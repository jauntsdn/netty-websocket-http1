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
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class WebSocketValidationTest {
  Channel server;

  @AfterEach
  void tearDown() throws Exception {
    Channel s = server;
    if (s != null) {
      s.close();
    }
  }

  @Timeout(15)
  @Test
  void frameSizeLimit() throws Exception {
    FrameSizeLimitServerHandler serverHandler = new FrameSizeLimitServerHandler();
    Channel s = server = testServer("localhost", 0, decoderConfig(125), serverHandler);
    FrameSizeLimitClientHandler clientHandler = new FrameSizeLimitClientHandler(126);
    Channel client = testClient(s.localAddress(), 125, clientHandler);
    serverHandler.onClose.join();
    clientHandler.onClose.join();
    Throwable serverInboundException = serverHandler.inboundException;
    Assertions.assertThat(serverInboundException).isNotNull();
    Assertions.assertThat(serverInboundException)
        .isInstanceOf(CorruptedWebSocketFrameException.class);
    WebSocketCloseStatus closeStatus =
        ((CorruptedWebSocketFrameException) serverInboundException).closeStatus();
    Assertions.assertThat(closeStatus.code())
        .isEqualTo(WebSocketCloseStatus.MESSAGE_TOO_BIG.code());
    Assertions.assertThat(serverHandler.framesReceived).isEqualTo(0);
    Assertions.assertThat(clientHandler.nonCloseFrames).isEqualTo(0);
    Set<ByteBuf> closeFrames = clientHandler.closeFrames;
    try {
      Assertions.assertThat(closeFrames.size()).isEqualTo(1);
      ByteBuf closeFramePayload = closeFrames.iterator().next();
      Assertions.assertThat(WebSocketFrameListener.CloseFramePayload.statusCode(closeFramePayload))
          .isEqualTo(WebSocketCloseStatus.MESSAGE_TOO_BIG.code());
    } finally {
      closeFrames.forEach(ByteBuf::release);
    }
  }

  @Test
  void controlFrameSizeLimit() {}

  @Test
  void frameWithExtensions() {}

  @Test
  void invalidFragmentStart() {}

  @Test
  void invalidFragmentContinuation() {}

  @Test
  void invalidFragmentCompletion() {}

  static WebSocketDecoderConfig decoderConfig(int maxFramePayloadLength) {
    return WebSocketDecoderConfig.newBuilder()
        .allowMaskMismatch(true)
        .expectMaskedFrames(true)
        .maxFramePayloadLength(maxFramePayloadLength)
        .withUTF8Validator(false)
        .build();
  }

  static Channel testClient(
      SocketAddress address, int maxFrameSize, WebSocketCallbacksHandler webSocketHandler)
      throws Exception {
    InetSocketAddress inetSocketAddress = (InetSocketAddress) address;
    String host = inetSocketAddress.getHostName();
    int port = inetSocketAddress.getPort();

    return new Bootstrap()
        .group(new NioEventLoopGroup(1))
        .channel(NioSocketChannel.class)
        .handler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {

                HttpClientCodec http1Codec = new HttpClientCodec();
                HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);

                com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
                    webSocketProtocolHandler =
                        com.jauntsdn.netty.handler.codec.http.websocketx
                            .WebSocketClientProtocolHandler.create()
                            .path("/test")
                            .mask(true)
                            .allowMaskMismatch(true)
                            .maxFramePayloadLength(maxFrameSize)
                            .webSocketHandler(webSocketHandler)
                            .build();

                ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast(http1Codec, http1Aggregator, webSocketProtocolHandler);
              }
            })
        .connect(new InetSocketAddress(host, port))
        .sync()
        .channel();
  }

  static class FrameSizeLimitClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    final int payloadSize;
    final CompletableFuture<Void> onClose = new CompletableFuture<>();
    final Set<ByteBuf> closeFrames = ConcurrentHashMap.newKeySet();
    volatile int nonCloseFrames;
    WebSocketFrameFactory webSocketFrameFactory;

    FrameSizeLimitClientHandler(int payloadSize) {
      this.payloadSize = payloadSize;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      return this;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      WebSocketFrameFactory factory = webSocketFrameFactory;
      ByteBuf binaryFrame = factory.createBinaryFrame(ctx.alloc(), payloadSize);
      byte[] payloadBytes = new byte[payloadSize];
      ThreadLocalRandom.current().nextBytes(payloadBytes);
      binaryFrame.writeBytes(payloadBytes);
      ctx.writeAndFlush(factory.mask(binaryFrame));
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (opcode == WebSocketProtocol.OPCODE_CLOSE) {
        closeFrames.add(payload);
        return;
      }
      //noinspection NonAtomicOperationOnVolatileField: written from single thread
      nonCloseFrames++;
      ReferenceCountUtil.release(payload);
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      onClose.complete(null);
    }
  }

  static Channel testServer(
      String host,
      int port,
      WebSocketDecoderConfig decoderConfig,
      WebSocketCallbacksHandler webSocketCallbacksHandler)
      throws Exception {
    return new ServerBootstrap()
        .group(new NioEventLoopGroup(1))
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {

              @Override
              protected void initChannel(SocketChannel ch) {
                HttpServerCodec http1Codec = new HttpServerCodec();
                HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);
                WebSocketServerProtocolHandler webSocketProtocolHandler =
                    WebSocketServerProtocolHandler.create()
                        .path("/test")
                        .decoderConfig(decoderConfig)
                        .webSocketCallbacksHandler(webSocketCallbacksHandler)
                        .build();

                ChannelPipeline pipeline = ch.pipeline();
                pipeline
                    .addLast(http1Codec)
                    .addLast(http1Aggregator)
                    .addLast(webSocketProtocolHandler);
              }
            })
        .bind(host, port)
        .sync()
        .channel();
  }

  static class FrameSizeLimitServerHandler
      implements WebSocketFrameListener, WebSocketCallbacksHandler {
    final CompletableFuture<Void> onClose = new CompletableFuture<>();

    volatile int framesReceived;
    volatile Throwable inboundException;

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      payload.release();
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      this.inboundException = cause;
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      onClose.complete(null);
    }
  }
}
