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

abstract class WebSocketDecoder implements WebSocketCallbacksFrameDecoder {

  static final int STATE_NON_PARTIAL = 0;
  static final int STATE_PARTIAL_PREFIX = 1;
  static final int STATE_PARTIAL_MASK = 2;
  static final int STATE_PARTIAL_PAYLOAD = 3;
  static final int STATE_CLOSED_INBOUND = 4;

  WebSocketFrameListener webSocketFrameListener;

  WebSocketDecoder() {}

  abstract void decode(ChannelHandlerContext ctx, ByteBuf buf);

  abstract WebSocketFrameFactory frameFactory();

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    WebSocketFrameListener listener = webSocketFrameListener;
    if (listener != null) {
      listener.onClose(ctx);
    }
    ctx.fireChannelInactive();
  }

  @Override
  public final void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof ByteBuf) {
      ByteBuf buf = (ByteBuf) msg;
      try {
        decode(ctx, buf);
      } finally {
        buf.release();
      }
      return;
    }
    ctx.fireChannelRead(msg);
  }

  @Override
  public void frameListener(
      ChannelHandlerContext ctx,
      WebSocketFrameListener webSocketFrameListener,
      WebSocketFrameFactory frameFactory) {
    this.webSocketFrameListener = webSocketFrameListener;
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) {
    WebSocketFrameListener listener = webSocketFrameListener;
    if (listener != null) {
      listener.onChannelReadComplete(ctx);
    } else {
      ctx.fireChannelReadComplete();
    }
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
    WebSocketFrameListener listener = webSocketFrameListener;
    if (listener != null) {
      listener.onUserEventTriggered(ctx, evt);
    } else {
      ctx.fireUserEventTriggered(evt);
    }
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) {
    WebSocketFrameListener listener = webSocketFrameListener;
    if (listener != null) {
      listener.onChannelWritabilityChanged(ctx);
    } else {
      ctx.fireChannelWritabilityChanged();
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    WebSocketFrameListener listener = webSocketFrameListener;
    if (listener != null) {
      listener.onExceptionCaught(ctx, cause);
    } else {
      ctx.fireExceptionCaught(cause);
    }
  }

  abstract void closeInbound();

  final void onFrameRead(ChannelHandlerContext ctx, boolean finFlag, int opcode, ByteBuf payload) {
    webSocketFrameListener.onChannelRead(ctx, finFlag, 0, opcode, payload);
  }

  /*boilerplate*/

  @Override
  public void channelRegistered(ChannelHandlerContext ctx) {
    ctx.fireChannelRegistered();
  }

  @Override
  public void channelUnregistered(ChannelHandlerContext ctx) {
    ctx.fireChannelUnregistered();
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    ctx.fireChannelActive();
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    /*noop*/
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) {
    /*noop*/
  }
}
