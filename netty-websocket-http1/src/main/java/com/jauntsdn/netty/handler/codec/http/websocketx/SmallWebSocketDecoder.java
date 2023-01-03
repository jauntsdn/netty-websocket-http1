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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;

final class SmallWebSocketDecoder extends WebSocketDecoder {
  int state = STATE_NON_PARTIAL;
  int partialPrefix;
  ByteBuf partialPayload;
  int partialRemaining;
  int opcode;
  boolean fin;

  /* non-negative value means fragmentation is in progress*/
  int fragmentedTotalLength = WebSocketProtocol.VALIDATION_RESULT_NON_FRAGMENTING;

  SmallWebSocketDecoder(int maxFramePayloadLength) {
    super(maxFramePayloadLength);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    ByteBuf partPayload = partialPayload;
    if (partPayload != null) {
      partPayload.release();
    }
    super.channelInactive(ctx);
  }

  @Override
  void decode(ChannelHandlerContext ctx, ByteBuf in) {
    int st = state;
    int readableBytes = in.readableBytes();
    while (readableBytes > 0) {
      switch (st) {
        case STATE_NON_PARTIAL:
          if (readableBytes == 1) {
            st = STATE_PARTIAL_PREFIX;
            partialPrefix = (in.readByte() << 8) & 0xFFFF;
            readableBytes = 0;
          } else {
            short prefix = in.readShort();
            readableBytes -= Short.BYTES;

            int flagsAndOpcode = prefix >> 8;
            int flags = flagsAndOpcode & 0xF0;
            int code = flagsAndOpcode & 0xF;
            int length = prefix & 0x7F;
            boolean maskFlag = (prefix & 0x80) == 0x80;

            if (maskFlag) {
              WebSocketProtocol.close(
                  ctx,
                  this,
                  WebSocketCloseStatus.NORMAL_CLOSURE,
                  "frames masking is not supported");
              return;
            }

            boolean finFlag = prefix < 0;
            int result =
                WebSocketProtocol.validate(
                    ctx, this, flags, code, length, fragmentedTotalLength, maxFramePayloadLength);
            if (result == WebSocketProtocol.VALIDATION_RESULT_INVALID) {
              return;
            }
            fragmentedTotalLength = result;

            if (readableBytes >= length) {
              ByteBuf payload = in.readRetainedSlice(length);
              readableBytes -= length;
              onFrameRead(ctx, finFlag, code, payload);
            } else {
              opcode = code;
              fin = finFlag;
              partialPayload = partialPayload(ctx, in, length);
              partialRemaining = length - readableBytes;
              readableBytes = 0;
              st = STATE_PARTIAL_PAYLOAD;
            }
          }
          break;

        case STATE_PARTIAL_PREFIX:
          int prefix = partialPrefix;
          prefix |= in.readByte();
          readableBytes -= 1;

          int flagsAndOpcode = prefix >> 8;
          int flags = flagsAndOpcode & 0xF0;
          int code = flagsAndOpcode & 0xF;
          int length = prefix & 0x7F;
          boolean maskFlag = (prefix & 0x80) == 0x80;
          boolean finFlag = (short) prefix < 0;

          if (maskFlag) {
            WebSocketProtocol.close(
                ctx, this, WebSocketCloseStatus.NORMAL_CLOSURE, "frames masking is not supported");
            return;
          }

          int result =
              WebSocketProtocol.validate(
                  ctx, this, flags, code, length, fragmentedTotalLength, maxFramePayloadLength);
          if (result == WebSocketProtocol.VALIDATION_RESULT_INVALID) {
            return;
          }
          fragmentedTotalLength = result;

          if (readableBytes >= length) {
            ByteBuf payload = in.readRetainedSlice(length);
            readableBytes -= length;
            st = STATE_NON_PARTIAL;
            onFrameRead(ctx, finFlag, code, payload);
          } else {
            opcode = code;
            fin = finFlag;
            partialPayload = partialPayload(ctx, in, length);
            partialRemaining = length - readableBytes;
            readableBytes = 0;
            st = STATE_PARTIAL_PAYLOAD;
          }
          break;

        case STATE_PARTIAL_PAYLOAD:
          int remaining = partialRemaining;
          int toRead = Math.min(readableBytes, remaining);
          ByteBuf partial = partialPayload;
          partial.writeBytes(in, toRead);
          remaining -= toRead;
          readableBytes -= toRead;
          if (remaining == 0) {
            partialPayload = null;
            onFrameRead(ctx, fin, opcode, partial);
            st = STATE_NON_PARTIAL;
          } else {
            partialRemaining = remaining;
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

  static ByteBuf partialPayload(ChannelHandlerContext ctx, ByteBuf in, int length) {
    ByteBuf partial = ctx.alloc().buffer(length);
    partial.writeBytes(in);
    return partial;
  }
}
