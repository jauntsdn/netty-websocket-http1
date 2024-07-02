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
import io.netty.util.ByteProcessor;
import io.netty.util.CharsetUtil;

/**
 * Listener for webSocket frames, combined with essential Netty ChannelHandler and lifecycle
 * callbacks
 */
public interface WebSocketFrameListener {

  /*handler callbacks*/

  void onChannelRead(
      ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload);

  default void onChannelReadComplete(ChannelHandlerContext ctx) {
    ctx.fireChannelReadComplete();
  }

  default void onUserEventTriggered(ChannelHandlerContext ctx, Object evt) {
    ctx.fireUserEventTriggered(evt);
  }

  default void onChannelWritabilityChanged(ChannelHandlerContext ctx) {
    ctx.fireChannelWritabilityChanged();
  }

  default void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    ctx.fireExceptionCaught(cause);
  }

  /*lifecycle*/

  default void onOpen(ChannelHandlerContext ctx) {}

  default void onClose(ChannelHandlerContext ctx) {}

  /** Utility to decode Close frame payload */
  final class CloseFramePayload {
    private CloseFramePayload() {}

    public static int statusCode(ByteBuf payload) {
      if (payload.readableBytes() < Short.BYTES) {
        return -1;
      }
      return payload.getShort(0);
    }

    public static String reason(ByteBuf payload) {
      if (payload.readableBytes() <= Short.BYTES) {
        return "";
      }
      return payload.toString(
          Short.BYTES, payload.readableBytes() - Short.BYTES, CharsetUtil.UTF_8);
    }
  }

  /**
   * UTF8 finite state machine based implementation from
   * https://bjoern.hoehrmann.de/utf-8/decoder/dfa/
   */
  final class Utf8FrameValidator implements ByteProcessor {
    public static final int UTF8_VALIDATION_ERROR_CODE = 1007;
    public static final String UTF8_VALIDATION_ERROR_MESSAGE =
        "inbound text frame with non-utf8 contents";

    private static final int UTF8_ACCEPT = 0;
    private static final int UTF8_REJECT = 12;

    private static final byte[] TYPES = {
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
      9, 9, 9, 9, 9, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
      7, 7, 7, 7, 7, 7, 8, 8, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
      2, 2, 2, 2, 2, 2, 2, 10, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 3, 3, 11, 6, 6, 6, 5, 8, 8, 8,
      8, 8, 8, 8, 8, 8, 8, 8
    };

    private static final byte[] STATES = {
      0, 12, 24, 36, 60, 96, 84, 12, 12, 12, 48, 72, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12,
      12, 0, 12, 12, 12, 12, 12, 0, 12, 0, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12, 24, 12, 12,
      12, 12, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 12, 12, 24, 12,
      12, 12, 12, 12, 12, 12, 12, 12, 36, 12, 36, 12, 12, 12, 36, 12, 12, 12, 12, 12, 36, 12, 36,
      12, 12, 12, 36, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12
    };

    int state = UTF8_ACCEPT;
    int codep;

    private Utf8FrameValidator() {}

    public static Utf8FrameValidator create() {
      return new Utf8FrameValidator();
    }

    /**
     * @param buffer text frame payload
     * @return true if payload is utf8 encoded, false otherwise
     */
    public boolean validateTextFrame(ByteBuf buffer) {
      buffer.forEachByte(this);
      int st = state;
      state = UTF8_ACCEPT;
      codep = 0;
      return st == UTF8_ACCEPT;
    }

    /**
     * @param buffer text fragment frame payload
     * @return true if payload is utf8 encoded, false otherwise
     */
    public boolean validateTextFragmentStart(ByteBuf buffer) {
      buffer.forEachByte(this);
      return state != UTF8_REJECT;
    }

    /**
     * @param buffer text fragment frame payload
     * @return true if payload is utf8 encoded, false otherwise
     */
    public boolean validateFragmentContinuation(ByteBuf buffer) {
      return validateTextFragmentStart(buffer);
    }

    /**
     * @param buffer text fragment frame payload
     * @return true if payload is utf8 encoded, false otherwise
     */
    public boolean validateFragmentEnd(ByteBuf buffer) {
      return validateTextFrame(buffer);
    }

    @Override
    public boolean process(byte bufferByte) {
      byte type = TYPES[bufferByte & 0xFF];
      int st = state;
      codep = st != UTF8_ACCEPT ? bufferByte & 0x3f | codep << 6 : 0xff >> type & bufferByte;
      st = state = STATES[st + type];

      return st != UTF8_REJECT;
    }
  }
}
