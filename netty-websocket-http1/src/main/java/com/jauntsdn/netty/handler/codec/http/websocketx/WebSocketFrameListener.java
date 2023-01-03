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
}
