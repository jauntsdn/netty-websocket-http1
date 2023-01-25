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

abstract class SmallWebSocketDecoder extends WebSocketDecoder {
  static final int MAX_FRAME_PAYLOAD_LENGTH = 125;
  /* [8bit frag total length][8bit partial prefix][1bit unused][1bit fin][4bit opcode][2bit state] */
  int encodedState = encodeFragmentedLength(0, WebSocketProtocol.VALIDATION_RESULT_NON_FRAGMENTING);
  ByteBuf partialPayload;

  SmallWebSocketDecoder() {}

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
    int encodedSt = encodedState;
    int st = decodeState(encodedSt);
    int readableBytes = in.readableBytes();
    while (readableBytes > 0) {
      switch (st) {
        case STATE_NON_PARTIAL:
          if (readableBytes == 1) {
            st = STATE_PARTIAL_PREFIX;
            encodedSt = encodePartialPrefix(encodedSt, in.readByte());
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
                    ctx,
                    this,
                    flags,
                    code,
                    length,
                    decodeFragmentedLength(encodedSt),
                    MAX_FRAME_PAYLOAD_LENGTH);
            if (result == WebSocketProtocol.VALIDATION_RESULT_INVALID) {
              return;
            }
            encodedSt = encodeFragmentedLength(encodedSt, result);

            if (readableBytes >= length) {
              ByteBuf payload = in.readRetainedSlice(length);
              readableBytes -= length;
              onFrameRead(ctx, finFlag, code, payload);
            } else {
              encodedSt = encodeFlags(encodedSt, code, finFlag);
              partialPayload = partialPayload(ctx, in, length);
              readableBytes = 0;
              st = STATE_PARTIAL_PAYLOAD;
            }
          }
          break;

        case STATE_PARTIAL_PREFIX:
          int prefix = decodePartialPrefix(encodedSt) | in.readByte();
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
          int fragmentedTotalLength = decodeFragmentedLength(encodedSt);

          int result =
              WebSocketProtocol.validate(
                  ctx, this, flags, code, length, fragmentedTotalLength, MAX_FRAME_PAYLOAD_LENGTH);
          if (result == WebSocketProtocol.VALIDATION_RESULT_INVALID) {
            return;
          }
          fragmentedTotalLength = result;
          encodedSt = encodeFragmentedLength(encodedSt, fragmentedTotalLength);

          if (readableBytes >= length) {
            ByteBuf payload = in.readRetainedSlice(length);
            readableBytes -= length;
            st = STATE_NON_PARTIAL;
            onFrameRead(ctx, finFlag, code, payload);
          } else {
            encodedSt = encodeFlags(encodedSt, code, finFlag);
            partialPayload = partialPayload(ctx, in, length);
            readableBytes = 0;
            st = STATE_PARTIAL_PAYLOAD;
          }
          break;

        case STATE_PARTIAL_PAYLOAD:
          ByteBuf partial = partialPayload;
          int remaining = partial.capacity() - partial.writerIndex();
          int toRead = Math.min(readableBytes, remaining);
          partial.writeBytes(in, toRead);
          remaining -= toRead;
          readableBytes -= toRead;
          if (remaining == 0) {
            partialPayload = null;
            int opcodeFin = decodeFlags(encodedSt);
            int opcode = decodeFlagOpcode(opcodeFin);
            boolean fin = decodeFlagFin(opcodeFin);
            onFrameRead(ctx, fin, opcode, partial);
            st = STATE_NON_PARTIAL;
          }
          break;

          /*only release inbound buffer*/
        case STATE_CLOSED_INBOUND:
          break;

        default:
          throw new IllegalStateException("unexpected decoding state: " + st);
      }
    }
    encodedState = encodeState(encodedSt, st);
  }

  @Override
  void closeInbound() {
    encodedState = encodeState(encodedState, STATE_CLOSED_INBOUND);
  }

  static ByteBuf partialPayload(ChannelHandlerContext ctx, ByteBuf in, int length) {
    ByteBuf partial = ctx.alloc().buffer(length);
    partial.writeBytes(in);
    return partial;
  }

  /* layout description is on "encodedState" field */

  static int encodeState(int encodedState, int state) {
    return encodedState & 0xFF_FF_FF_FC | state;
  }

  static int decodeState(int encodedState) {
    return encodedState & 0x3;
  }

  static int encodeFlags(int encodedState, int opcode, boolean fin) {
    int flags = (fin ? (byte) 1 : 0) << 6 | opcode << 2;
    return encodedState & 0xFF_FF_FF_F3 | flags;
  }

  static int decodeFlags(int encodedState) {
    return (encodedState & 0xFC) >> 2;
  }

  static int decodeFlagOpcode(int flags) {
    return flags & 0xF;
  }

  static boolean decodeFlagFin(int flags) {
    return (flags & 0x10) == 0x10;
  }

  static int encodePartialPrefix(int encodedState, byte partialPrefix) {
    return encodedState & 0xFF_FF_00_FF | (partialPrefix << 8) & 0xFFFF;
  }

  static int decodePartialPrefix(int encodedState) {
    return encodedState & 0xFF_00;
  }

  static int encodeFragmentedLength(int encodedState, int fragmentedTotalLength) {
    return encodedState & 0xFF_FF | fragmentedTotalLength << 16;
  }

  /* non-negative value means fragmentation is in progress*/
  static int decodeFragmentedLength(int encodedState) {
    return (byte) ((encodedState & 0xFF_00_00) >> 16);
  }

  static final class WithMaskingEncoder extends SmallWebSocketDecoder {

    @Override
    WebSocketFrameFactory frameFactory() {
      return MaskingWebSocketEncoder.FrameFactory.INSTANCE;
    }
  }

  static final class WithNonMaskingEncoder extends SmallWebSocketDecoder {

    @Override
    WebSocketFrameFactory frameFactory() {
      return NonMaskingWebSocketEncoder.FrameFactory.INSTANCE;
    }
  }
}
