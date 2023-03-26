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

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.ServerHandshakeStateEvent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class WebSocketHandshakeTest {

  Channel server;

  @AfterEach
  void tearDown() {
    Channel s = server;
    if (s != null) {
      s.close();
    }
  }

  @Timeout(15)
  @Test
  void webSocketExchange() throws Exception {
    WebSocketDecoderConfig decoderConfig = webSocketDecoderConfig(false, false, 125);
    TestWebSocketHandler serverHandler = new TestWebSocketHandler();
    Channel s = server = testServer("/", decoderConfig, serverHandler);

    TestWebSocketHandler clientHandler = new TestWebSocketHandler();
    Channel client = testClient(s.localAddress(), "/", true, false, 125, clientHandler);

    clientHandler.onOpen.join();
    Assertions.assertThat(clientHandler.webSocketFrameFactory).isNotNull();
    Assertions.assertThat(clientHandler.onClose.isDone()).isFalse();

    Assertions.assertThat(serverHandler.onOpen.isDone()).isTrue();
    Assertions.assertThat(serverHandler.webSocketFrameFactory).isNotNull();
    Assertions.assertThat(serverHandler.onClose.isDone()).isFalse();

    client.close();
    client.closeFuture().await();
    clientHandler.onClose.join();
    serverHandler.onClose.join();
  }

  @Timeout(15)
  @Test
  void webSocketExchangeNonWebSocketPath() throws Exception {
    WebSocketDecoderConfig decoderConfig = webSocketDecoderConfig(false, false, 125);
    TestWebSocketHandler serverHandler = new TestWebSocketHandler();
    NonWebSocketRequests nonWebSocketRequests = new NonWebSocketRequests();
    Channel s = server = testServer("/", decoderConfig, serverHandler, nonWebSocketRequests);

    TestWebSocketHandler clientHandler = new TestWebSocketHandler();
    Channel client = testClient(s.localAddress(), "/nonws", true, false, 125, clientHandler);

    Object nonHandledRequest = nonWebSocketRequests.onReceived.join();
    try {
      Assertions.assertThat(nonHandledRequest).isInstanceOf(HttpRequest.class);
      Assertions.assertThat(serverHandler.onOpen.isDone()).isFalse();
      Assertions.assertThat(serverHandler.webSocketFrameFactory).isNull();
    } finally {
      ReferenceCountUtil.release(nonHandledRequest);
    }
    client.close();
    client.closeFuture().await();
  }

  static class NonWebSocketRequests implements Consumer<Object> {
    final CompletableFuture<Object> onReceived = new CompletableFuture<>();

    @Override
    public void accept(Object o) {
      CompletableFuture<Object> received = onReceived;
      if (received.isDone()) {
        String desc = o.toString();
        ReferenceCountUtil.release(o);
        throw new IllegalStateException("received more than one unhandled message: " + desc);
      }
      received.complete(o);
    }
  }

  @Timeout(15)
  @Test
  void defaultDecoderConfig() throws Exception {
    WebSocketDecoderConfig decoderConfig = webSocketDecoderConfig(true, true, 65_535);
    TestWebSocketHandler serverHandler = new TestWebSocketHandler();
    Channel s = server = testServer("/", decoderConfig, serverHandler);

    TestWebSocketHandler clientHandler = new TestWebSocketHandler();
    Channel client = testClient(s.localAddress(), "/", true, true, 65_535, clientHandler);

    clientHandler.onOpen.join();
    Assertions.assertThat(clientHandler.channel.pipeline().get(DefaultWebSocketDecoder.class))
        .isNotNull();
    serverHandler.onOpen.join();
    Assertions.assertThat(serverHandler.channel.pipeline().get(DefaultWebSocketDecoder.class))
        .isNotNull();
    client.close();
  }

  @Timeout(15)
  @Test
  void smallDecoderConfig() throws Exception {
    WebSocketDecoderConfig decoderConfig = webSocketDecoderConfig(false, false, 125);
    TestWebSocketHandler serverHandler = new TestWebSocketHandler();
    Channel s = server = testServer("/", decoderConfig, serverHandler);

    TestWebSocketHandler clientHandler = new TestWebSocketHandler();
    Channel client = testClient(s.localAddress(), "/", false, false, 125, clientHandler);

    clientHandler.onOpen.join();
    Assertions.assertThat(clientHandler.channel.pipeline().get(SmallWebSocketDecoder.class))
        .isNotNull();
    serverHandler.onOpen.join();
    Assertions.assertThat(serverHandler.channel.pipeline().get(SmallWebSocketDecoder.class))
        .isNotNull();
    client.close();
  }

  @Test
  void clientBuilderMissingHandler() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () -> {
          WebSocketClientProtocolHandler clientProtocolHandler =
              WebSocketClientProtocolHandler.create().build();
        });
  }

  @Test
  void serverBuilderMissingHandler() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () -> {
          WebSocketServerProtocolHandler serverProtocolHandler =
              WebSocketServerProtocolHandler.create().build();
        });
  }

  @Timeout(15)
  @Test
  void clientTimeout() throws InterruptedException {
    Channel s =
        server =
            new ServerBootstrap()
                .group(new NioEventLoopGroup(1))
                .channel(NioServerSocketChannel.class)
                .childHandler(
                    new ChannelInitializer<SocketChannel>() {

                      @Override
                      protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(
                            new ChannelInboundHandlerAdapter() {
                              @Override
                              public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                ReferenceCountUtil.safeRelease(msg);
                              }
                            });
                      }
                    })
                .bind("localhost", 0)
                .sync()
                .channel();

    AttributeKey<ChannelFuture> handshakeKey = AttributeKey.newInstance("handshake");

    Channel client =
        new Bootstrap()
            .group(new NioEventLoopGroup(1))
            .channel(NioSocketChannel.class)
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {

                    HttpClientCodec http1Codec = new HttpClientCodec();
                    HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);

                    WebSocketClientProtocolHandler webSocketProtocolHandler =
                        WebSocketClientProtocolHandler.create()
                            .handshakeTimeoutMillis(1)
                            .allowMaskMismatch(true)
                            .webSocketHandler(
                                (ctx, webSocketFrameFactory) -> {
                                  throw new AssertionError("should not be called");
                                })
                            .build();

                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(http1Codec, http1Aggregator, webSocketProtocolHandler);

                    ChannelFuture handshake = webSocketProtocolHandler.handshakeCompleted();
                    ch.attr(handshakeKey).set(handshake);
                  }
                })
            .connect(s.localAddress())
            .sync()
            .channel();

    ChannelFuture handshakeFuture = client.attr(handshakeKey).get();
    handshakeFuture.await();
    Throwable cause = handshakeFuture.cause();
    Assertions.assertThat(cause).isNotNull();
    Assertions.assertThat(cause).isInstanceOf(WebSocketClientHandshakeException.class);
    client.closeFuture().await();
    Assertions.assertThat(client.isOpen()).isFalse();
  }

  @Timeout(15)
  @Test
  void serverNonWebSocketRequest() throws InterruptedException {
    WebSocketDecoderConfig decoderConfig = webSocketDecoderConfig(false, true, 125);
    TestWebSocketHandler serverHandler = new TestWebSocketHandler();
    Channel s = server = testServer("/", decoderConfig, serverHandler);

    AttributeKey<Future<FullHttpResponse>> handshakeKey = AttributeKey.newInstance("response");

    Channel client =
        new Bootstrap()
            .group(new NioEventLoopGroup(1))
            .channel(NioSocketChannel.class)
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {

                    HttpClientCodec http1Codec = new HttpClientCodec();
                    HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);
                    NonWebSocketRequestHandler nonWebSocketRequestHandler =
                        new NonWebSocketRequestHandler();
                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(http1Codec, http1Aggregator, nonWebSocketRequestHandler);

                    Future<FullHttpResponse> handshake = nonWebSocketRequestHandler.response();
                    ch.attr(handshakeKey).set(handshake);
                  }
                })
            .connect(s.localAddress())
            .sync()
            .channel();

    Future<FullHttpResponse> responseFuture = client.attr(handshakeKey).get();
    responseFuture.await();
    FullHttpResponse response = responseFuture.getNow();
    try {
      Assertions.assertThat(response).isNotNull();
      Assertions.assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
    } finally {
      response.release();
    }
    client.closeFuture().await();
    Assertions.assertThat(client.isOpen()).isFalse();
  }

  @Timeout(15)
  @Test
  void serverHandshakeEvents() throws InterruptedException {
    WebSocketDecoderConfig decoderConfig = webSocketDecoderConfig(false, true, 125);
    TestWebSocketHandler serverHandler = new TestWebSocketHandler();
    TestWebSocketHandler clientHandler = new TestWebSocketHandler();
    String subprotocol = "subprotocol";
    String path = "/";
    Channel s = server = testServer(path, subprotocol, decoderConfig, serverHandler, null);
    Channel client =
        testClient(s.localAddress(), path, subprotocol, true, true, 65_535, clientHandler);
    serverHandler.onOpen.join();
    client.close();
    serverHandler.onClose.join();
    List<Object> events = serverHandler.events;
    Assertions.assertThat(events).hasSize(2);
    Assertions.assertThat(events.get(0)).isEqualTo(ServerHandshakeStateEvent.HANDSHAKE_COMPLETE);
    Object event = serverHandler.events.get(1);
    Assertions.assertThat(event).isExactlyInstanceOf(HandshakeComplete.class);
    HandshakeComplete completeEvent = (HandshakeComplete) event;
    Assertions.assertThat(completeEvent.requestUri()).isEqualTo(path);
    Assertions.assertThat(completeEvent.requestHeaders()).isNotNull().isNotEmpty();
    Assertions.assertThat(completeEvent.selectedSubprotocol()).isEqualTo(subprotocol);
  }

  static Channel testClient(
      SocketAddress address,
      String path,
      String subprotocol,
      boolean mask,
      boolean allowMaskMismatch,
      int maxFramePayloadLength,
      WebSocketCallbacksHandler webSocketCallbacksHandler)
      throws InterruptedException {
    return new Bootstrap()
        .group(new NioEventLoopGroup(1))
        .channel(NioSocketChannel.class)
        .handler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {

                HttpClientCodec http1Codec = new HttpClientCodec();
                HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);

                WebSocketClientProtocolHandler webSocketProtocolHandler =
                    WebSocketClientProtocolHandler.create()
                        .path(path)
                        .mask(mask)
                        .allowMaskMismatch(allowMaskMismatch)
                        .maxFramePayloadLength(maxFramePayloadLength)
                        .webSocketHandler(webSocketCallbacksHandler)
                        .subprotocol(subprotocol)
                        .build();

                ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast(http1Codec, http1Aggregator, webSocketProtocolHandler);
              }
            })
        .connect(address)
        .sync()
        .channel();
  }

  static Channel testClient(
      SocketAddress address,
      String path,
      boolean mask,
      boolean allowMaskMismatch,
      int maxFramePayloadLength,
      WebSocketCallbacksHandler webSocketCallbacksHandler)
      throws InterruptedException {
    return testClient(
        address,
        path,
        null,
        mask,
        allowMaskMismatch,
        maxFramePayloadLength,
        webSocketCallbacksHandler);
  }

  static Channel testServer(
      String path,
      WebSocketDecoderConfig decoderConfig,
      WebSocketCallbacksHandler webSocketCallbacksHandler)
      throws InterruptedException {
    return testServer(path, decoderConfig, webSocketCallbacksHandler, null);
  }

  static Channel testServer(
      String path,
      WebSocketDecoderConfig decoderConfig,
      WebSocketCallbacksHandler webSocketCallbacksHandler,
      Consumer<Object> nonHandledMessageConsumer)
      throws InterruptedException {
    return testServer(
        path, null, decoderConfig, webSocketCallbacksHandler, nonHandledMessageConsumer);
  }

  static Channel testServer(
      String path,
      String subprotocol,
      WebSocketDecoderConfig decoderConfig,
      WebSocketCallbacksHandler webSocketCallbacksHandler,
      Consumer<Object> nonHandledMessageConsumer)
      throws InterruptedException {
    return new ServerBootstrap()
        .group(new NioEventLoopGroup(1))
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new TestAcceptor(
                path,
                subprotocol,
                decoderConfig,
                webSocketCallbacksHandler,
                nonHandledMessageConsumer))
        .bind("localhost", 0)
        .sync()
        .channel();
  }

  static class NonWebSocketRequestHandler extends ChannelInboundHandlerAdapter {
    private Promise<FullHttpResponse> responsePromise;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof FullHttpResponse) {
        responsePromise.trySuccess((FullHttpResponse) msg);
        return;
      }
      super.channelRead(ctx, msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      responsePromise.tryFailure(new ClosedChannelException());
      super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      responsePromise.tryFailure(cause);
      super.exceptionCaught(ctx, cause);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      super.handlerAdded(ctx);
      responsePromise = new DefaultPromise<>(ctx.executor());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      super.channelActive(ctx);
      FullHttpRequest request =
          new DefaultFullHttpRequest(
              HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Unpooled.EMPTY_BUFFER);

      ctx.writeAndFlush(request);
    }

    Future<FullHttpResponse> response() {
      return responsePromise;
    }
  }

  static class TestAcceptor extends ChannelInitializer<SocketChannel> {
    private final String path;
    private final String subprotocol;
    private final WebSocketDecoderConfig webSocketDecoderConfig;
    private final WebSocketCallbacksHandler webSocketCallbacksHandler;
    private final Consumer<Object> nonHandledMessageConsumer;

    TestAcceptor(
        String path,
        String subprotocol,
        WebSocketDecoderConfig decoderConfig,
        WebSocketCallbacksHandler webSocketCallbacksHandler,
        Consumer<Object> nonHandledMessageConsumer) {
      this.path = path;
      this.subprotocol = subprotocol;
      this.webSocketDecoderConfig = decoderConfig;
      this.webSocketCallbacksHandler = webSocketCallbacksHandler;
      this.nonHandledMessageConsumer = nonHandledMessageConsumer;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
      HttpServerCodec http1Codec = new HttpServerCodec();
      HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);
      WebSocketServerProtocolHandler webSocketProtocolHandler =
          WebSocketServerProtocolHandler.create()
              .path(path)
              .subprotocols(subprotocol)
              .decoderConfig(webSocketDecoderConfig)
              .webSocketCallbacksHandler(webSocketCallbacksHandler)
              .build();

      ChannelPipeline pipeline = ch.pipeline();
      pipeline.addLast(http1Codec).addLast(http1Aggregator).addLast(webSocketProtocolHandler);
      Consumer<Object> nonHandledConsumer = nonHandledMessageConsumer;
      if (nonHandledConsumer != null) {
        pipeline.addLast(
            new ChannelInboundHandlerAdapter() {
              @Override
              public void channelRead(ChannelHandlerContext ctx, Object msg) {
                nonHandledConsumer.accept(msg);
              }
            });
      }
    }
  }

  static class TestWebSocketHandler implements WebSocketCallbacksHandler {
    final CompletableFuture<Void> onOpen = new CompletableFuture<>();
    final CompletableFuture<Void> onClose = new CompletableFuture<>();
    final List<Object> events = new CopyOnWriteArrayList<>();

    volatile WebSocketFrameFactory webSocketFrameFactory;
    volatile Channel channel;

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      this.channel = ctx.channel();

      return new WebSocketFrameListener() {
        @Override
        public void onChannelRead(
            ChannelHandlerContext ctx,
            boolean finalFragment,
            int rsv,
            int opcode,
            ByteBuf payload) {}

        @Override
        public void onUserEventTriggered(ChannelHandlerContext ctx, Object evt) {
          events.add(evt);
        }

        @Override
        public void onOpen(ChannelHandlerContext ctx) {
          onOpen.complete(null);
        }

        @Override
        public void onClose(ChannelHandlerContext ctx) {
          onClose.complete(null);
        }
      };
    }
  }

  static WebSocketDecoderConfig webSocketDecoderConfig(
      boolean expectMasked, boolean allowMaskMismatch, int maxFramePayloadLength) {
    return WebSocketDecoderConfig.newBuilder()
        .allowMaskMismatch(allowMaskMismatch)
        .expectMaskedFrames(expectMasked)
        .maxFramePayloadLength(maxFramePayloadLength)
        .withUTF8Validator(false)
        .build();
  }
}
