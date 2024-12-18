/*
 * Copyright 2024 - present Maksym Ostroverkhov.
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketFrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameEncoder;
import java.net.URI;
import java.util.Objects;

final class WebSocketClientNomaskingHandshaker extends WebSocketClientHandshaker {
  private Channel channel;

  WebSocketClientNomaskingHandshaker(
      URI webSocketURL,
      String subprotocol,
      HttpHeaders customHeaders,
      int maxFramePayloadLength,
      boolean expectMaskedFrames,
      boolean allowMaskMismatch) {
    super(
        Objects.requireNonNull(webSocketURL, "webSocketURL"),
        subprotocol,
        customHeaders,
        maxFramePayloadLength,
        false,
        expectMaskedFrames,
        allowMaskMismatch);
  }

  public static boolean supportsNoMaskingExtension(URI webSocketURL) {
    return webSocketURL.getScheme().startsWith("wss");
  }

  @Override
  protected WebSocketFrameDecoder newWebsocketDecoder() {
    return WebSocketCallbacksFrameDecoder.frameDecoder(maxFramePayloadLength(), false, false);
  }

  @Override
  protected WebSocketFrameEncoder newWebSocketEncoder() {
    return WebSocketCallbacksFrameEncoder.frameEncoder(false);
  }

  @Override
  protected FullHttpRequest newHandshakeRequest() {
    return nomaskingExtensionStart(super.newHandshakeRequest());
  }

  @Override
  public ChannelFuture handshake(Channel channel) {
    this.channel = channel;
    return super.handshake(channel);
  }

  @Override
  protected void verify(FullHttpResponse handshakeResponse) {
    super.verify(handshakeResponse);
    nomaskingExtensionComplete(handshakeResponse);
  }

  private FullHttpRequest nomaskingExtensionStart(FullHttpRequest handshakeRequest) {
    handshakeRequest.headers().add(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "no-masking");
    return handshakeRequest;
  }

  private void nomaskingExtensionComplete(FullHttpResponse handshakeResponse) {
    if (!handshakeResponse
        .headers()
        .containsValue(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "no-masking", true)) {
      ChannelPipeline pipeline = channel.pipeline();
      pipeline.replace(
          WebSocketFrameEncoder.class,
          "ws-encoder",
          WebSocketCallbacksFrameEncoder.frameEncoder(true));
    }
  }
}
