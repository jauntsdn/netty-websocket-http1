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

import static com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketProtocol.OPCODE_BINARY;
import static com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketProtocol.OPCODE_CLOSE;
import static com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketProtocol.OPCODE_PING;
import static com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketProtocol.OPCODE_PONG;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.nio.charset.StandardCharsets;

final class NonMaskingWebSocketEncoder extends ChannelOutboundHandlerAdapter
    implements WebSocketCallbacksFrameEncoder {

  static final NonMaskingWebSocketEncoder INSTANCE = new NonMaskingWebSocketEncoder();

  private NonMaskingWebSocketEncoder() {}

  @Override
  public boolean isSharable() {
    return true;
  }

  @Override
  public WebSocketFrameFactory frameFactory(ChannelHandlerContext ctx) {
    ctx.pipeline().remove(this);
    return FrameFactory.INSTANCE;
  }

  static class FrameFactory implements WebSocketFrameFactory, WebSocketFrameFactory.Encoder {
    static final int PREFIX_SIZE_SMALL = 2;
    static final int BINARY_FRAME_SMALL = OPCODE_BINARY << 8 | /*FIN*/ (byte) 1 << 15;

    static final int CLOSE_FRAME = OPCODE_CLOSE << 8 | /*FIN*/ (byte) 1 << 15;
    static final int PING_FRAME = OPCODE_PING << 8 | /*FIN*/ (byte) 1 << 15;
    static final int PONG_FRAME = OPCODE_PONG << 8 | /*FIN*/ (byte) 1 << 15;

    static final int PREFIX_SIZE_MEDIUM = 4;
    static final int BINARY_FRAME_MEDIUM = (BINARY_FRAME_SMALL | /*LEN*/ (byte) 126) << 16;

    static final WebSocketFrameFactory INSTANCE = new FrameFactory();

    @Override
    public ByteBuf createBinaryFrame(ByteBufAllocator allocator, int payloadSize) {
      if (payloadSize <= 125) {
        return allocator
            .buffer(PREFIX_SIZE_SMALL + payloadSize)
            .writeShort(BINARY_FRAME_SMALL | payloadSize);
      }

      if (payloadSize <= 65_535) {
        return allocator
            .buffer(PREFIX_SIZE_MEDIUM + payloadSize)
            .writeInt(BINARY_FRAME_MEDIUM | payloadSize);
      }
      throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 65_535));
    }

    @Override
    public ByteBuf createCloseFrame(ByteBufAllocator allocator, int statusCode, String reason) {
      if (!WebSocketCloseStatus.isValidStatusCode(statusCode)) {
        throw new IllegalArgumentException("Incorrect close status code: " + statusCode);
      }
      if (reason == null) {
        reason = "";
      }
      int payloadSize = /*status code*/ 2 + ByteBufUtil.utf8Bytes(reason);
      if (payloadSize <= 125) {
        ByteBuf frame = allocator.buffer(PREFIX_SIZE_SMALL + payloadSize);
        frame.writeInt((CLOSE_FRAME | payloadSize) << 16 | statusCode);
        if (!reason.isEmpty()) {
          frame.writeCharSequence(reason, StandardCharsets.UTF_8);
        }
        return frame;
      }
      throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 125));
    }

    @Override
    public ByteBuf createPingFrame(ByteBufAllocator allocator, int payloadSize) {
      if (payloadSize <= 125) {
        return allocator
            .buffer(PREFIX_SIZE_SMALL + payloadSize)
            .writeShort(PING_FRAME | payloadSize);
      }
      throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 125));
    }

    @Override
    public ByteBuf createPongFrame(ByteBufAllocator allocator, int payloadSize) {
      if (payloadSize <= 125) {
        return allocator
            .buffer(PREFIX_SIZE_SMALL + payloadSize)
            .writeShort(PONG_FRAME | payloadSize);
      }
      throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 125));
    }

    @Override
    public ByteBuf mask(ByteBuf frame) {
      return frame;
    }

    @Override
    public Encoder encoder() {
      return this;
    }

    @Override
    public ByteBuf encodeBinaryFrame(ByteBuf binaryFrame) {
      int frameSize = binaryFrame.readableBytes();
      int smallPrefixSize = 2;
      if (frameSize <= 125 + smallPrefixSize) {
        int payloadSize = frameSize - smallPrefixSize;
        return binaryFrame.setShort(0, BINARY_FRAME_SMALL | payloadSize);
      }

      int mediumPrefixSize = 4;
      if (frameSize <= 65_535 + mediumPrefixSize) {
        int payloadSize = frameSize - mediumPrefixSize;
        return binaryFrame.setInt(0, BINARY_FRAME_MEDIUM | payloadSize);
      }
      int payloadSize = frameSize - 8;
      throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 65_535));
    }

    @Override
    public int sizeofBinaryFrame(int payloadSize) {
      if (payloadSize <= 125) {
        return payloadSize + 2;
      }
      if (payloadSize < 65_535) {
        return payloadSize + 4;
      }
      throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 65_535));
    }

    static String payloadSizeLimit(int payloadSize, int limit) {
      return "payloadSize: " + payloadSize + " exceeds supported limit: " + limit;
    }
  }
}
