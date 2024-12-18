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
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker13;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslHandler;
import java.util.Objects;

public class WebSocketServerHandshaker extends WebSocketServerHandshaker13 {

  WebSocketServerHandshaker(
      String webSocketURL, String subprotocols, WebSocketDecoderConfig webSocketDecoderConfig) {
    super(
        Objects.requireNonNull(webSocketURL, "webSocketURL"), subprotocols, webSocketDecoderConfig);
  }

  @Override
  protected WebSocketFrameDecoder newWebsocketDecoder() {
    WebSocketDecoderConfig decoderConfig = decoderConfig();

    return WebSocketCallbacksFrameDecoder.frameDecoder(
        decoderConfig.maxFramePayloadLength(),
        decoderConfig.expectMaskedFrames(),
        decoderConfig.allowMaskMismatch());
  }

  @Override
  protected WebSocketFrameEncoder newWebSocketEncoder() {
    return WebSocketCallbacksFrameEncoder.frameEncoder(false);
  }

  public static final class Factory {
    private final String path;
    private final String subprotocols;
    private final boolean nomaskingExtension;
    private final WebSocketDecoderConfig decoderConfig;

    Factory(
        String path,
        String subprotocols,
        boolean nomaskingExtension,
        WebSocketDecoderConfig decoderConfig) {
      this.path = Objects.requireNonNull(path, "path");
      this.subprotocols = subprotocols;
      this.nomaskingExtension = nomaskingExtension;
      this.decoderConfig = Objects.requireNonNull(decoderConfig, "decoderConfig");
    }

    public WebSocketServerHandshaker newHandshaker(ChannelHandlerContext ctx, HttpRequest req) {
      CharSequence version = req.headers().get(HttpHeaderNames.SEC_WEBSOCKET_VERSION);
      if (version != null) {
        if (version.equals(WebSocketVersion.V13.toHttpHeaderValue())) {
          return webSocketHandshaker(webSocketUrl(ctx.pipeline(), req, path));
        }
      }
      return null;
    }

    private WebSocketServerHandshaker webSocketHandshaker(String uri) {
      return nomaskingExtension
              && WebSocketServerNomaskingHandshaker.supportsNoMaskingExtension(uri)
          ? new WebSocketServerNomaskingHandshaker(uri, subprotocols, decoderConfig)
          : new WebSocketServerHandshaker(uri, subprotocols, decoderConfig);
    }

    private static String webSocketUrl(ChannelPipeline cp, HttpRequest req, String path) {
      String protocol = "ws";
      if (cp.get(SslHandler.class) != null) {
        protocol = "wss";
      }
      String host = req.headers().get(HttpHeaderNames.HOST);
      return protocol + "://" + host + path;
    }
  }
}
