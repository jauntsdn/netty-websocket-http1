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

  ByteBuf createCloseFrame(ByteBufAllocator allocator, int statusCode, String reason);

  ByteBuf createPingFrame(ByteBufAllocator allocator, int binaryDataSize);

  ByteBuf createPongFrame(ByteBufAllocator allocator, int binaryDataSize);

  ByteBuf mask(ByteBuf frame);

  Encoder encoder();

  interface Encoder {
    /*write prefix/mask, apply mask if needed*/
    ByteBuf encodeBinaryFrame(ByteBuf binaryFrame);

    /*size with prefix/mask*/
    int sizeofBinaryFrame(int payloadSize);
  }
}
