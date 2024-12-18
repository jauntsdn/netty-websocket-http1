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

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker13;
import io.netty.handler.codec.http.websocketx.WebSocketFrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import java.net.URI;
import java.util.Objects;

public class WebSocketClientHandshaker extends WebSocketClientHandshaker13 {
  private final boolean performMasking;
  private final boolean expectMaskedFrames;
  private final boolean allowMaskMismatch;

  public WebSocketClientHandshaker(
      URI webSocketURL,
      String subprotocol,
      HttpHeaders customHeaders,
      int maxFramePayloadLength,
      boolean performMasking,
      boolean expectMaskedFrames,
      boolean allowMaskMismatch) {
    super(
        Objects.requireNonNull(webSocketURL, "webSocketURL"),
        WebSocketVersion.V13,
        subprotocol,
        false,
        customHeaders,
        maxFramePayloadLength,
        performMasking,
        allowMaskMismatch,
        /*unused*/ -1);
    this.performMasking = performMasking;
    this.expectMaskedFrames = expectMaskedFrames;
    this.allowMaskMismatch = allowMaskMismatch;
  }

  @Override
  protected WebSocketFrameDecoder newWebsocketDecoder() {
    return WebSocketCallbacksFrameDecoder.frameDecoder(
        maxFramePayloadLength(), expectMaskedFrames, allowMaskMismatch);
  }

  @Override
  protected WebSocketFrameEncoder newWebSocketEncoder() {
    return WebSocketCallbacksFrameEncoder.frameEncoder(performMasking);
  }
}
