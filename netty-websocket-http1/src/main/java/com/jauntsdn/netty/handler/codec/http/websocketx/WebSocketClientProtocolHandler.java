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
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import javax.annotation.Nullable;

/**
 * Performs webSocket client-side handshake and adds webSocket encoder/decoder to channel pipeline.
 */
public final class WebSocketClientProtocolHandler extends ChannelInboundHandlerAdapter {
  private final String address;
  private final String path;
  private final String subprotocol;
  private final HttpHeaders headers;
  private final boolean mask;
  private final boolean expectMaskedFrames;
  private final boolean allowMaskMismatch;
  private final boolean nomaskingExtension;
  private final IntSupplier externalMask;
  private final int maxFramePayloadLength;
  private final long handshakeTimeoutMillis;
  private final WebSocketCallbacksHandler webSocketHandler;
  private WebSocketClientHandshaker handshaker;
  private ScheduledFuture<?> handshakeTimeoutFuture;
  private ChannelPromise handshakeCompleted;

  private WebSocketClientProtocolHandler(
      String address,
      String path,
      String subprotocol,
      HttpHeaders headers,
      boolean mask,
      boolean expectMaskedFrames,
      boolean allowMaskMismatch,
      boolean nomaskingExtension,
      IntSupplier externalMask,
      int maxFramePayloadLength,
      long handshakeTimeoutMillis,
      @Nullable WebSocketCallbacksHandler webSocketHandler) {
    this.address = address;
    this.path = path;
    this.subprotocol = subprotocol;
    this.headers = headers;
    this.mask = mask;
    this.expectMaskedFrames = expectMaskedFrames;
    this.allowMaskMismatch = allowMaskMismatch;
    this.nomaskingExtension = nomaskingExtension;
    this.externalMask = externalMask;
    this.maxFramePayloadLength = maxFramePayloadLength;
    this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    this.webSocketHandler = webSocketHandler;
  }

  public static Builder create() {
    return new Builder();
  }

  @SuppressWarnings("unchecked")
  public <T extends WebSocketCallbacksHandler> T webSocketHandler() {
    return (T) webSocketHandler;
  }

  public ChannelFuture handshakeCompleted() {
    ChannelPromise completed = handshakeCompleted;
    if (completed == null) {
      throw new IllegalStateException("handshakeCompleted() must be called after handlerAdded()");
    }
    return completed;
  }

  @Override
  public boolean isSharable() {
    return false;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    handshakeCompleted = ctx.newPromise();
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    super.channelActive(ctx);
    WebSocketClientHandshaker h = handshaker = webSocketHandshaker(uri(ctx, address, path));
    startHandshakeTimeout(ctx, handshakeTimeoutMillis);
    h.handshake(ctx.channel())
        .addListener(
            future -> {
              Throwable cause = future.cause();
              if (cause != null) {
                handshakeCompleted.tryFailure(cause);
                ctx.fireExceptionCaught(cause);
                cancelHandshakeTimeout();
              } else {
                ctx.fireUserEventTriggered(
                    io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
                        .ClientHandshakeStateEvent.HANDSHAKE_ISSUED);
              }
            });
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    cancelHandshakeTimeout();
    ChannelPromise completed = handshakeCompleted;
    if (!completed.isDone()) {
      completed.tryFailure(new ClosedChannelException());
    }
    super.channelInactive(ctx);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    ctx.close();
    super.exceptionCaught(ctx, cause);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof FullHttpResponse) {
      FullHttpResponse response = (FullHttpResponse) msg;
      try {
        completeHandshake(ctx, response);
      } finally {
        response.release();
      }
      return;
    }
    super.channelRead(ctx, msg);
  }

  private WebSocketClientHandshaker webSocketHandshaker(URI uri) {
    return nomaskingExtension && WebSocketClientNomaskingHandshaker.supportsNoMaskingExtension(uri)
        ? new WebSocketClientNomaskingHandshaker(
            uri,
            subprotocol,
            headers,
            maxFramePayloadLength,
            expectMaskedFrames,
            allowMaskMismatch,
            externalMask)
        : new WebSocketClientHandshaker(
            uri,
            subprotocol,
            headers,
            maxFramePayloadLength,
            mask,
            externalMask,
            expectMaskedFrames,
            allowMaskMismatch);
  }

  private void completeHandshake(ChannelHandlerContext ctx, FullHttpResponse response) {
    WebSocketClientHandshaker h = handshaker;
    if (h == null) {
      throw new IllegalStateException(
          "Unexpected http response after http1 websocket handshake completion");
    }
    try {
      h.finishHandshake(ctx.channel(), response);
      handshaker = null;
    } catch (Exception e) {
      handshakeCompleted.tryFailure(e);
      if (!(e instanceof WebSocketHandshakeException)) {
        ctx.fireExceptionCaught(e);
      }
      ctx.close();
      return;
    } finally {
      cancelHandshakeTimeout();
    }
    ctx.pipeline().remove(this);
    WebSocketCallbacksHandler handler = webSocketHandler;
    if (handler != null) {
      WebSocketCallbacksHandler.exchange(ctx, handler);
    }
    handshakeCompleted.trySuccess();
    ctx.fireUserEventTriggered(
        io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
            .ClientHandshakeStateEvent.HANDSHAKE_COMPLETE);
  }

  private void startHandshakeTimeout(ChannelHandlerContext ctx, long timeoutMillis) {
    handshakeTimeoutFuture =
        ctx.executor()
            .schedule(
                () -> {
                  handshakeTimeoutFuture = null;
                  ChannelPromise handshake = handshakeCompleted;
                  if (!handshake.isDone()
                      && handshake.tryFailure(
                          new WebSocketClientHandshakeException(
                              "websocket handshake timeout after "
                                  + handshakeTimeoutMillis
                                  + " millis"))) {
                    ctx.flush();
                    ctx.fireUserEventTriggered(
                        io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
                            .ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT);
                    ctx.close();
                  }
                },
                timeoutMillis,
                TimeUnit.MILLISECONDS);
  }

  private void cancelHandshakeTimeout() {
    ScheduledFuture<?> timeoutFuture = handshakeTimeoutFuture;
    if (timeoutFuture != null) {
      handshakeTimeoutFuture = null;
      timeoutFuture.cancel(true);
    }
  }

  private static URI uri(ChannelHandlerContext ctx, String address, String path) {
    String scheme = ctx.pipeline().get(SslHandler.class) != null ? "wss://" : "ws://";
    String url;
    if (address != null) {
      url = scheme + address;
    } else {
      SocketAddress socketAddress = ctx.channel().remoteAddress();
      if (socketAddress instanceof InetSocketAddress) {
        InetSocketAddress inetSocketAddress = (InetSocketAddress) socketAddress;
        url = scheme + inetSocketAddress.getHostString() + ":" + inetSocketAddress.getPort() + path;
      } else {
        throw new IllegalArgumentException(
            "SocketAddress: " + socketAddress + " is not InetSocketAddress");
      }
    }
    try {
      return new URI(url);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("uri syntax error: " + url, e);
    }
  }

  public static final class Builder {
    private static final boolean EXPECT_MASKED_FRAMES = false;
    private String path = "/";
    private String address;
    private String subprotocol;
    private HttpHeaders headers;
    private boolean mask = true;
    private boolean allowMaskMismatch = true;
    private int maxFramePayloadLength = 65_535;
    private long handshakeTimeoutMillis = 15_000;
    private WebSocketCallbacksHandler webSocketHandler;
    private boolean nomaskingExtension;
    private IntSupplier externalMask;

    private Builder() {}

    public static Builder create() {
      return new Builder();
    }

    /**
     * @param address webSocketUri address. Must be non-null
     * @return this Builder instance
     */
    public Builder address(String address) {
      this.address = Objects.requireNonNull(address, "address");
      return this;
    }

    /**
     * @param path websocket path starting with "/". Must be non-null
     * @return this Builder instance
     */
    public Builder path(String path) {
      this.path = Objects.requireNonNull(path, "path");
      this.address = null;
      return this;
    }

    /**
     * @param subprotocol webSocket subprotocol. May be null
     * @return this Builder instance
     */
    public Builder subprotocol(@Nullable String subprotocol) {
      this.subprotocol = subprotocol;
      return this;
    }

    /**
     * @param headers webSocket handshake request headers
     * @return this Builder instance
     */
    public Builder headers(@Nullable HttpHeaders headers) {
      this.headers = headers;
      return this;
    }

    /**
     * @param mask true if frames payload must be masked, false otherwise
     * @return this Builder instance
     */
    public Builder mask(boolean mask) {
      this.mask = mask;
      return this;
    }

    /**
     * @param externalMask generator for payload masking key. May be null.
     * @return this Builder instance
     */
    public Builder mask(@Nullable IntSupplier externalMask) {
      this.externalMask = externalMask;
      return this;
    }

    /**
     * @param allowMaskMismatch true if inbound frames mask mismatch is allowed, false otherwise.
     * @return this Builder instance
     */
    public Builder allowMaskMismatch(boolean allowMaskMismatch) {
      this.allowMaskMismatch = allowMaskMismatch;
      return this;
    }

    /**
     * @param nomaskingExtension enables "no-masking" extension <a
     *     href="https://datatracker.ietf.org/doc/html/draft-damjanovic-websockets-nomasking-02">draft</a>.
     *     Takes precedence over masking related configuration.
     * @return this Builder instance
     */
    public Builder nomaskingExtension(boolean nomaskingExtension) {
      this.nomaskingExtension = nomaskingExtension;
      return this;
    }

    /**
     * @param maxFramePayloadLength inbound frame payload max length, must be less than or equal
     *     65_535
     * @return this Builder instance
     */
    public Builder maxFramePayloadLength(int maxFramePayloadLength) {
      this.maxFramePayloadLength = requirePositive(maxFramePayloadLength, "maxFramePayloadLength");
      return this;
    }

    /**
     * @param handshakeTimeoutMillis webSocket handshake request timeout
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
    public Builder webSocketHandler(@Nullable WebSocketCallbacksHandler webSocketHandler) {
      this.webSocketHandler = webSocketHandler;
      return this;
    }

    /** @return new WebSocketClientProtocolHandler instance */
    public WebSocketClientProtocolHandler build() {
      int maxPayloadLength = maxFramePayloadLength;
      boolean maskMismatch = allowMaskMismatch;

      WebSocketProtocol.validateDecoderConfig(
          maxPayloadLength, false, false, EXPECT_MASKED_FRAMES, maskMismatch);

      return new WebSocketClientProtocolHandler(
          address,
          path,
          subprotocol,
          headers,
          mask,
          EXPECT_MASKED_FRAMES,
          maskMismatch,
          nomaskingExtension,
          externalMask,
          maxPayloadLength,
          handshakeTimeoutMillis,
          webSocketHandler);
    }

    private static int requirePositive(int val, String desc) {
      if (val <= 0) {
        throw new IllegalArgumentException(desc + " must be positive, provided: " + val);
      }
      return val;
    }

    private static long requirePositive(long val, String desc) {
      if (val <= 0) {
        throw new IllegalArgumentException(desc + " must be positive, provided: " + val);
      }
      return val;
    }
  }
}
