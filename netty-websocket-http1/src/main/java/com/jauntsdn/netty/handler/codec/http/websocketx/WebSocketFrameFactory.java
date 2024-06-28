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
import io.netty.buffer.ByteBufAllocator;

/**
 * Creates frame ByteBufs containing webSocket prefix. It is user's responsibility to call ByteBuf
 * mask(ByteBuf) after frame payload is written.
 */
public interface WebSocketFrameFactory {

  ByteBuf createBinaryFrame(ByteBufAllocator allocator, int binaryDataSize);

  default ByteBuf createTextFrame(ByteBufAllocator allocator, int textDataSize) {
    throw new UnsupportedOperationException(
        "WebSocketFrameFactory.createTextFrame() not implemented");
  }

  ByteBuf createCloseFrame(ByteBufAllocator allocator, int statusCode, String reason);

  ByteBuf createPingFrame(ByteBufAllocator allocator, int binaryDataSize);

  ByteBuf createPongFrame(ByteBufAllocator allocator, int binaryDataSize);

  ByteBuf mask(ByteBuf frame);

  Encoder encoder();

  default BulkEncoder bulkEncoder() {
    throw new UnsupportedOperationException("WebSocketFrameFactory.bulkEncoder() not implemented");
  }

  /** Encodes prefix of single binary websocket frame into provided bytebuffer. */
  interface Encoder {

    ByteBuf encodeBinaryFrame(ByteBuf binaryFrame);

    int sizeofBinaryFrame(int payloadSize);

    default ByteBuf encodeTextFrame(ByteBuf textFrame) {
      throw new UnsupportedOperationException(
          "WebSocketFrameFactory.Encoder.encodeTextFrame() not implemented");
    }

    default int sizeofTextFrame(int textPayloadSize) {
      throw new UnsupportedOperationException(
          "WebSocketFrameFactory.Encoder.sizeofTextFrame() not implemented");
    }
  }

  /** Encodes prefixes of multiple binary websocket frames into provided bytebuffer. */
  interface BulkEncoder {

    /** @return frame mask, or -1 if masking not applicable */
    int encodeBinaryFramePrefix(ByteBuf byteBuf, int payloadSize);

    ByteBuf maskBinaryFrame(ByteBuf byteBuf, int mask, int payloadSize);

    int sizeofBinaryFrame(int payloadSize);
  }
}
