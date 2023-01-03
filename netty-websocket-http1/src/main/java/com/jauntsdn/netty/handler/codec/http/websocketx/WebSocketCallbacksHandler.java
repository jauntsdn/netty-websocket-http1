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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.internal.ObjectUtil;

/** Handler to process successfully handshaked webSocket */
public interface WebSocketCallbacksHandler {

  WebSocketFrameListener exchange(
      ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory);

  static WebSocketFrameFactory exchange(
      ChannelHandlerContext ctx, WebSocketCallbacksHandler webSocketCallbacksHandler) {
    ObjectUtil.checkNotNull(ctx, "ctx");
    ObjectUtil.checkNotNull(webSocketCallbacksHandler, "webSocketFrameListener");

    ChannelPipeline p = ctx.pipeline();
    ChannelHandlerContext frameFactoryCtx = p.context(WebSocketCallbacksFrameEncoder.class);
    if (frameFactoryCtx == null) {
      throw new IllegalStateException(
          "WebSocketCallbacksFrameEncoder is not present in channel pipeline");
    }
    WebSocketCallbacksFrameEncoder encoder =
        (WebSocketCallbacksFrameEncoder) frameFactoryCtx.handler();

    ChannelHandlerContext decoderCtx = p.context(WebSocketCallbacksFrameDecoder.class);
    if (decoderCtx == null) {
      throw new IllegalStateException(
          "WebSocketCallbacksFrameDecoder is not present in channel pipeline");
    }
    WebSocketCallbacksFrameDecoder decoder = (WebSocketCallbacksFrameDecoder) decoderCtx.handler();

    WebSocketFrameFactory frameFactory = encoder.frameFactory(ctx);
    WebSocketFrameListener webSocketFrameListener =
        webSocketCallbacksHandler.exchange(ctx, frameFactory);
    decoder.frameListener(ctx, webSocketFrameListener, frameFactory);
    webSocketFrameListener.onOpen(ctx);
    return frameFactory;
  }
}
