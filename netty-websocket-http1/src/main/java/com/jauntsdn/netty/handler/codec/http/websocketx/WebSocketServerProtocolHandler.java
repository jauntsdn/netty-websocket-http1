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

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Performs webSocket server-side handshake and adds webSocket encoder/decoder to channel pipeline.
 */
public final class WebSocketServerProtocolHandler extends ChannelInboundHandlerAdapter {
  private final String path;
  private final String subprotocols;
  private final WebSocketDecoderConfig decoderConfig;
  private final WebSocketCallbacksHandler webSocketHandler;
  private ChannelPromise handshakeCompleted;

  public static Builder create() {
    return Builder.create();
  }

  private WebSocketServerProtocolHandler(
      String path,
      String subprotocols,
      WebSocketDecoderConfig webSocketDecoderConfig,
      WebSocketCallbacksHandler webSocketHandler) {
    this.path = path;
    this.subprotocols = subprotocols;
    this.decoderConfig = webSocketDecoderConfig;
    this.webSocketHandler = webSocketHandler;
  }

  @SuppressWarnings("unchecked")
  public <T extends WebSocketCallbacksHandler> T webSocketHandler() {
    return (T) webSocketHandler;
  }

  public ChannelFuture handshakeCompleted() {
    ChannelPromise completed = handshakeCompleted;
    if (completed == null) {
      throw new IllegalStateException(
          "handshakeCompleted() must be called after channelRegistered()");
    }
    return completed;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    handshakeCompleted = ctx.newPromise();
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    ChannelPromise completed = handshakeCompleted;
    if (!completed.isDone()) {
      completed.setFailure(new ClosedChannelException());
    }
    super.channelInactive(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof FullHttpRequest) {
      FullHttpRequest request = (FullHttpRequest) msg;
      if (!isWebSocketRequest(request)) {
        super.channelRead(ctx, msg);
        return;
      }
      try {
        completeHandshake(ctx, request);
      } finally {
        request.release();
      }
      return;
    }
    super.channelRead(ctx, msg);
  }

  private boolean isWebSocketRequest(HttpRequest req) {
    try {
      URI requestUri = new URI(req.uri());
      return path.equals(requestUri.getPath());
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private void completeHandshake(ChannelHandlerContext ctx, HttpRequest request) {
    WebSocketServerHandshaker.Factory handshakerFactory =
        new WebSocketServerHandshaker.Factory(path, subprotocols, decoderConfig);

    WebSocketServerHandshaker handshaker = handshakerFactory.newHandshaker(ctx, request);
    if (handshaker == null) {
      WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
    } else {
      handshaker
          .handshake(ctx.channel(), request)
          .addListener(
              future -> {
                Throwable cause = future.cause();
                if (cause != null) {
                  handshakeCompleted.tryFailure(cause);
                  ctx.fireExceptionCaught(cause);
                } else {
                  WebSocketCallbacksHandler.exchange(ctx, webSocketHandler);
                  handshakeCompleted.trySuccess();
                  ctx.fireUserEventTriggered(
                      io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
                          .ServerHandshakeStateEvent.HANDSHAKE_COMPLETE);
                }
                ctx.pipeline().remove(this);
              });
    }
  }

  public static final class Builder {
    private static final WebSocketDecoderConfig DEFAULT_DECODER_CONFIG =
        WebSocketDecoderConfig.newBuilder()
            .maxFramePayloadLength(65_535)
            .allowExtensions(false)
            .expectMaskedFrames(true)
            .allowMaskMismatch(true)
            .withUTF8Validator(false)
            .build();

    private String path = "/";
    private String subprotocols;
    private WebSocketDecoderConfig decoderConfig = DEFAULT_DECODER_CONFIG;
    private WebSocketCallbacksHandler webSocketCallbacksHandler;

    private Builder() {}

    public static Builder create() {
      return new Builder();
    }

    /**
     * @param path websocket path starting with "/". Must be non-null
     * @return this Builder instance
     */
    public Builder path(String path) {
      this.path = Objects.requireNonNull(path, "path");
      return this;
    }

    /**
     * @param subprotocols accepted subprotocols. May be null
     * @return this Builder instance
     */
    public Builder subprotocols(@Nullable String subprotocols) {
      this.subprotocols = subprotocols;
      return this;
    }

    /**
     * @param decoderConfig webSocket decoder config that corresponds to library declared scope: no
     *     extensions, no utf8 validation, maxFrameLength is 65_635. For default decoder:
     *     allowMaskMismatch = true. For small decoder allowMaskMismatch = false, expectMaskedFrames
     *     = false, maxFramePayloadLength = 125. Must be non-null.
     * @return this Builder instance
     */
    public Builder decoderConfig(WebSocketDecoderConfig decoderConfig) {
      this.decoderConfig =
          WebSocketProtocol.validateDecoderConfig(
              Objects.requireNonNull(decoderConfig, "decoderConfig"));
      return this;
    }

    /**
     * @param webSocketHandler handler to process successfully handshaked webSocket
     * @return this Builder instance
     */
    public Builder webSocketCallbacksHandler(WebSocketCallbacksHandler webSocketHandler) {
      this.webSocketCallbacksHandler = Objects.requireNonNull(webSocketHandler, "webSocketHandler");
      return this;
    }

    /** @return new WebSocketServerProtocolHandler instance */
    public WebSocketServerProtocolHandler build() {
      WebSocketCallbacksHandler handler = webSocketCallbacksHandler;
      if (handler == null) {
        throw new IllegalStateException("webSocketCallbacksHandler was not provided");
      }
      return new WebSocketServerProtocolHandler(path, subprotocols, decoderConfig, handler);
    }
  }
}
