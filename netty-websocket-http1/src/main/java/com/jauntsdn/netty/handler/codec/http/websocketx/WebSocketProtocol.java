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
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameEncoder;
import java.util.function.IntSupplier;

public final class WebSocketProtocol {
  public static final byte OPCODE_CONT = 0x0;
  public static final byte OPCODE_TEXT = 0x1;
  public static final byte OPCODE_BINARY = 0x2;
  public static final byte OPCODE_CLOSE = 0x8;
  public static final byte OPCODE_PING = 0x9;
  public static final byte OPCODE_PONG = 0xA;

  /*validation*/
  static final int VALIDATION_RESULT_INVALID = Byte.MIN_VALUE;
  static final int VALIDATION_RESULT_NON_FRAGMENTING = -1;

  static int validate(
      ChannelHandlerContext ctx,
      WebSocketDecoder webSocketDecoder,
      int flags,
      int code,
      int frameLength,
      int fragmentedTotalLength,
      int maxFramePayloadLength) {
    switch (flags) {
        /*final frame w/o extensions*/
      case 0b1000_0000:
        {
          /*fragmenting state*/
          if (fragmentedTotalLength >= 0) {
            if (code > 0 && code < 8) {
              close(
                  ctx,
                  webSocketDecoder,
                  WebSocketCloseStatus.PROTOCOL_ERROR,
                  "non-fragmented frame in fragmented sequence");
              return VALIDATION_RESULT_INVALID;

              /*end fragmenting state*/
            } else if (code == 0) {
              int newFragmentedTotalLength = fragmentedTotalLength + frameLength;
              if (newFragmentedTotalLength > maxFramePayloadLength) {
                close(
                    ctx,
                    webSocketDecoder,
                    WebSocketCloseStatus.MESSAGE_TOO_BIG,
                    "fragmented frame is too big: "
                        + newFragmentedTotalLength
                        + " exceeds limit: "
                        + maxFramePayloadLength);
                return VALIDATION_RESULT_INVALID;
              }
              /*fragmentedTotalLength*/
              return VALIDATION_RESULT_NON_FRAGMENTING;

              /*control frames: validate length*/
            } else {
              if (frameLength > 125) {
                close(
                    ctx,
                    webSocketDecoder,
                    WebSocketCloseStatus.MESSAGE_TOO_BIG,
                    "control frame "
                        + code
                        + " is too big: "
                        + frameLength
                        + " exceeds limit: 125");
                return VALIDATION_RESULT_INVALID;
              }
            }
            /*non-fragmenting state: validate length*/
          } else {
            if (code == 0) {
              close(
                  ctx,
                  webSocketDecoder,
                  WebSocketCloseStatus.PROTOCOL_ERROR,
                  "fragmented frame " + code + " in non-fragmented sequence");
              return VALIDATION_RESULT_INVALID;
            } else if (code >= 8) {
              if (frameLength > 125) {
                close(
                    ctx,
                    webSocketDecoder,
                    WebSocketCloseStatus.MESSAGE_TOO_BIG,
                    "control frame "
                        + code
                        + " is too big: "
                        + frameLength
                        + " exceeds limit: 125");
                return VALIDATION_RESULT_INVALID;
              }
            } else if (frameLength > maxFramePayloadLength) {
              close(
                  ctx,
                  webSocketDecoder,
                  WebSocketCloseStatus.MESSAGE_TOO_BIG,
                  "non-fragmented frame is too big: "
                      + frameLength
                      + " exceeds limit: "
                      + maxFramePayloadLength);
              return VALIDATION_RESULT_INVALID;
            }
          }
        }
        break;

        /*fragment frame w/o extensions*/
      case 0b0000_0000:
        {
          if (code >= 8) {
            close(
                ctx,
                webSocketDecoder,
                WebSocketCloseStatus.PROTOCOL_ERROR,
                "fragmented control frame: " + code);
            return VALIDATION_RESULT_INVALID;
          }
          /*fragmentation start*/
          if (code > 0) {
            if (fragmentedTotalLength >= 0) {
              close(
                  ctx,
                  webSocketDecoder,
                  WebSocketCloseStatus.PROTOCOL_ERROR,
                  "fragmentation start while fragmenting already: " + code);
              return VALIDATION_RESULT_INVALID;
            }
            if (frameLength > maxFramePayloadLength) {
              close(
                  ctx,
                  webSocketDecoder,
                  WebSocketCloseStatus.MESSAGE_TOO_BIG,
                  "non-fragmented frame is too big: "
                      + frameLength
                      + " exceeds limit: "
                      + maxFramePayloadLength);
              return VALIDATION_RESULT_INVALID;
            }
            return frameLength;
            /*fragmentation continuation*/
          } else {
            if (fragmentedTotalLength < 0) {
              close(
                  ctx,
                  webSocketDecoder,
                  WebSocketCloseStatus.PROTOCOL_ERROR,
                  "fragmentation continuation while not in fragmenting state: " + code);
              return VALIDATION_RESULT_INVALID;
            }
            int newFragmentedLength = fragmentedTotalLength + frameLength;
            if (newFragmentedLength > maxFramePayloadLength) {
              close(
                  ctx,
                  webSocketDecoder,
                  WebSocketCloseStatus.MESSAGE_TOO_BIG,
                  "fragmented frame is too big: "
                      + frameLength
                      + " exceeds limit: "
                      + maxFramePayloadLength);
              return VALIDATION_RESULT_INVALID;
            }
            return newFragmentedLength;
          }
        }
      default:
        close(
            ctx,
            webSocketDecoder,
            WebSocketCloseStatus.PROTOCOL_ERROR,
            "extensions are not supported: " + Integer.toBinaryString(flags));
        return VALIDATION_RESULT_INVALID;
    }
    /*non-fragmentable frame so state have not changed*/
    return fragmentedTotalLength;
  }

  static void close(
      ChannelHandlerContext ctx,
      WebSocketDecoder webSocketDecoder,
      WebSocketCloseStatus status,
      String msg) {
    WebSocketFrameFactory frameFactory = webSocketDecoder.frameFactory();
    WebSocketFrameListener frameListener = webSocketDecoder.webSocketFrameListener;

    ByteBuf closeFrame =
        frameFactory.mask(frameFactory.createCloseFrame(ctx.alloc(), status.code(), msg));
    ctx.writeAndFlush(closeFrame).addListener(ChannelFutureListener.CLOSE);
    webSocketDecoder.closeInbound();
    frameListener.onExceptionCaught(ctx, new CorruptedWebSocketFrameException(status, msg));
  }

  public static void validateDecoderConfig(
      int maxFramePayloadLength,
      boolean allowExtensions,
      boolean withUtf8Validator,
      boolean expectMaskedFrames,
      boolean allowMaskMismatch) {

    if (maxFramePayloadLength < 125 || maxFramePayloadLength > 65_535) {
      throw new IllegalArgumentException("maxFramePayloadLength must be in range [125; 65535]");
    }
    if (allowExtensions) {
      throw new IllegalArgumentException("extensions are not supported");
    }
    if (withUtf8Validator) {
      throw new IllegalArgumentException("text frames UTF8 validation is not supported");
    }
  }

  public static WebSocketDecoderConfig validateDecoderConfig(
      WebSocketDecoderConfig webSocketDecoderConfig) {
    validateDecoderConfig(
        webSocketDecoderConfig.maxFramePayloadLength(),
        webSocketDecoderConfig.allowExtensions(),
        webSocketDecoderConfig.withUTF8Validator(),
        webSocketDecoderConfig.expectMaskedFrames(),
        webSocketDecoderConfig.allowMaskMismatch());
    return webSocketDecoderConfig;
  }

  private WebSocketProtocol() {}

  /*for use with external websocket handlers, e.g. websocket-http2*/

  public static WebSocketFrameDecoder frameDecoder(
      int maxFramePayloadLength, boolean expectMaskedFrames, boolean allowMaskMismatch) {
    return WebSocketCallbacksFrameDecoder.frameDecoder(
        maxFramePayloadLength, expectMaskedFrames, allowMaskMismatch);
  }

  /** @deprecated use {@link #frameDecoder(int, boolean, boolean)} instead */
  @Deprecated
  public static WebSocketFrameDecoder frameDecoder(
      boolean maskPayload,
      int maxFramePayloadLength,
      boolean expectMaskedFrames,
      boolean allowMaskMismatch) {
    return WebSocketCallbacksFrameDecoder.frameDecoder(
        maxFramePayloadLength, expectMaskedFrames, allowMaskMismatch);
  }

  public static WebSocketFrameEncoder frameEncoder(
      boolean expectMaskedFrames, IntSupplier externalMask) {
    return WebSocketCallbacksFrameEncoder.frameEncoder(expectMaskedFrames, externalMask);
  }

  public static WebSocketFrameEncoder frameEncoder(boolean expectMaskedFrames) {
    return WebSocketCallbacksFrameEncoder.frameEncoder(expectMaskedFrames, null);
  }
}
