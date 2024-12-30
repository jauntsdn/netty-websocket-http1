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

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameEncoder;
import java.util.Objects;

final class WebSocketServerNomaskingHandshaker extends WebSocketServerHandshaker {
  private boolean nomaskingExtension;

  WebSocketServerNomaskingHandshaker(
      String webSocketURL, String subprotocols, WebSocketDecoderConfig webSocketDecoderConfig) {
    super(
        Objects.requireNonNull(webSocketURL, "webSocketURL"), subprotocols, webSocketDecoderConfig);
  }

  public static boolean supportsNoMaskingExtension(String webSocketURL) {
    return webSocketURL.startsWith("wss");
  }

  @Override
  protected WebSocketFrameDecoder newWebsocketDecoder() {
    WebSocketDecoderConfig decoderConfig = decoderConfig();

    if (nomaskingExtension) {
      return WebSocketCallbacksFrameDecoder.frameDecoder(
          decoderConfig.maxFramePayloadLength(), false, false);
    }
    return WebSocketCallbacksFrameDecoder.frameDecoder(
        decoderConfig.maxFramePayloadLength(),
        decoderConfig.expectMaskedFrames(),
        decoderConfig.allowMaskMismatch());
  }

  @Override
  protected WebSocketFrameEncoder newWebSocketEncoder() {
    return WebSocketCallbacksFrameEncoder.frameEncoder(false);
  }

  @Override
  protected FullHttpResponse newHandshakeResponse(
      FullHttpRequest handshakeRequest, HttpHeaders headers) {
    FullHttpResponse handshakeResponse = super.newHandshakeResponse(handshakeRequest, headers);
    return nomaskingExtensionComplete(handshakeRequest, handshakeResponse);
  }

  private FullHttpResponse nomaskingExtensionComplete(
      FullHttpRequest handshakeRequest, FullHttpResponse handshakeResponse) {
    if (handshakeRequest
        .headers()
        .containsValue(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "no-masking", true)) {
      handshakeResponse.headers().set(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, "no-masking");
      nomaskingExtension = true;
    }
    return handshakeResponse;
  }
}
