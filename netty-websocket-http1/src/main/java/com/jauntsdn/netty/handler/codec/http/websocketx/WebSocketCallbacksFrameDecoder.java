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
import io.netty.handler.codec.http.websocketx.WebSocketFrameDecoder;

interface WebSocketCallbacksFrameDecoder extends WebSocketFrameDecoder {

  void frameListener(
      ChannelHandlerContext ctx,
      WebSocketFrameListener webSocketFrameListener,
      WebSocketFrameFactory frameFactory);

  static WebSocketCallbacksFrameDecoder frameDecoder(
      boolean maskPayload,
      int maxFramePayloadLength,
      boolean expectMaskedFrames,
      boolean allowMaskMismatch) {

    return frameDecoder(maxFramePayloadLength, expectMaskedFrames, allowMaskMismatch);
  }

  static WebSocketCallbacksFrameDecoder frameDecoder(
      int maxFramePayloadLength, boolean expectMaskedFrames, boolean allowMaskMismatch) {

    Boolean strictExpectMaskedFrames = null;
    if (!allowMaskMismatch) {
      strictExpectMaskedFrames = expectMaskedFrames;
    }
    return new DefaultWebSocketDecoder(maxFramePayloadLength, strictExpectMaskedFrames);
  }
}
