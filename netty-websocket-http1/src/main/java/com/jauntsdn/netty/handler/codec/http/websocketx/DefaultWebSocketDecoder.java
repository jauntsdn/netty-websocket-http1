/*
 * Copyright 2022 - present Maksym Ostroverkhov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jauntsdn.netty.handler.codec.http.websocketx;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;

final class DefaultWebSocketDecoder extends WebSocketDecoder {
  /*if null, mask mismatch is allowed*/
  final Boolean expectMaskedFrames;
  final int maxFramePayloadLength;

  WebSocketFrameFactory frameFactory;

  int state = STATE_NON_PARTIAL;
  ByteBuf partialPrefix;
  int partialMask;
  ByteBuf partialPayload;
  int partialRemaining;

  int frameFlags;
  int opcode;
  boolean fin;
  boolean masking;
  int length;

  /* non-negative value means fragmentation is in progress*/
  int fragmentedTotalLength = WebSocketProtocol.VALIDATION_RESULT_NON_FRAGMENTING;

  DefaultWebSocketDecoder(int maxFramePayloadLength, Boolean expectMaskedFrames) {
    this.maxFramePayloadLength = maxFramePayloadLength;
    this.expectMaskedFrames = expectMaskedFrames;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    partialPrefix = ctx.alloc().buffer(2, 2);
    super.handlerAdded(ctx);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    partialPrefix.release();
    ByteBuf payload = partialPayload;
    if (payload != null) {
      payload.release();
    }
    super.channelInactive(ctx);
  }

  @Override
  public void frameListener(
      ChannelHandlerContext ctx,
      WebSocketFrameListener webSocketFrameListener,
      WebSocketFrameFactory frameFactory) {
    super.frameListener(ctx, webSocketFrameListener, frameFactory);
    this.frameFactory = frameFactory;
  }

  @Override
  WebSocketFrameFactory frameFactory() {
    return frameFactory;
  }

  @Override
  void decode(ChannelHandlerContext ctx, ByteBuf in) {
    int st = state;
    int readableBytes = in.readableBytes();
    while (readableBytes > 0) {
      switch (st) {
        case STATE_NON_PARTIAL:
          /*small and medium prefix*/
          if (readableBytes >= 4) {
            int prefix = in.getInt(in.readerIndex());
            int flags = (prefix >> 24) & 0xF0;
            boolean finFlag = prefix < 0;
            int maskAndLen = prefix >> 16;
            int len = maskAndLen & 0x7F;
            boolean maskFlag = (maskAndLen & 0x80) == 0x80;
            int code = (prefix >> 24) & 0xF;

            Boolean expectMasked = expectMaskedFrames;
            if (expectMasked != null && maskFlag != expectMasked) {
              WebSocketProtocol.close(
                  ctx,
                  this,
                  WebSocketCloseStatus.PROTOCOL_ERROR,
                  "frame mask flag expectation mismatch");
              return;
            }
            int length;
            int prefixLength;
            if (len <= 125) {
              length = len;
              prefixLength = Short.BYTES;
            } else if (len == 126) {
              length = (prefix & 0xFFFF);
              prefixLength = Integer.BYTES;
            } else {
              /*large frames are not supported*/
              WebSocketProtocol.close(
                  ctx,
                  this,
                  WebSocketCloseStatus.MESSAGE_TOO_BIG,
                  "frame payload is too large - exceeds 65535");
              return;
            }

            int result =
                WebSocketProtocol.validate(
                    ctx, this, flags, code, length, fragmentedTotalLength, maxFramePayloadLength);
            if (result == WebSocketProtocol.VALIDATION_RESULT_INVALID) {
              return;
            }
            fragmentedTotalLength = result;

            in.skipBytes(prefixLength);
            readableBytes -= prefixLength;
            int mask = 0;
            if (maskFlag) {
              if (readableBytes >= 4) {
                mask = in.readInt();
                readableBytes -= 4;
              } else {
                this.length = length;
                partialRemaining = 4;
                partialMask = 0;
                opcode = code;
                fin = finFlag;
                st = STATE_PARTIAL_MASK;
                continue;
              }
            }
            if (readableBytes >= length) {
              ByteBuf payload = in.readRetainedSlice(length);
              if (maskFlag) {
                unmask(payload, mask);
              }
              readableBytes -= length;
              onFrameRead(ctx, finFlag, code, payload);
            } else {
              if (maskFlag) {
                /*mask is fully read at this stage*/
                partialMask = mask;
                masking = true;
              }
              ByteBuf partial = partialPayload = ctx.alloc().buffer(length);
              if (readableBytes > 0) {
                partial.writeBytes(in);
              }
              partialRemaining = length - readableBytes;
              readableBytes = 0;
              opcode = code;
              fin = finFlag;
              st = STATE_PARTIAL_PAYLOAD;
            }
            /*small prefix only: [2,3]*/
          } else if (readableBytes >= 2) {
            short prefix = in.readShort();
            readableBytes -= Short.BYTES;

            boolean finFlag = fin = prefix < 0;
            int flagsAndOpcode = prefix >> 8;
            int code = opcode = flagsAndOpcode & 0xF;
            int flags = frameFlags = flagsAndOpcode & 0xF0;
            int len = prefix & 0x7F;
            boolean maskFlag = masking = (prefix & 0x80) == 0x80;

            Boolean expectMasked = expectMaskedFrames;
            if (expectMasked != null && maskFlag != expectMasked) {
              WebSocketProtocol.close(
                  ctx,
                  this,
                  WebSocketCloseStatus.PROTOCOL_ERROR,
                  "frame mask flag expectation mismatch");
              return;
            }
            int length;
            /* small prefix is read*/
            if (len <= 125) {
              length = len;
              int result =
                  WebSocketProtocol.validate(
                      ctx, this, flags, code, length, fragmentedTotalLength, maxFramePayloadLength);
              if (result == WebSocketProtocol.VALIDATION_RESULT_INVALID) {
                return;
              }
              fragmentedTotalLength = result;

              if (maskFlag) {
                this.length = length;
                partialRemaining = 4;
                partialMask = 0;
                st = STATE_PARTIAL_MASK;
                continue;
              }
              /*full frame available: 0 or 1 length*/
              if (length <= readableBytes) {
                if (length == 0) {
                  onFrameRead(ctx, finFlag, code, Unpooled.EMPTY_BUFFER);
                } else {
                  onFrameRead(ctx, finFlag, code, in.readRetainedSlice(length));
                  readableBytes -= length;
                }
                st = STATE_NON_PARTIAL;
                /*partial payload*/
              } else {
                ByteBuf partial = ctx.alloc().buffer(length);
                partial.writeBytes(in);
                partialPayload = partial;
                partialRemaining = length - readableBytes;
                readableBytes = 0;
                opcode = code;
                fin = finFlag;
                st = STATE_PARTIAL_PAYLOAD;
              }
              /*prefix is next 2 bytes*/
            } else if (len == 126) {
              this.length = len;
              if (readableBytes > 0) {
                partialPrefix.writeBytes(in);
              }
              partialRemaining = 2 - readableBytes;
              readableBytes = 0;
              opcode = code;
              fin = finFlag;
              st = STATE_PARTIAL_PREFIX;
            } else {
              /*large frames are not supported*/
              WebSocketProtocol.close(
                  ctx,
                  this,
                  WebSocketCloseStatus.MESSAGE_TOO_BIG,
                  "frame payload is too large - exceeds 65535");
              return;
            }
            /*half of small prefix: 1 byte*/
          } else {
            partialPrefix.writeBytes(in);
            readableBytes = 0;
            partialRemaining = 1;
            st = STATE_PARTIAL_PREFIX;
          }
          break;

        case STATE_PARTIAL_PREFIX:
          {
            int toRead = Math.min(readableBytes, partialRemaining);
            partialPrefix.writeBytes(in, toRead);
            readableBytes -= toRead;
            int remaining = partialRemaining -= toRead;
            if (remaining == 0) {
              /*partial prefix is extended length*/
              if (length == 126) {
                length = 0;
                int extendedLength = partialPrefix.getUnsignedShort(0);
                partialPrefix.writerIndex(0);

                int result =
                    WebSocketProtocol.validate(
                        ctx,
                        this,
                        frameFlags,
                        opcode,
                        extendedLength,
                        fragmentedTotalLength,
                        maxFramePayloadLength);
                if (result == WebSocketProtocol.VALIDATION_RESULT_INVALID) {
                  return;
                }
                fragmentedTotalLength = result;

                if (masking) {
                  length = extendedLength;
                  partialRemaining = 4;
                  partialMask = 0;
                  st = STATE_PARTIAL_MASK;
                  continue;
                }
                /*read whole frame*/
                if (extendedLength <= readableBytes) {
                  onFrameRead(ctx, fin, opcode, in.readRetainedSlice(extendedLength));
                  readableBytes -= extendedLength;
                  st = STATE_NON_PARTIAL;
                } else {
                  ByteBuf partial = ctx.alloc().buffer(extendedLength);
                  partial.writeBytes(in);
                  partialPayload = partial;
                  partialRemaining = extendedLength - readableBytes;
                  readableBytes = 0;
                  st = STATE_PARTIAL_PAYLOAD;
                }
                /*partialPrefix is flags and length*/
              } else {
                short prefix = partialPrefix.getShort(0);
                partialPrefix.writerIndex(0);

                fin = prefix < 0;
                int flagsAndOpcode = prefix >> 8;
                int flags = flagsAndOpcode & 0xF0;
                int code = opcode = flagsAndOpcode & 0xF;
                int len = prefix & 0x7F;
                boolean maskFlag = masking = (prefix & 0x80) == 0x80;

                Boolean expectMasked = expectMaskedFrames;
                if (expectMasked != null && maskFlag != expectMasked) {
                  WebSocketProtocol.close(
                      ctx,
                      this,
                      WebSocketCloseStatus.PROTOCOL_ERROR,
                      "frame mask flag expectation mismatch");
                  return;
                }
                /*small length: proceed to next state - mask or partial_payload*/
                if (len <= 125) {
                  int result =
                      WebSocketProtocol.validate(
                          ctx,
                          this,
                          flags,
                          code,
                          len,
                          fragmentedTotalLength,
                          maxFramePayloadLength);
                  if (result == WebSocketProtocol.VALIDATION_RESULT_INVALID) {
                    return;
                  }
                  fragmentedTotalLength = result;

                  if (maskFlag) {
                    length = len;
                    partialRemaining = 4;
                    partialMask = 0;
                    st = STATE_PARTIAL_MASK;
                    continue;
                  }
                  length = 0;
                  partialPayload = ctx.alloc().buffer(len);
                  partialRemaining = len;
                  st = STATE_PARTIAL_PAYLOAD;

                  /*extended length: read next short*/
                } else if (len == 126) {
                  if (readableBytes >= 2) {

                    len = in.readUnsignedShort();
                    readableBytes -= 2;

                    int result =
                        WebSocketProtocol.validate(
                            ctx,
                            this,
                            flags,
                            code,
                            len,
                            fragmentedTotalLength,
                            maxFramePayloadLength);
                    if (result == WebSocketProtocol.VALIDATION_RESULT_INVALID) {
                      return;
                    }
                    fragmentedTotalLength = result;

                    if (maskFlag) {
                      length = len;
                      partialRemaining = 4;
                      partialMask = 0;
                      st = STATE_PARTIAL_MASK;
                      continue;
                    }

                    /*proceeded to next state*/
                    length = 0;
                    partialPayload = ctx.alloc().buffer(len);
                    partialRemaining = len;
                    st = STATE_PARTIAL_PAYLOAD;
                    /*extended length partial: state is same*/
                  } else {
                    length = len;
                    if (readableBytes > 0) {
                      partialPrefix.writeByte(in.readByte());
                      readableBytes -= 1;
                      partialRemaining = 1;
                    } else {
                      partialRemaining = 2;
                    }
                  }
                } else {
                  /*large frames are not supported*/
                  WebSocketProtocol.close(
                      ctx,
                      this,
                      WebSocketCloseStatus.MESSAGE_TOO_BIG,
                      "frame payload is too large - exceeds 65535");
                  return;
                }
              }
            }
          }
          break;
        case STATE_PARTIAL_MASK:
          {
            int remaining = partialRemaining;
            int toRead = Math.min(readableBytes, remaining);
            readableBytes -= toRead;
            int mask = partialMask;
            for (int i = 0; i < toRead; i++) {
              remaining--;
              mask |= (in.readByte() & 0xFF) << 8 * remaining;
            }
            if (remaining == 0) {
              /*proceed to partial payload / non_partial*/
              int len = length;
              length = 0;
              /*full payload is available*/
              if (len <= readableBytes) {
                ByteBuf payload = in.readRetainedSlice(len);
                readableBytes -= len;
                unmask(payload, mask);
                onFrameRead(ctx, fin, opcode, payload);
                st = STATE_NON_PARTIAL;

                /*partial payload*/
              } else {
                partialMask = mask;
                partialPayload = ctx.alloc().buffer(len);
                partialRemaining = len;
                st = STATE_PARTIAL_PAYLOAD;
              }
            } else {
              partialMask = mask;
              partialRemaining = remaining;
            }
          }
          break;
        case STATE_PARTIAL_PAYLOAD:
          int remaining = partialRemaining;
          int toRead = Math.min(readableBytes, remaining);
          ByteBuf partial = partialPayload;
          partial.writeBytes(in, toRead);
          remaining = partialRemaining -= toRead;
          readableBytes -= toRead;
          if (remaining == 0) {
            partialPayload = null;
            if (masking) {
              unmask(partial, partialMask);
            }
            onFrameRead(ctx, fin, opcode, partial);
            st = STATE_NON_PARTIAL;
          }
          break;
          /*only release inbound buffer*/
        case STATE_CLOSED_INBOUND:
          break;

        default:
          throw new IllegalStateException("unexpected decoding state: " + state);
      }
    }
    state = st;
  }

  @Override
  void closeInbound() {
    state = STATE_CLOSED_INBOUND;
  }

  static void unmask(ByteBuf payload, int mask) {
    int readableBytes = payload.readableBytes();
    int cur = 0;

    if (readableBytes >= 8) {
      long longMask = (long) mask & 0xFFFFFFFFL;
      longMask |= longMask << 32;

      for (; cur < readableBytes - 7; cur += 8) {
        payload.setLong(cur, payload.getLong(cur) ^ longMask);
      }
    }

    if (cur < readableBytes - 3) {
      payload.setInt(cur, payload.getInt(cur) ^ mask);
      cur += 4;
    }

    int offset = 0;
    for (; cur < readableBytes; cur++) {
      payload.setByte(cur, payload.getByte(cur) ^ byteAtIndex(mask, offset++ & 3));
    }
  }

  static int byteAtIndex(int mask, int index) {
    return (mask >> 8 * (3 - index)) & 0xFF;
  }
}
