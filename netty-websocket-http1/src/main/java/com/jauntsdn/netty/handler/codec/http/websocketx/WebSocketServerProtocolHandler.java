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

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Performs webSocket server-side handshake and adds webSocket encoder/decoder to channel pipeline.
 */
public final class WebSocketServerProtocolHandler extends ChannelInboundHandlerAdapter {
  private final String path;
  private final String subprotocols;
  private final WebSocketDecoderConfig decoderConfig;
  private final long handshakeTimeoutMillis;
  private final WebSocketCallbacksHandler webSocketHandler;
  private ChannelPromise handshakeCompleted;

  public static Builder create() {
    return Builder.create();
  }

  private WebSocketServerProtocolHandler(
      String path,
      String subprotocols,
      WebSocketDecoderConfig webSocketDecoderConfig,
      long handshakeTimeoutMillis,
      WebSocketCallbacksHandler webSocketHandler) {
    this.path = path;
    this.subprotocols = subprotocols;
    this.decoderConfig = webSocketDecoderConfig;
    this.handshakeTimeoutMillis = handshakeTimeoutMillis;
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
      completed.tryFailure(new ClosedChannelException());
    }
    super.channelInactive(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof FullHttpRequest) {
      FullHttpRequest request = (FullHttpRequest) msg;
      if (!isWebSocketPath(request)) {
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

  private boolean isWebSocketPath(HttpRequest req) {
    try {
      URI requestUri = new URI(req.uri());
      return path.equals(requestUri.getPath());
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private void completeHandshake(ChannelHandlerContext ctx, HttpRequest request) {
    if (!GET.equals(request.method())) {
      ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST, Unpooled.EMPTY_BUFFER))
          .addListener(ChannelFutureListener.CLOSE);
      return;
    }

    WebSocketServerHandshaker.Factory handshakerFactory =
        new WebSocketServerHandshaker.Factory(path, subprotocols, decoderConfig);

    WebSocketServerHandshaker handshaker = handshakerFactory.newHandshaker(ctx, request);
    if (handshaker == null) {
      WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
    } else {
      ChannelPromise handshake = handshakeCompleted;
      String uri = request.uri();
      HttpHeaders headers = request.headers();

      ChannelFuture handshakeFuture;
      /*netty's websocket handshaker throws exceptions instead of notifying handshake future*/
      try {
        handshakeFuture = handshaker.handshake(ctx.channel(), request);
      } catch (Exception e) {
        handleHandshakeResult(ctx, handshake, uri, headers, handshaker.selectedSubprotocol(), e);
        return;
      }
      ScheduledFuture<?> timeout = startHandshakeTimeout(ctx, handshakeTimeoutMillis, handshake);
      handshakeFuture.addListener(
          future -> {
            if (timeout != null) {
              timeout.cancel(true);
            }
            handleHandshakeResult(
                ctx, handshake, uri, headers, handshaker.selectedSubprotocol(), future.cause());
          });
    }
  }

  private void handleHandshakeResult(
      ChannelHandlerContext ctx,
      ChannelPromise handshake,
      String uri,
      HttpHeaders headers,
      String subprotocol,
      Throwable cause) {
    if (cause != null) {
      handshake.tryFailure(cause);
      if (cause instanceof WebSocketHandshakeException) {
        FullHttpResponse response =
            new DefaultFullHttpResponse(
                HTTP_1_1,
                HttpResponseStatus.BAD_REQUEST,
                Unpooled.wrappedBuffer(cause.getMessage().getBytes()));
        ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
      } else {
        ctx.fireExceptionCaught(cause);
        ctx.close();
      }
    } else {
      WebSocketCallbacksHandler.exchange(ctx, webSocketHandler);
      handshake.trySuccess();
      ChannelPipeline p = ctx.channel().pipeline();
      p.fireUserEventTriggered(
          io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
              .ServerHandshakeStateEvent.HANDSHAKE_COMPLETE);
      p.fireUserEventTriggered(
          new io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
              .HandshakeComplete(uri, headers, subprotocol));
    }
    ctx.pipeline().remove(this);
  }

  static ScheduledFuture<?> startHandshakeTimeout(
      ChannelHandlerContext ctx, long handshakeTimeoutMillis, ChannelPromise handshake) {
    if (handshakeTimeoutMillis > 0) {
      return ctx.executor()
          .schedule(
              () -> {
                if (!handshake.isDone()
                    && handshake.tryFailure(
                        new WebSocketServerHandshakeException(
                            "websocket handshake timeout after "
                                + handshakeTimeoutMillis
                                + " millis"))) {
                  ctx.flush();
                  ctx.fireUserEventTriggered(
                      io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
                          .ServerHandshakeStateEvent.HANDSHAKE_TIMEOUT);
                  ctx.close();
                }
              },
              handshakeTimeoutMillis,
              TimeUnit.MILLISECONDS);
    }
    return null;
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
    private long handshakeTimeoutMillis;

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
     * @param handshakeTimeoutMillis webSocket handshake timeout
     * @return this Builder instance
     */
    public Builder handshakeTimeoutMillis(long handshakeTimeoutMillis) {
      this.handshakeTimeoutMillis =
          requirePositive(handshakeTimeoutMillis, "handshakeTimeoutMillis");
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
      return new WebSocketServerProtocolHandler(
          path, subprotocols, decoderConfig, handshakeTimeoutMillis, handler);
    }

    private static long requirePositive(long val, String desc) {
      if (val <= 0) {
        throw new IllegalArgumentException(desc + " must be positive, provided: " + val);
      }
      return val;
    }
  }
}
