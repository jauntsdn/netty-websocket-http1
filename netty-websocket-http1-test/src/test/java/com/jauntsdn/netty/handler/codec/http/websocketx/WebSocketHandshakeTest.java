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
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.util.ReferenceCountUtil;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
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

  static Channel testClient(
      SocketAddress address,
      String path,
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
                        .build();

                ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast(http1Codec, http1Aggregator, webSocketProtocolHandler);
              }
            })
        .connect(address)
        .sync()
        .channel();
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
    return new ServerBootstrap()
        .group(new NioEventLoopGroup(1))
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new TestAcceptor(
                path, decoderConfig, webSocketCallbacksHandler, nonHandledMessageConsumer))
        .bind("localhost", 0)
        .sync()
        .channel();
  }

  static class TestAcceptor extends ChannelInitializer<SocketChannel> {
    private final String path;
    private final WebSocketDecoderConfig webSocketDecoderConfig;
    private final WebSocketCallbacksHandler webSocketCallbacksHandler;
    private final Consumer<Object> nonHandledMessageConsumer;

    TestAcceptor(
        String path,
        WebSocketDecoderConfig decoderConfig,
        WebSocketCallbacksHandler webSocketCallbacksHandler,
        Consumer<Object> nonHandledMessageConsumer) {
      this.path = path;
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
