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
import io.netty.util.internal.PlatformDependent;
import java.nio.charset.StandardCharsets;

final class MaskingWebSocketEncoder extends ChannelOutboundHandlerAdapter
    implements WebSocketCallbacksFrameEncoder {

  static final MaskingWebSocketEncoder INSTANCE = new MaskingWebSocketEncoder();

  private MaskingWebSocketEncoder() {}

  @Override
  public boolean isSharable() {
    return true;
  }

  @Override
  public WebSocketFrameFactory frameFactory(ChannelHandlerContext ctx) {
    ctx.pipeline().remove(this);
    return FrameFactory.INSTANCE;
  }

  static class FrameFactory
      implements WebSocketFrameFactory,
          WebSocketFrameFactory.Encoder,
          WebSocketFrameFactory.BulkEncoder {
    static final int PREFIX_SIZE_SMALL = 6;
    static final int BINARY_FRAME_SMALL =
        OPCODE_BINARY << 8 | /*FIN*/ (byte) 1 << 15 | /*MASK*/ (byte) 1 << 7;
    static final int TEXT_FRAME_SMALL =
        OPCODE_TEXT << 8 | /*FIN*/ (byte) 1 << 15 | /*MASK*/ (byte) 1 << 7;

    static final int CLOSE_FRAME =
        OPCODE_CLOSE << 8 | /*FIN*/ (byte) 1 << 15 | /*MASK*/ (byte) 1 << 7;
    static final int PING_FRAME =
        OPCODE_PING << 8 | /*FIN*/ (byte) 1 << 15 | /*MASK*/ (byte) 1 << 7;
    static final int PONG_FRAME =
        OPCODE_PONG << 8 | /*FIN*/ (byte) 1 << 15 | /*MASK*/ (byte) 1 << 7;

    static final int PREFIX_SIZE_MEDIUM = 8;
    static final int BINARY_FRAME_MEDIUM = (BINARY_FRAME_SMALL | /*LEN*/ (byte) 126) << 16;
    static final int TEXT_FRAME_MEDIUM = (TEXT_FRAME_SMALL | /*LEN*/ (byte) 126) << 16;

    static final WebSocketFrameFactory INSTANCE = new FrameFactory();

    static ByteBuf createDataFrame(
        ByteBufAllocator allocator, int payloadSize, int prefixSmall, int prefixMedium) {
      if (payloadSize <= 125) {
        return allocator
            .buffer(PREFIX_SIZE_SMALL + payloadSize)
            .writeShort(prefixSmall | payloadSize)
            .readerIndex(2)
            .writeInt(mask());
      } else if (payloadSize <= 65_535) {
        return allocator
            .buffer(PREFIX_SIZE_MEDIUM + payloadSize)
            .writeLong((long) (prefixMedium | payloadSize) << 32 | mask())
            .readerIndex(4);
      } else {
        throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 65_535));
      }
    }

    @Override
    public ByteBuf createBinaryFrame(ByteBufAllocator allocator, int payloadSize) {
      return createDataFrame(allocator, payloadSize, BINARY_FRAME_SMALL, BINARY_FRAME_MEDIUM);
    }

    @Override
    public ByteBuf createTextFrame(ByteBufAllocator allocator, int payloadSize) {
      return createDataFrame(allocator, payloadSize, TEXT_FRAME_SMALL, TEXT_FRAME_MEDIUM);
    }

    @Override
    public ByteBuf createCloseFrame(ByteBufAllocator allocator, int statusCode, String reason) {
      if (!WebSocketCloseStatus.isValidStatusCode(statusCode)) {
        throw new IllegalArgumentException("incorrect close status code: " + statusCode);
      }
      if (reason == null) {
        reason = "";
      }
      int payloadSize = /*status code*/ 2 + ByteBufUtil.utf8Bytes(reason);
      if (payloadSize <= 125) {
        ByteBuf frame =
            allocator
                .buffer(PREFIX_SIZE_SMALL + payloadSize)
                .writeShort(CLOSE_FRAME | payloadSize)
                .readerIndex(2)
                .writeInt(mask())
                .writeShort(statusCode);
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
            .writeShort(PING_FRAME | payloadSize)
            .readerIndex(2)
            .writeInt(mask());
      }
      throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 125));
    }

    @Override
    public ByteBuf createPongFrame(ByteBufAllocator allocator, int payloadSize) {
      if (payloadSize <= 125) {
        return allocator
            .buffer(PREFIX_SIZE_SMALL + payloadSize)
            .writeShort(PONG_FRAME | payloadSize)
            .readerIndex(2)
            .writeInt(mask());
      }
      throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 125));
    }

    @Override
    public ByteBuf mask(ByteBuf frame) {
      int maskIndex = frame.readerIndex();
      int mask = frame.getInt(maskIndex);
      mask(mask, frame, maskIndex + /*mask size*/ 4, frame.writerIndex());
      return frame.readerIndex(0);
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

    static ByteBuf encodeDataFrame(ByteBuf binaryFrame, int prefixSmall, int prefixMedium) {
      int frameSize = binaryFrame.readableBytes();
      int smallPrefixSize = 6;
      if (frameSize <= 125 + smallPrefixSize) {
        int payloadSize = frameSize - smallPrefixSize;
        binaryFrame.setShort(0, prefixSmall | payloadSize);
        int mask = mask();
        binaryFrame.setInt(2, mask);
        return mask(mask, binaryFrame, smallPrefixSize, binaryFrame.writerIndex());
      }

      int mediumPrefixSize = 8;
      if (frameSize <= 65_535 + mediumPrefixSize) {
        int payloadSize = frameSize - mediumPrefixSize;
        int mask = mask();
        binaryFrame.setLong(0, ((prefixMedium | (long) payloadSize) << 32) | mask);
        return mask(mask, binaryFrame, mediumPrefixSize, binaryFrame.writerIndex());
      }
      int payloadSize = frameSize - 12;
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
        int mask = mask();
        byteBuf.writeInt(mask);
        return mask;
      }

      if (payloadSize <= 65_535) {
        int mask = mask();
        byteBuf.writeLong(((prefixMedium | (long) payloadSize) << 32) | mask);
        return mask;
      }
      throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 65_535));
    }

    @Override
    public ByteBuf maskBinaryFrame(ByteBuf byteBuf, int mask, int payloadSize) {
      return maskDataFrame(byteBuf, mask, payloadSize);
    }

    @Override
    public ByteBuf maskTextFrame(ByteBuf byteBuf, int mask, int textPayloadSize) {
      return maskDataFrame(byteBuf, mask, textPayloadSize);
    }

    static ByteBuf maskDataFrame(ByteBuf byteBuf, int mask, int payloadSize) {
      int end = byteBuf.writerIndex();
      int start = end - payloadSize;
      return mask(mask, byteBuf, start, end);
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
        return payloadSize + 6;
      }
      if (payloadSize < 65_535) {
        return payloadSize + 8;
      }
      throw new IllegalArgumentException(payloadSizeLimit(payloadSize, 65_535));
    }

    static ByteBuf mask(int mask, ByteBuf frame, int start, int end) {
      int cur = start;
      if (end - cur >= 8) {
        long longMask = (long) mask & 0xFFFFFFFFL;
        longMask |= longMask << 32;
        for (; cur < end - 7; cur += 8) {
          frame.setLong(cur, frame.getLong(cur) ^ longMask);
        }
      }
      if (end - cur >= 4) {
        frame.setInt(cur, frame.getInt(cur) ^ mask);
        cur += 4;
      }
      int maskOffset = 0;
      for (; cur < end; cur++) {
        byte bytePayload = frame.getByte(cur);
        frame.setByte(cur, bytePayload ^ byteAtIndex(mask, maskOffset++ & 3));
      }
      return frame;
    }

    static int byteAtIndex(int mask, int index) {
      return (mask >> 8 * (3 - index)) & 0xFF;
    }

    static int mask() {
      return PlatformDependent.threadLocalRandom().nextInt(Integer.MAX_VALUE);
    }

    static String payloadSizeLimit(int payloadSize, int limit) {
      return "payloadSize: " + payloadSize + " exceeds supported limit: " + limit;
    }
  }
}
