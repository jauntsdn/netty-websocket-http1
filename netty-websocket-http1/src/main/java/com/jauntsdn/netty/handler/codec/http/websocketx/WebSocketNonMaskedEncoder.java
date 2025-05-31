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
import static com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketProtocol.OPCODE_TEXT;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.nio.charset.StandardCharsets;

final class WebSocketNonMaskedEncoder extends ChannelOutboundHandlerAdapter
    implements WebSocketCallbacksFrameEncoder {

  static final WebSocketNonMaskedEncoder INSTANCE = new WebSocketNonMaskedEncoder();

  private WebSocketNonMaskedEncoder() {}

  @Override
  public boolean isSharable() {
    return true;
  }

  @Override
  public WebSocketFrameFactory frameFactory(ChannelHandlerContext ctx) {
    ctx.pipeline().remove(this);
    return FrameFactory.INSTANCE;
  }

  static final class FrameFactory
      implements WebSocketFrameFactory,
          WebSocketFrameFactory.Encoder,
          WebSocketFrameFactory.BulkEncoder {
    static final int PREFIX_SIZE_SMALL = 2;
    static final int BINARY_FRAME_SMALL = OPCODE_BINARY << 8 | /*FIN*/ (byte) 1 << 15;
    static final int TEXT_FRAME_SMALL = OPCODE_TEXT << 8 | /*FIN*/ (byte) 1 << 15;

    static final int BINARY_FRAGMENT_START_SMALL = OPCODE_BINARY << 8;
    static final int TEXT_FRAGMENT_START_SMALL = OPCODE_TEXT << 8;
    static final int DATA_FRAGMENT_CONTINUATION_SMALL = 0;
    static final int DATA_FRAGMENT_CONTINUATION_END_SMALL = /*FIN*/ (byte) 1 << 15;

    static final int CLOSE_FRAME = OPCODE_CLOSE << 8 | /*FIN*/ (byte) 1 << 15;
    static final int PING_FRAME = OPCODE_PING << 8 | /*FIN*/ (byte) 1 << 15;
    static final int PONG_FRAME = OPCODE_PONG << 8 | /*FIN*/ (byte) 1 << 15;

    static final int PREFIX_SIZE_MEDIUM = 4;
    static final int BINARY_FRAME_MEDIUM = (BINARY_FRAME_SMALL | /*LEN*/ (byte) 126) << 16;
    static final int TEXT_FRAME_MEDIUM = (TEXT_FRAME_SMALL | /*LEN*/ (byte) 126) << 16;

    static final int BINARY_FRAGMENT_START_MEDIUM =
        (BINARY_FRAGMENT_START_SMALL | /*LEN*/ (byte) 126) << 16;
    static final int TEXT_FRAGMENT_START_MEDIUM =
        (TEXT_FRAGMENT_START_SMALL | /*LEN*/ (byte) 126) << 16;
    static final int DATA_FRAGMENT_CONTINUATION_MEDIUM =
        (DATA_FRAGMENT_CONTINUATION_SMALL | /*LEN*/ (byte) 126) << 16;
    static final int DATA_FRAGMENT_CONTINUATION_END_MEDIUM =
        (DATA_FRAGMENT_CONTINUATION_END_SMALL | /*LEN*/ (byte) 126) << 16;

    static final WebSocketFrameFactory INSTANCE = new FrameFactory();

    static ByteBuf createDataFrame(
        ByteBufAllocator allocator, int payloadSize, int prefixSmall, int prefixMedium) {
      if (payloadSize <= 125) {
        return allocator
            .buffer(PREFIX_SIZE_SMALL + payloadSize)
            .writeShort(prefixSmall | payloadSize);
      }

      if (payloadSize <= 65_535) {
        return allocator
            .buffer(PREFIX_SIZE_MEDIUM + payloadSize)
            .writeInt(prefixMedium | payloadSize);
      }
      throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 65_535));
    }

    @Override
    public ByteBuf createBinaryFrame(ByteBufAllocator allocator, int payloadSize) {
      return createDataFrame(allocator, payloadSize, BINARY_FRAME_SMALL, BINARY_FRAME_MEDIUM);
    }

    @Override
    public ByteBuf createTextFrame(ByteBufAllocator allocator, int textDataSize) {
      return createDataFrame(allocator, textDataSize, TEXT_FRAME_SMALL, TEXT_FRAME_MEDIUM);
    }

    @Override
    public ByteBuf createBinaryFragmentStart(ByteBufAllocator allocator, int binaryDataSize) {
      return createDataFrame(
          allocator, binaryDataSize, BINARY_FRAGMENT_START_SMALL, BINARY_FRAGMENT_START_MEDIUM);
    }

    @Override
    public ByteBuf createTextFragmentStart(ByteBufAllocator allocator, int textDataSize) {
      return createDataFrame(
          allocator, textDataSize, TEXT_FRAGMENT_START_SMALL, TEXT_FRAGMENT_START_MEDIUM);
    }

    @Override
    public ByteBuf createContinuationFragment(ByteBufAllocator allocator, int dataSize) {
      return createDataFrame(
          allocator, dataSize, DATA_FRAGMENT_CONTINUATION_SMALL, DATA_FRAGMENT_CONTINUATION_MEDIUM);
    }

    @Override
    public ByteBuf createContinuationFragmentEnd(ByteBufAllocator allocator, int dataSize) {
      return createDataFrame(
          allocator,
          dataSize,
          DATA_FRAGMENT_CONTINUATION_END_SMALL,
          DATA_FRAGMENT_CONTINUATION_END_MEDIUM);
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
    public BulkEncoder bulkEncoder() {
      return this;
    }

    @Override
    public ByteBuf encodeBinaryFrame(ByteBuf binaryFrame) {
      return encodeDataFrame(binaryFrame, BINARY_FRAME_SMALL, BINARY_FRAME_MEDIUM);
    }

    @Override
    public ByteBuf encodeTextFrame(ByteBuf textFrame) {
      return encodeDataFrame(textFrame, TEXT_FRAME_SMALL, TEXT_FRAME_MEDIUM);
    }

    @Override
    public ByteBuf encodeBinaryFragmentStart(ByteBuf fragmentFrame) {
      return encodeDataFrame(
          fragmentFrame, BINARY_FRAGMENT_START_SMALL, BINARY_FRAGMENT_START_MEDIUM);
    }

    @Override
    public ByteBuf encodeTextFragmentStart(ByteBuf fragmentFrame) {
      return encodeDataFrame(fragmentFrame, TEXT_FRAGMENT_START_SMALL, TEXT_FRAGMENT_START_MEDIUM);
    }

    @Override
    public ByteBuf encodeContinuationFragment(ByteBuf fragmentFrame) {
      return encodeDataFrame(
          fragmentFrame, DATA_FRAGMENT_CONTINUATION_SMALL, DATA_FRAGMENT_CONTINUATION_MEDIUM);
    }

    @Override
    public ByteBuf encodeContinuationFragmentEnd(ByteBuf fragmentFrame) {
      return encodeDataFrame(
          fragmentFrame,
          DATA_FRAGMENT_CONTINUATION_END_SMALL,
          DATA_FRAGMENT_CONTINUATION_END_MEDIUM);
    }

    @Override
    public int sizeofFragment(int payloadSize) {
      return sizeOfDataFrame(payloadSize);
    }

    static ByteBuf encodeDataFrame(ByteBuf binaryFrame, int prefixSmall, int prefixMedium) {
      int frameSize = binaryFrame.readableBytes();
      int smallPrefixSize = 2;
      if (frameSize <= 125 + smallPrefixSize) {
        int payloadSize = frameSize - smallPrefixSize;
        return binaryFrame.setShort(0, prefixSmall | payloadSize);
      }

      int mediumPrefixSize = 4;
      if (frameSize <= 65_535 + mediumPrefixSize) {
        int payloadSize = frameSize - mediumPrefixSize;
        return binaryFrame.setInt(0, prefixMedium | payloadSize);
      }
      int payloadSize = frameSize - 8;
      throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 65_535));
    }

    @Override
    public int encodeBinaryFramePrefix(ByteBuf byteBuf, int payloadSize) {
      return encodeDataFramePrefix(byteBuf, payloadSize, BINARY_FRAME_SMALL, BINARY_FRAME_MEDIUM);
    }

    @Override
    public int encodeTextFramePrefix(ByteBuf byteBuf, int textPayloadSize) {
      return encodeDataFramePrefix(byteBuf, textPayloadSize, TEXT_FRAME_SMALL, TEXT_FRAME_MEDIUM);
    }

    static int encodeDataFramePrefix(
        ByteBuf byteBuf, int payloadSize, int prefixSmall, int prefixMedium) {
      if (payloadSize <= 125) {
        byteBuf.writeShort(prefixSmall | payloadSize);
      } else if (payloadSize <= 65_535) {
        byteBuf.writeInt(prefixMedium | payloadSize);
      } else {
        throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 65_535));
      }
      return -1;
    }

    @Override
    public ByteBuf maskBinaryFrame(ByteBuf byteBuf, int mask, int payloadSize) {
      return byteBuf;
    }

    @Override
    public ByteBuf maskTextFrame(ByteBuf byteBuf, int mask, int textPayloadSize) {
      return byteBuf;
    }

    @Override
    public int sizeofBinaryFrame(int payloadSize) {
      return sizeOfDataFrame(payloadSize);
    }

    @Override
    public int sizeofTextFrame(int textPayloadSize) {
      return sizeOfDataFrame(textPayloadSize);
    }

    static int sizeOfDataFrame(int payloadSize) {
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
