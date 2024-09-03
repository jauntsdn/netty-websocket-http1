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

import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
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
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.ReferenceCountUtil;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WebSocketCodecTest {
  static final Logger logger = LoggerFactory.getLogger(WebSocketCodecTest.class);
  static final int SMALL_CODEC_MAX_FRAME_SIZE = 125;
  static final int DEFAULT_CODEC_MAX_FRAME_SIZE =
      Integer.parseInt(System.getProperty("MAX_FRAME_SIZE", "5000"));

  Channel server;

  @AfterEach
  void tearDown() {
    Channel s = server;
    if (s != null) {
      s.close();
    }
  }

  @Timeout(300)
  @ValueSource(booleans = {true, false})
  @ParameterizedTest
  void binaryFramesEncoder(boolean mask) throws Exception {
    int maxFrameSize = DEFAULT_CODEC_MAX_FRAME_SIZE;
    Channel s = server = nettyServer(new WebSocketFramesTestServerHandler(), mask, false);
    BinaryFramesEncoderClientHandler clientHandler =
        new BinaryFramesEncoderClientHandler(maxFrameSize);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    WebSocketFrameFactory.Encoder encoder = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(encoder).isNotNull();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(300)
  @ValueSource(booleans = {true, false})
  @ParameterizedTest
  void textFramesEncoder(boolean mask) throws Exception {
    int maxFrameSize = DEFAULT_CODEC_MAX_FRAME_SIZE;
    Channel s = server = nettyServer(new WebSocketFramesTestServerHandler(), mask, false);
    TextFramesEncoderClientHandler clientHandler =
        new TextFramesEncoderClientHandler(maxFrameSize, 'a');
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    WebSocketFrameFactory.Encoder encoder = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(encoder).isNotNull();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(300)
  @ValueSource(booleans = {true, false})
  @ParameterizedTest
  void binaryFramesBulkEncoder(boolean mask) throws Exception {
    int maxFrameSize = 1000;
    Channel s = server = nettyServer(new WebSocketFramesTestServerHandler(), mask, false);
    BinaryFramesEncoderClientBulkHandler clientHandler =
        new BinaryFramesEncoderClientBulkHandler(maxFrameSize);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    WebSocketFrameFactory.BulkEncoder encoder = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(encoder).isNotNull();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(300)
  @ValueSource(booleans = {true, false})
  @ParameterizedTest
  void textFramesBulkEncoder(boolean mask) throws Exception {
    int maxFrameSize = 1000;
    Channel s = server = nettyServer(new WebSocketFramesTestServerHandler(), mask, false);
    TextFramesEncoderClientBulkHandler clientHandler =
        new TextFramesEncoderClientBulkHandler(maxFrameSize, 'a');
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    WebSocketFrameFactory.BulkEncoder encoder = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(encoder).isNotNull();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(300)
  @ValueSource(booleans = {true, false})
  @ParameterizedTest
  void binaryFramesFragmentsEncoder(boolean mask) throws Exception {
    int maxFrameSize = 3_000;
    Channel s = server = nettyServer(new WebSocketFramesTestServerHandler(), mask, false);
    OutboundBinaryFragmentationEncoderClientHandler clientHandler =
        new OutboundBinaryFragmentationEncoderClientHandler(maxFrameSize / 3 - 1);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    clientHandler.onHandshakeCompleted().join();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(300)
  @ValueSource(booleans = {true, false})
  @ParameterizedTest
  void binaryFramesFragmentsFactory(boolean mask) throws Exception {
    int maxFrameSize = 3_000;
    Channel s = server = nettyServer(new WebSocketFramesTestServerHandler(), mask, false);
    OutboundBinaryFragmentationClientHandler clientHandler =
        new OutboundBinaryFragmentationClientHandler(maxFrameSize / 3 - 1);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    clientHandler.onHandshakeCompleted().join();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(300)
  @ValueSource(booleans = {true, false})
  @ParameterizedTest
  void textFramesFragmentsEncoder(boolean mask) throws Exception {
    int maxFrameSize = 3_000;
    Channel s = server = nettyServer(new WebSocketFramesTestServerHandler(), mask, false);
    OutboundTextFragmentationEncoderClientHandler clientHandler =
        new OutboundTextFragmentationEncoderClientHandler(maxFrameSize / 3 - 1, 'a');
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    clientHandler.onHandshakeCompleted().join();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(300)
  @ValueSource(booleans = {true, false})
  @ParameterizedTest
  void textFramesFragmentsFactory(boolean mask) throws Exception {
    int maxFrameSize = 3_000;
    Channel s = server = nettyServer(new WebSocketFramesTestServerHandler(), mask, false);
    OutboundTextFragmentationClientHandler clientHandler =
        new OutboundTextFragmentationClientHandler(maxFrameSize / 3 - 1, 'a');
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    clientHandler.onHandshakeCompleted().join();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(300)
  @ValueSource(booleans = {true, false})
  @ParameterizedTest
  void textFramesFactory(boolean mask) throws Exception {
    int maxFrameSize = DEFAULT_CODEC_MAX_FRAME_SIZE;
    Channel s = server = nettyServer(new WebSocketFramesTestServerHandler(), mask, false);
    TextFramesFactoryClientHandler clientHandler =
        new TextFramesFactoryClientHandler(maxFrameSize, 'a');
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    WebSocketFrameFactory frameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(frameFactory).isNotNull();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(300)
  @MethodSource("maskingArgs")
  @ParameterizedTest
  void allSizeBinaryFramesDefaultDecoder(
      boolean mask, Class<?> webSocketFrameFactoryType, Class<ChannelHandler> webSocketDecoderType)
      throws Exception {
    int maxFrameSize = DEFAULT_CODEC_MAX_FRAME_SIZE;
    Channel s = server = nettyServer(new WebSocketFramesTestServerHandler(), mask, false);
    BinaryFramesTestClientHandler clientHandler = new BinaryFramesTestClientHandler(maxFrameSize);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    WebSocketFrameFactory webSocketFrameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(webSocketFrameFactory).isExactlyInstanceOf(webSocketFrameFactoryType);
    Assertions.assertThat(client.pipeline().get(webSocketDecoderType)).isNotNull();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(300)
  @MethodSource("maskingArgs")
  @ParameterizedTest
  void allSizeTextFramesDefaultDecoder(
      boolean mask, Class<?> webSocketFrameFactoryType, Class<ChannelHandler> webSocketDecoderType)
      throws Exception {
    int maxFrameSize = DEFAULT_CODEC_MAX_FRAME_SIZE;
    char content = 'a';
    Channel s =
        server = nettyServer(new TextFramesTestServerHandler(maxFrameSize, content), mask, false);
    TextFramesTestClientHandler clientHandler =
        new TextFramesTestClientHandler(maxFrameSize, content);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    WebSocketFrameFactory webSocketFrameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(webSocketFrameFactory).isExactlyInstanceOf(webSocketFrameFactoryType);
    Assertions.assertThat(client.pipeline().get(webSocketDecoderType)).isNotNull();

    clientHandler.onFrameExchangeCompleted().join();
    client.close();
  }

  @Timeout(15)
  @MethodSource("maskingArgs")
  @ParameterizedTest
  void pingFramesDefaultDecoder(
      boolean mask, Class<?> webSocketFrameFactoryType, Class<ChannelHandler> webSocketDecoderType)
      throws Exception {
    int maxFrameSize = SMALL_CODEC_MAX_FRAME_SIZE;
    Channel s = server = nettyServer(new PingPongTestServerHandler(), mask, false);
    PingFramesTestClientHandler clientHandler =
        new PingFramesTestClientHandler(maxFrameSize, (byte) 0xFE);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    WebSocketFrameFactory webSocketFrameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(webSocketFrameFactory).isExactlyInstanceOf(webSocketFrameFactoryType);
    Assertions.assertThat(client.pipeline().get(webSocketDecoderType)).isNotNull();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(15)
  @MethodSource("maskingArgs")
  @ParameterizedTest
  void pongFramesDefaultDecoder(
      boolean mask, Class<?> webSocketFrameFactoryType, Class<ChannelHandler> webSocketDecoderType)
      throws Exception {
    int maxFrameSize = SMALL_CODEC_MAX_FRAME_SIZE;
    Channel s = server = nettyServer(new PingPongTestServerHandler(), mask, false);
    PongFramesTestClientHandler clientHandler =
        new PongFramesTestClientHandler(maxFrameSize, (byte) 0xFE);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    WebSocketFrameFactory webSocketFrameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(webSocketFrameFactory).isExactlyInstanceOf(webSocketFrameFactoryType);
    Assertions.assertThat(client.pipeline().get(webSocketDecoderType)).isNotNull();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(15)
  @ParameterizedTest
  @MethodSource("maskingArgs")
  void closeFramesDefaultDecoder(
      boolean mask, Class<?> webSocketFrameFactoryType, Class<ChannelHandler> webSocketDecoderType)
      throws Exception {
    int maxFrameSize = SMALL_CODEC_MAX_FRAME_SIZE;
    Channel s = server = nettyServer(new CloseTestServerHandler(), mask, false);
    CloseFramesTestClientHandler clientHandler =
        new CloseFramesTestClientHandler(WebSocketCloseStatus.NORMAL_CLOSURE, "NORMAL_CLOSURE");
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    WebSocketFrameFactory webSocketFrameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(webSocketFrameFactory).isExactlyInstanceOf(webSocketFrameFactoryType);
    Assertions.assertThat(client.pipeline().get(webSocketDecoderType)).isNotNull();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(15)
  @ParameterizedTest
  @MethodSource("maskingArgs")
  void fragmentDefaultDecoder(
      boolean mask, Class<?> webSocketFrameFactoryType, Class<ChannelHandler> webSocketDecoderType)
      throws Exception {
    int maxFrameSize = DEFAULT_CODEC_MAX_FRAME_SIZE;

    Channel s = server = nettyServer(new FragmentTestServerHandler(1500), mask, false);
    InboundFragmentationFramesTestClientHandler clientHandler =
        new InboundFragmentationFramesTestClientHandler(3333);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), mask, true, maxFrameSize, clientHandler);

    WebSocketFrameFactory webSocketFrameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(webSocketFrameFactory).isExactlyInstanceOf(webSocketFrameFactoryType);
    Assertions.assertThat(client.pipeline().get(webSocketDecoderType)).isNotNull();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
  }

  @Timeout(15)
  @Test
  void strictMaskedDecoderUnmaskedFrame() throws Exception {
    StrictMaskEncoderServerHandler serverHandler = new StrictMaskEncoderServerHandler();
    Channel s = server = nettyCallbacksServer(serverHandler, true, false);

    StrictMaskEncoderClientHandler clientHandler = new StrictMaskEncoderClientHandler();
    Channel client =
        webSocketCallbacksClient(s.localAddress(), false, false, 10_000, clientHandler);
    clientHandler.onCloseFrameRead().join();
    Assertions.assertThat(clientHandler.framesReadCount()).isEqualTo(1);
    Assertions.assertThat(serverHandler.onFramesRead()).isNotDone();
  }

  @Timeout(15)
  @Test
  void strictMaskedDecoderMaskedFrame() throws Exception {
    StrictMaskEncoderServerHandler serverHandler = new StrictMaskEncoderServerHandler();
    Channel s = server = nettyCallbacksServer(serverHandler, true, false);

    StrictMaskEncoderClientHandler clientHandler = new StrictMaskEncoderClientHandler();
    Channel client = webSocketCallbacksClient(s.localAddress(), true, false, 10_000, clientHandler);
    clientHandler.onBinaryFrameRead().join();
    Assertions.assertThat(clientHandler.framesReadCount()).isEqualTo(1);
  }

  static Stream<Arguments> maskingArgs() {
    return Stream.of(
        arguments(true, MaskingWebSocketEncoder.FrameFactory.class, DefaultWebSocketDecoder.class),
        arguments(
            false, NonMaskingWebSocketEncoder.FrameFactory.class, DefaultWebSocketDecoder.class));
  }

  static Channel nettyServer(
      ChannelHandler webSocketHandler, boolean expectMaskedFrames, boolean allowMaskMismatch)
      throws Exception {
    return new ServerBootstrap()
        .group(new NioEventLoopGroup(1))
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ServerConnectionAcceptor(webSocketHandler, expectMaskedFrames, allowMaskMismatch))
        .bind("localhost", 0)
        .sync()
        .channel();
  }

  static Channel nettyCallbacksServer(
      WebSocketCallbacksHandler webSocketHandler,
      boolean expectMaskedFrames,
      boolean allowMaskMismatch)
      throws Exception {
    return new ServerBootstrap()
        .group(new NioEventLoopGroup(1))
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                try {
                  HttpServerCodec http1Codec = new HttpServerCodec();
                  HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);

                  com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
                      webSocketProtocolHandler =
                          com.jauntsdn.netty.handler.codec.http.websocketx
                              .WebSocketServerProtocolHandler.create()
                              .path("/test")
                              .decoderConfig(
                                  WebSocketDecoderConfig.newBuilder()
                                      .maxFramePayloadLength(10_000)
                                      .withUTF8Validator(false)
                                      .allowExtensions(false)
                                      .allowMaskMismatch(allowMaskMismatch)
                                      .expectMaskedFrames(expectMaskedFrames)
                                      .build())
                              .webSocketCallbacksHandler(webSocketHandler)
                              .build();

                  ChannelPipeline pipeline = ch.pipeline();
                  pipeline.addLast(http1Codec, http1Aggregator, webSocketProtocolHandler);
                } catch (Exception e) {
                  logger.info("a", e);
                }
              }
            })
        .bind("localhost", 0)
        .sync()
        .channel();
  }

  static Channel webSocketCallbacksClient(
      SocketAddress address,
      boolean mask,
      boolean allowMaskMismatch,
      int maxFramePayloadLength,
      WebSocketCallbacksHandler webSocketHandler)
      throws InterruptedException {
    Channel channel =
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
                            .path("/test")
                            .mask(mask)
                            .allowMaskMismatch(allowMaskMismatch)
                            .maxFramePayloadLength(maxFramePayloadLength)
                            .webSocketHandler(webSocketHandler)
                            .build();

                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(http1Codec, http1Aggregator, webSocketProtocolHandler);
                  }
                })
            .connect(address)
            .sync()
            .channel();
    return channel;
  }

  static class ServerConnectionAcceptor extends ChannelInitializer<SocketChannel> {
    final ChannelHandler webSocketHandler;
    final boolean expectMaskedFrames;
    final boolean allowMaskMismatch;

    ServerConnectionAcceptor(
        ChannelHandler webSocketHandler, boolean expectMaskedFrames, boolean allowMaskMismatch) {
      this.webSocketHandler = webSocketHandler;
      this.expectMaskedFrames = expectMaskedFrames;
      this.allowMaskMismatch = allowMaskMismatch;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
      HttpServerCodec http1Codec = new HttpServerCodec();
      HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);

      WebSocketDecoderConfig decoderConfig =
          WebSocketDecoderConfig.newBuilder()
              .expectMaskedFrames(expectMaskedFrames)
              .allowMaskMismatch(allowMaskMismatch)
              .withUTF8Validator(true)
              .allowExtensions(false)
              .maxFramePayloadLength(65535)
              .build();

      WebSocketServerProtocolHandler webSocketProtocolHandler =
          new WebSocketServerProtocolHandler("/test", null, false, false, 15_000, decoderConfig);

      ch.pipeline()
          .addLast(http1Codec, http1Aggregator, webSocketProtocolHandler, webSocketHandler);
    }
  }

  static class StrictMaskEncoderServerHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    private final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    private final CompletableFuture<Void> onFramesRead = new CompletableFuture<>();
    private WebSocketFrameFactory webSocketFrameFactory;

    StrictMaskEncoderServerHandler() {}

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (opcode == WebSocketProtocol.OPCODE_BINARY) {
        ByteBuf binaryFrame =
            webSocketFrameFactory.createBinaryFrame(ctx.alloc(), payload.readableBytes());
        binaryFrame.writeBytes(payload);
        ctx.writeAndFlush(webSocketFrameFactory.mask(binaryFrame));
      }
      payload.release();
      if (!onFramesRead.isDone()) {
        onFramesRead.complete(null);
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(cause);
      }
    }

    CompletableFuture<Void> onFramesRead() {
      return onFramesRead;
    }
  }

  static class StrictMaskEncoderClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    private final CompletableFuture<Void> onCloseFrameRead = new CompletableFuture<>();
    private final CompletableFuture<Void> onBinaryFrameRead = new CompletableFuture<>();
    private volatile int framesReadCount = 0;
    private WebSocketFrameFactory webSocketFrameFactory;

    StrictMaskEncoderClientHandler() {}

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      return this;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      WebSocketFrameFactory factory = webSocketFrameFactory;
      ByteBuf frame = factory.createBinaryFrame(ctx.alloc(), 1);
      frame.writeByte(0xFE);
      ctx.writeAndFlush(factory.mask(frame));
    }

    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      /*written on eventloop thread only*/
      framesReadCount++;
      if (opcode == WebSocketProtocol.OPCODE_CLOSE) {
        int code = WebSocketCloseStatus.PROTOCOL_ERROR.code();
        if (code == CloseFramePayload.statusCode(payload)) {
          onCloseFrameRead.complete(null);
        } else {
          onCloseFrameRead.completeExceptionally(
              new AssertionError("unexpected close status code: " + code));
        }
      } else if (opcode == WebSocketProtocol.OPCODE_BINARY) {
        onBinaryFrameRead.complete(null);
      }
      payload.release();
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!onCloseFrameRead.isDone()) {
        onCloseFrameRead.completeExceptionally(cause);
      }
    }

    CompletableFuture<Void> onCloseFrameRead() {
      return onCloseFrameRead;
    }

    CompletableFuture<Void> onBinaryFrameRead() {
      return onBinaryFrameRead;
    }

    int framesReadCount() {
      return framesReadCount;
    }
  }

  static class BinaryFramesEncoderClientBulkHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    private final CompletableFuture<WebSocketFrameFactory.BulkEncoder> onHandshakeComplete =
        new CompletableFuture<>();
    private final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    private WebSocketFrameFactory.BulkEncoder binaryFrameEncoder;
    private final int framesCount;
    private int receivedFrames;
    private int sentFrames;
    private ByteBuf outBuffer;
    private volatile ChannelHandlerContext ctx;

    BinaryFramesEncoderClientBulkHandler(int maxFrameSize) {
      this.framesCount = maxFrameSize;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.binaryFrameEncoder = webSocketFrameFactory.bulkEncoder();
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (!finalFragment) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-final frame: " + finalFragment));
        payload.release();
        return;
      }
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }
      if (opcode != WebSocketProtocol.OPCODE_BINARY) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-binary frame: " + Long.toHexString(opcode)));
        payload.release();
        return;
      }

      int readableBytes = payload.readableBytes();

      int expectedSize = receivedFrames;
      if (expectedSize != readableBytes) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError(
                "received frame of unexpected size: "
                    + expectedSize
                    + ", actual: "
                    + readableBytes));
        payload.release();
        return;
      }

      for (int i = 0; i < readableBytes; i++) {
        byte b = payload.readByte();
        if (b != (byte) 0xFE) {
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError("received frame with unexpected content: " + Long.toHexString(b)));
          payload.release();
          return;
        }
      }
      payload.release();
      if (++receivedFrames == framesCount) {
        onFrameExchangeComplete.complete(null);
      }
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
      int bufferSize = 4 * framesCount;
      this.outBuffer = ctx.alloc().buffer(bufferSize, bufferSize);
      onHandshakeComplete.complete(binaryFrameEncoder);
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      ByteBuf out = outBuffer;
      if (out != null) {
        outBuffer = null;
        out.release();
      }
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(new ClosedChannelException());
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(cause);
      }
    }

    CompletableFuture<WebSocketFrameFactory.BulkEncoder> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    CompletableFuture<Void> startFramesExchange() {
      ChannelHandlerContext c = ctx;
      c.executor().execute(() -> sendFrames(c, framesCount - sentFrames));
      return onFrameExchangeComplete;
    }

    private void sendFrames(ChannelHandlerContext c, int toSend) {
      WebSocketFrameFactory.BulkEncoder frameEncoder = binaryFrameEncoder;
      for (int frameIdx = 0; frameIdx < toSend; frameIdx++) {
        if (!c.channel().isOpen()) {
          return;
        }
        int payloadSize = sentFrames;
        int frameSize = frameEncoder.sizeofBinaryFrame(payloadSize);
        ByteBuf out = outBuffer;
        if (frameSize > out.capacity() - out.writerIndex()) {
          int readableBytes = out.readableBytes();
          int bufferSize = 4 * framesCount;
          outBuffer = c.alloc().buffer(bufferSize, bufferSize);
          if (c.channel().bytesBeforeUnwritable() < readableBytes) {
            c.writeAndFlush(out, c.voidPromise());
          } else {
            c.write(out, c.voidPromise());
          }
          out = outBuffer;
        }
        int mask = frameEncoder.encodeBinaryFramePrefix(out, payloadSize);
        for (int payloadIdx = 0; payloadIdx < payloadSize; payloadIdx++) {
          out.writeByte(0xFE);
        }
        frameEncoder.maskBinaryFrame(out, mask, payloadSize);
        sentFrames++;
      }
      ByteBuf out = outBuffer;
      if (out.readableBytes() > 0) {
        c.writeAndFlush(out, c.voidPromise());
      } else {
        c.flush();
      }
    }
  }

  static class TextFramesEncoderClientBulkHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    private final CompletableFuture<WebSocketFrameFactory.BulkEncoder> onHandshakeComplete =
        new CompletableFuture<>();
    private final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    private final int framesCount;
    private final char expectedAsciiChar;
    private WebSocketFrameFactory.BulkEncoder textFrameEncoder;
    private int receivedFrames;
    private int sentFrames;
    private ByteBuf outBuffer;
    private volatile ChannelHandlerContext ctx;

    TextFramesEncoderClientBulkHandler(int maxFrameSize, char expectedAsciiChar) {
      this.framesCount = maxFrameSize;
      this.expectedAsciiChar = expectedAsciiChar;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.textFrameEncoder = webSocketFrameFactory.bulkEncoder();
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (!finalFragment) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-final frame: " + finalFragment));
        payload.release();
        return;
      }
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }
      if (opcode != WebSocketProtocol.OPCODE_TEXT) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-text frame: " + Long.toHexString(opcode)));
        payload.release();
        return;
      }

      int readableBytes = payload.readableBytes();

      int expectedSize = receivedFrames;
      if (expectedSize != readableBytes) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError(
                "received frame of unexpected size: "
                    + expectedSize
                    + ", actual: "
                    + readableBytes));
        payload.release();
        return;
      }

      for (int i = 0; i < readableBytes; i++) {
        char ch = (char) payload.readByte();
        if (ch != expectedAsciiChar) {
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError(
                  "received frame with unexpected content: "
                      + ch
                      + ", expected: "
                      + expectedAsciiChar));
          payload.release();
          return;
        }
      }
      payload.release();
      if (++receivedFrames == framesCount) {
        onFrameExchangeComplete.complete(null);
      }
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
      int bufferSize = 4 * framesCount;
      this.outBuffer = ctx.alloc().buffer(bufferSize, bufferSize);
      onHandshakeComplete.complete(textFrameEncoder);
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      ByteBuf out = outBuffer;
      if (out != null) {
        outBuffer = null;
        out.release();
      }
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(new ClosedChannelException());
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(cause);
      }
    }

    CompletableFuture<WebSocketFrameFactory.BulkEncoder> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    CompletableFuture<Void> startFramesExchange() {
      ChannelHandlerContext c = ctx;
      c.executor().execute(() -> sendFrames(c, framesCount - sentFrames));
      return onFrameExchangeComplete;
    }

    private void sendFrames(ChannelHandlerContext c, int toSend) {
      WebSocketFrameFactory.BulkEncoder frameEncoder = textFrameEncoder;
      for (int frameIdx = 0; frameIdx < toSend; frameIdx++) {
        if (!c.channel().isOpen()) {
          return;
        }
        int payloadSize = sentFrames;
        int frameSize = frameEncoder.sizeofTextFrame(payloadSize);
        ByteBuf out = outBuffer;
        if (frameSize > out.capacity() - out.writerIndex()) {
          int readableBytes = out.readableBytes();
          int bufferSize = 4 * framesCount;
          outBuffer = c.alloc().buffer(bufferSize, bufferSize);
          if (c.channel().bytesBeforeUnwritable() < readableBytes) {
            c.writeAndFlush(out, c.voidPromise());
          } else {
            c.write(out, c.voidPromise());
          }
          out = outBuffer;
        }
        int mask = frameEncoder.encodeTextFramePrefix(out, payloadSize);
        for (int payloadIdx = 0; payloadIdx < payloadSize; payloadIdx++) {
          out.writeByte(expectedAsciiChar);
        }
        frameEncoder.maskTextFrame(out, mask, payloadSize);
        sentFrames++;
      }
      ByteBuf out = outBuffer;
      if (out.readableBytes() > 0) {
        c.writeAndFlush(out, c.voidPromise());
      } else {
        c.flush();
      }
    }
  }

  static class BinaryFramesEncoderClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    private final CompletableFuture<WebSocketFrameFactory.Encoder> onHandshakeComplete =
        new CompletableFuture<>();
    private final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    private WebSocketFrameFactory.Encoder binaryFrameEncoder;
    private final int framesCount;
    private int receivedFrames;
    private int sentFrames;
    private volatile ChannelHandlerContext ctx;

    BinaryFramesEncoderClientHandler(int maxFrameSize) {
      this.framesCount = maxFrameSize;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.binaryFrameEncoder = webSocketFrameFactory.encoder();
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (!finalFragment) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-final frame: " + finalFragment));
        payload.release();
        return;
      }
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }
      if (opcode != WebSocketProtocol.OPCODE_BINARY) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-binary frame: " + Long.toHexString(opcode)));
        payload.release();
        return;
      }

      int readableBytes = payload.readableBytes();

      int expectedSize = receivedFrames;
      if (expectedSize != readableBytes) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError(
                "received frame of unexpected size: "
                    + expectedSize
                    + ", actual: "
                    + readableBytes));
        payload.release();
        return;
      }

      for (int i = 0; i < readableBytes; i++) {
        byte b = payload.readByte();
        if (b != (byte) 0xFE) {
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError("received frame with unexpected content: " + Long.toHexString(b)));
          payload.release();
          return;
        }
      }
      payload.release();
      if (++receivedFrames == framesCount) {
        onFrameExchangeComplete.complete(null);
      }
    }

    @Override
    public void onChannelWritabilityChanged(ChannelHandlerContext ctx) {
      boolean writable = ctx.channel().isWritable();
      if (sentFrames > 0 && writable) {
        int toSend = framesCount - sentFrames;
        if (toSend > 0) {
          sendFrames(ctx, toSend);
        }
      }
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
      onHandshakeComplete.complete(binaryFrameEncoder);
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(new ClosedChannelException());
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(cause);
      }
    }

    CompletableFuture<WebSocketFrameFactory.Encoder> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    CompletableFuture<Void> startFramesExchange() {
      ChannelHandlerContext c = ctx;
      c.executor().execute(() -> sendFrames(c, framesCount - sentFrames));
      return onFrameExchangeComplete;
    }

    private void sendFrames(ChannelHandlerContext c, int toSend) {
      Channel ch = c.channel();
      WebSocketFrameFactory.Encoder frameEncoder = binaryFrameEncoder;
      boolean pendingFlush = false;
      ByteBufAllocator allocator = c.alloc();
      for (int frameIdx = 0; frameIdx < toSend; frameIdx++) {
        if (!c.channel().isOpen()) {
          return;
        }
        int payloadSize = sentFrames;
        int frameSize = frameEncoder.sizeofBinaryFrame(payloadSize);
        ByteBuf binaryFrame = allocator.buffer(frameSize);
        binaryFrame.writerIndex(frameSize - payloadSize);
        for (int payloadIdx = 0; payloadIdx < payloadSize; payloadIdx++) {
          binaryFrame.writeByte(0xFE);
        }
        ByteBuf maskedBinaryFrame = frameEncoder.encodeBinaryFrame(binaryFrame);
        sentFrames++;
        if (ch.bytesBeforeUnwritable() < binaryFrame.capacity()) {
          c.writeAndFlush(maskedBinaryFrame, c.voidPromise());
          pendingFlush = false;
          if (!ch.isWritable()) {
            return;
          }
        } else {
          c.write(maskedBinaryFrame, c.voidPromise());
          pendingFlush = true;
        }
      }
      if (pendingFlush) {
        c.flush();
      }
    }
  }

  static class TextFramesEncoderClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    private final CompletableFuture<WebSocketFrameFactory.Encoder> onHandshakeComplete =
        new CompletableFuture<>();
    private final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    private WebSocketFrameFactory.Encoder textFrameEncoder;
    private final int framesCount;
    private final char expectedAsciiChar;
    private int receivedFrames;
    private int sentFrames;
    private volatile ChannelHandlerContext ctx;

    TextFramesEncoderClientHandler(int maxFrameSize, char expectedAsciiChar) {
      this.framesCount = maxFrameSize;
      this.expectedAsciiChar = expectedAsciiChar;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.textFrameEncoder = webSocketFrameFactory.encoder();
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (!finalFragment) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-final frame: " + finalFragment));
        payload.release();
        return;
      }
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }
      if (opcode != WebSocketProtocol.OPCODE_TEXT) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-text frame: " + Long.toHexString(opcode)));
        payload.release();
        return;
      }

      int readableBytes = payload.readableBytes();

      int expectedSize = receivedFrames;
      if (expectedSize != readableBytes) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError(
                "received frame of unexpected size: "
                    + expectedSize
                    + ", actual: "
                    + readableBytes));
        payload.release();
        return;
      }

      for (int i = 0; i < readableBytes; i++) {
        char ch = (char) payload.readByte();
        if (ch != expectedAsciiChar) {
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError(
                  "received frame with unexpected content: "
                      + ch
                      + ", expected: "
                      + expectedAsciiChar));
          payload.release();
          return;
        }
      }
      payload.release();
      if (++receivedFrames == framesCount) {
        onFrameExchangeComplete.complete(null);
      }
    }

    @Override
    public void onChannelWritabilityChanged(ChannelHandlerContext ctx) {
      boolean writable = ctx.channel().isWritable();
      if (sentFrames > 0 && writable) {
        int toSend = framesCount - sentFrames;
        if (toSend > 0) {
          sendFrames(ctx, toSend);
        }
      }
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
      onHandshakeComplete.complete(textFrameEncoder);
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(new ClosedChannelException());
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(cause);
      }
    }

    CompletableFuture<WebSocketFrameFactory.Encoder> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    CompletableFuture<Void> startFramesExchange() {
      ChannelHandlerContext c = ctx;
      c.executor().execute(() -> sendFrames(c, framesCount - sentFrames));
      return onFrameExchangeComplete;
    }

    private void sendFrames(ChannelHandlerContext c, int toSend) {
      Channel ch = c.channel();
      WebSocketFrameFactory.Encoder frameEncoder = textFrameEncoder;
      boolean pendingFlush = false;
      ByteBufAllocator allocator = c.alloc();
      for (int frameIdx = 0; frameIdx < toSend; frameIdx++) {
        if (!c.channel().isOpen()) {
          return;
        }
        int payloadSize = sentFrames;
        int frameSize = frameEncoder.sizeofTextFrame(payloadSize);
        ByteBuf textFrame = allocator.buffer(frameSize);
        textFrame.writerIndex(frameSize - payloadSize);
        for (int payloadIdx = 0; payloadIdx < payloadSize; payloadIdx++) {
          textFrame.writeByte(expectedAsciiChar);
        }
        ByteBuf maskedTextFrame = frameEncoder.encodeTextFrame(textFrame);
        sentFrames++;
        if (ch.bytesBeforeUnwritable() < textFrame.capacity()) {
          c.writeAndFlush(maskedTextFrame, c.voidPromise());
          pendingFlush = false;
          if (!ch.isWritable()) {
            return;
          }
        } else {
          c.write(maskedTextFrame, c.voidPromise());
          pendingFlush = true;
        }
      }
      if (pendingFlush) {
        c.flush();
      }
    }
  }

  static class TextFramesFactoryClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    private final CompletableFuture<WebSocketFrameFactory> onHandshakeComplete =
        new CompletableFuture<>();
    private final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    private WebSocketFrameFactory frameFactory;
    private final int framesCount;
    private final char expectedAsciiChar;
    private int receivedFrames;
    private int sentFrames;
    private volatile ChannelHandlerContext ctx;

    TextFramesFactoryClientHandler(int maxFrameSize, char expectedAsciiChar) {
      this.framesCount = maxFrameSize;
      this.expectedAsciiChar = expectedAsciiChar;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.frameFactory = webSocketFrameFactory;
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (!finalFragment) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-final frame: " + finalFragment));
        payload.release();
        return;
      }
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }
      if (opcode != WebSocketProtocol.OPCODE_TEXT) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-text frame: " + Long.toHexString(opcode)));
        payload.release();
        return;
      }

      int readableBytes = payload.readableBytes();

      int expectedSize = receivedFrames;
      if (expectedSize != readableBytes) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError(
                "received frame of unexpected size: "
                    + expectedSize
                    + ", actual: "
                    + readableBytes));
        payload.release();
        return;
      }

      for (int i = 0; i < readableBytes; i++) {
        char ch = (char) payload.readByte();
        if (ch != expectedAsciiChar) {
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError(
                  "received frame with unexpected content: "
                      + ch
                      + ", expected: "
                      + expectedAsciiChar));
          payload.release();
          return;
        }
      }
      payload.release();
      if (++receivedFrames == framesCount) {
        onFrameExchangeComplete.complete(null);
      }
    }

    @Override
    public void onChannelWritabilityChanged(ChannelHandlerContext ctx) {
      boolean writable = ctx.channel().isWritable();
      if (sentFrames > 0 && writable) {
        int toSend = framesCount - sentFrames;
        if (toSend > 0) {
          sendFrames(ctx, toSend);
        }
      }
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
      onHandshakeComplete.complete(frameFactory);
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(new ClosedChannelException());
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(cause);
      }
    }

    CompletableFuture<WebSocketFrameFactory> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    CompletableFuture<Void> startFramesExchange() {
      ChannelHandlerContext c = ctx;
      c.executor().execute(() -> sendFrames(c, framesCount - sentFrames));
      return onFrameExchangeComplete;
    }

    private void sendFrames(ChannelHandlerContext c, int toSend) {
      Channel ch = c.channel();
      WebSocketFrameFactory factory = frameFactory;
      boolean pendingFlush = false;
      ByteBufAllocator allocator = c.alloc();
      for (int frameIdx = 0; frameIdx < toSend; frameIdx++) {
        if (!c.channel().isOpen()) {
          return;
        }
        int payloadSize = sentFrames;
        ByteBuf textFrame = factory.createTextFrame(allocator, payloadSize);
        for (int payloadIdx = 0; payloadIdx < payloadSize; payloadIdx++) {
          textFrame.writeByte(expectedAsciiChar);
        }
        ByteBuf maskedTextFrame = factory.mask(textFrame);
        sentFrames++;
        if (ch.bytesBeforeUnwritable() < textFrame.capacity()) {
          c.writeAndFlush(maskedTextFrame, c.voidPromise());
          pendingFlush = false;
          if (!ch.isWritable()) {
            return;
          }
        } else {
          c.write(maskedTextFrame, c.voidPromise());
          pendingFlush = true;
        }
      }
      if (pendingFlush) {
        c.flush();
      }
    }
  }

  static class BinaryFramesTestClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    private final CompletableFuture<WebSocketFrameFactory> onHandshakeComplete =
        new CompletableFuture<>();
    private final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    private WebSocketFrameFactory webSocketFrameFactory;
    private final int framesCount;
    private int receivedFrames;
    private int sentFrames;
    private volatile ChannelHandlerContext ctx;

    BinaryFramesTestClientHandler(int maxFrameSize) {
      this.framesCount = maxFrameSize;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (!finalFragment) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-final frame: " + finalFragment));
        payload.release();
        return;
      }
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }
      if (opcode != WebSocketProtocol.OPCODE_BINARY) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-binary frame: " + Long.toHexString(opcode)));
        payload.release();
        return;
      }

      int readableBytes = payload.readableBytes();

      int expectedSize = receivedFrames;
      if (expectedSize != readableBytes) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError(
                "received frame of unexpected size: "
                    + expectedSize
                    + ", actual: "
                    + readableBytes));
        payload.release();
        return;
      }

      for (int i = 0; i < readableBytes; i++) {
        byte b = payload.readByte();
        if (b != (byte) 0xFE) {
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError("received frame with unexpected content: " + Long.toHexString(b)));
          payload.release();
          return;
        }
      }
      payload.release();
      if (++receivedFrames == framesCount) {
        onFrameExchangeComplete.complete(null);
      }
    }

    @Override
    public void onChannelWritabilityChanged(ChannelHandlerContext ctx) {
      boolean writable = ctx.channel().isWritable();
      if (sentFrames > 0 && writable) {
        int toSend = framesCount - sentFrames;
        if (toSend > 0) {
          sendFrames(ctx, toSend);
        }
      }
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
      onHandshakeComplete.complete(webSocketFrameFactory);
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(new ClosedChannelException());
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(cause);
      }
    }

    CompletableFuture<WebSocketFrameFactory> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    CompletableFuture<Void> startFramesExchange() {
      ChannelHandlerContext c = ctx;
      c.executor().execute(() -> sendFrames(c, framesCount - sentFrames));
      return onFrameExchangeComplete;
    }

    private void sendFrames(ChannelHandlerContext c, int toSend) {
      Channel ch = c.channel();
      WebSocketFrameFactory factory = webSocketFrameFactory;
      boolean pendingFlush = false;
      for (int frameIdx = 0; frameIdx < toSend; frameIdx++) {
        if (!c.channel().isOpen()) {
          return;
        }
        int frameSize = sentFrames;
        ByteBuf binaryFrame = factory.createBinaryFrame(c.alloc(), frameSize);
        for (int payloadIdx = 0; payloadIdx < frameSize; payloadIdx++) {
          binaryFrame.writeByte(0xFE);
        }
        ByteBuf maskedBinaryFrame = factory.mask(binaryFrame);
        sentFrames++;
        if (ch.bytesBeforeUnwritable() < binaryFrame.capacity()) {
          c.writeAndFlush(maskedBinaryFrame, c.voidPromise());
          pendingFlush = false;
          if (!ch.isWritable()) {
            return;
          }
        } else {
          c.write(maskedBinaryFrame, c.voidPromise());
          pendingFlush = true;
        }
      }
      if (pendingFlush) {
        c.flush();
      }
    }
  }

  static class TextFramesTestClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    final CompletableFuture<WebSocketFrameFactory> onHandshakeComplete = new CompletableFuture<>();
    final int framesCount;
    final char expectedContent;
    WebSocketFrameFactory webSocketFrameFactory;
    int receivedFrames;
    volatile ChannelHandlerContext ctx;

    TextFramesTestClientHandler(int maxFrameSize, char expectedContent) {
      this.framesCount = maxFrameSize;
      this.expectedContent = expectedContent;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      onHandshakeComplete.complete(webSocketFrameFactory);
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (!finalFragment) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-final frame: " + finalFragment));
        payload.release();
        return;
      }
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }
      if (opcode != WebSocketProtocol.OPCODE_TEXT) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-text frame: " + Long.toHexString(opcode)));
        payload.release();
        return;
      }

      CharSequence content =
          payload.readCharSequence(payload.readableBytes(), StandardCharsets.UTF_8);

      int expectedSize = receivedFrames;
      if (expectedSize != content.length()) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError(
                "received frame of unexpected size: "
                    + expectedSize
                    + ", actual: "
                    + content.length()));
        payload.release();
        return;
      }

      for (int i = 0; i < content.length(); i++) {
        char ch = content.charAt(i);
        char expectedCh = expectedContent;
        if (ch != expectedCh) {
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError(
                  "received text frame with unexpected content: "
                      + ch
                      + ", expected: "
                      + expectedCh));
          payload.release();
          return;
        }
      }
      payload.release();
      if (++receivedFrames == framesCount) {
        onFrameExchangeComplete.complete(null);
      }
    }

    CompletableFuture<WebSocketFrameFactory> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    CompletableFuture<Void> onFrameExchangeCompleted() {
      return onFrameExchangeComplete;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
    }
  }

  static class InboundFragmentationFramesTestClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    final CompletableFuture<WebSocketFrameFactory> onHandshakeComplete = new CompletableFuture<>();
    final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    final int frameSize;
    WebSocketFrameFactory webSocketFrameFactory;
    volatile ChannelHandlerContext ctx;

    InboundFragmentationFramesTestClientHandler(int frameSize) {
      this.frameSize = frameSize;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      inboundFrame = new CompositeByteBuf(ctx.alloc(), true, 3);
      return this;
    }

    CompositeByteBuf inboundFrame;

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }

      CompositeByteBuf inbound = inboundFrame;
      /*first*/
      if (inbound.numComponents() == 0) {
        if (opcode != WebSocketProtocol.OPCODE_BINARY) {
          payload.release();
          inbound.release();
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError("first fragment with non-binary opcode: " + opcode));
        }
        if (finalFragment) {
          payload.release();
          inbound.release();
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError("received non-fragmented frame"));
        }
      } else {
        if (opcode != WebSocketProtocol.OPCODE_CONT) {
          payload.release();
          inbound.release();
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError("received non-continuation frame, opcode: " + opcode));
        }
      }
      inbound.addComponent(true, payload);

      if (finalFragment) {
        int readableBytes = inbound.readableBytes();
        if (readableBytes != frameSize) {
          inbound.release();
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError(
                  "Unexpected inbound frame size: " + readableBytes + ", expected: " + frameSize));
        }
        for (int i = 0; i < readableBytes; i++) {
          byte b = inbound.readByte();
          if (b != (byte) 0xFE) {
            inbound.release();
            payload.release();
            onFrameExchangeComplete.completeExceptionally(
                new AssertionError(
                    "received frame with unexpected content: " + Long.toHexString(b)));
            return;
          }
        }
        onFrameExchangeComplete.complete(null);
        inbound.release();
      }
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
      onHandshakeComplete.complete(webSocketFrameFactory);
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(new ClosedChannelException());
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(cause);
      }
    }

    CompletableFuture<WebSocketFrameFactory> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    CompletableFuture<Void> startFramesExchange() {
      ChannelHandlerContext c = ctx;
      c.executor().execute(() -> sendFrames(c));
      return onFrameExchangeComplete;
    }

    private void sendFrames(ChannelHandlerContext c) {
      WebSocketFrameFactory factory = webSocketFrameFactory;
      ByteBuf binaryFrame = factory.createBinaryFrame(c.alloc(), frameSize);
      for (int payloadIdx = 0; payloadIdx < frameSize; payloadIdx++) {
        binaryFrame.writeByte(0xFE);
      }
      c.writeAndFlush(factory.mask(binaryFrame));
    }
  }

  static class OutboundBinaryFragmentationEncoderClientHandler
      extends OutboundBinaryFragmentationClientHandler {
    OutboundBinaryFragmentationEncoderClientHandler(int frameSize) {
      super(frameSize);
    }

    static ByteBuf withPayload(
        ByteBufAllocator allocator, WebSocketFrameFactory.Encoder encoder, byte content, int size) {
      int frameSize = encoder.sizeofBinaryFrame(size);
      ByteBuf binaryFrame = allocator.buffer(frameSize);
      binaryFrame.writerIndex(frameSize - size);
      for (int i = 0; i < size; i++) {
        binaryFrame.writeByte(content);
      }
      return binaryFrame;
    }

    @Override
    protected void sendFrames(ChannelHandlerContext c) {
      WebSocketFrameFactory.Encoder encoder = webSocketFrameFactory.encoder();
      ByteBufAllocator allocator = c.alloc();
      while (framesSent < frameSize) {
        int size = ++framesSent;

        ByteBuf shortFragmentStart =
            encoder.encodeBinaryFragmentStart(withPayload(allocator, encoder, (byte) 0xFE, size));
        ByteBuf shortFragmentEnd =
            encoder.encodeContinuationFragmentEnd(
                withPayload(allocator, encoder, (byte) 0xFE, size));

        ByteBuf longFragmentStart =
            encoder.encodeBinaryFragmentStart(withPayload(allocator, encoder, (byte) 0xFE, size));
        ByteBuf longFragmentContinuation =
            encoder.encodeContinuationFragment(withPayload(allocator, encoder, (byte) 0xFE, size));
        ByteBuf longFragmentEnd =
            encoder.encodeContinuationFragmentEnd(
                withPayload(allocator, encoder, (byte) 0xFE, size));

        ctx.write(shortFragmentStart);
        ctx.write(shortFragmentEnd);
        ctx.write(longFragmentStart);
        ctx.write(longFragmentContinuation);
        ctx.writeAndFlush(longFragmentEnd);
      }
    }
  }

  static class OutboundTextFragmentationEncoderClientHandler
      extends OutboundTextFragmentationClientHandler {

    OutboundTextFragmentationEncoderClientHandler(int frameSize, char expectedContent) {
      super(frameSize, expectedContent);
    }

    static ByteBuf withPayload(
        ByteBufAllocator allocator, WebSocketFrameFactory.Encoder encoder, char content, int size) {
      int frameSize = encoder.sizeofTextFrame(size);
      ByteBuf binaryFrame = allocator.buffer(frameSize);
      binaryFrame.writerIndex(frameSize - size);
      for (int i = 0; i < size; i++) {
        binaryFrame.writeByte(content);
      }
      return binaryFrame;
    }

    @Override
    protected void sendFrames(ChannelHandlerContext c) {
      WebSocketFrameFactory.Encoder encoder = webSocketFrameFactory.encoder();
      ByteBufAllocator allocator = c.alloc();
      while (framesSent < frameSize) {
        int size = ++framesSent;

        char expected = expectedContent;
        ByteBuf shortFragmentStart =
            encoder.encodeTextFragmentStart(withPayload(allocator, encoder, expected, size));
        ByteBuf shortFragmentEnd =
            encoder.encodeContinuationFragmentEnd(withPayload(allocator, encoder, expected, size));

        ByteBuf longFragmentStart =
            encoder.encodeTextFragmentStart(withPayload(allocator, encoder, expected, size));
        ByteBuf longFragmentContinuation =
            encoder.encodeContinuationFragment(withPayload(allocator, encoder, expected, size));
        ByteBuf longFragmentEnd =
            encoder.encodeContinuationFragmentEnd(withPayload(allocator, encoder, expected, size));

        ctx.write(shortFragmentStart);
        ctx.write(shortFragmentEnd);
        ctx.write(longFragmentStart);
        ctx.write(longFragmentContinuation);
        ctx.writeAndFlush(longFragmentEnd);
      }
    }
  }

  static class OutboundTextFragmentationClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    final CompletableFuture<WebSocketFrameFactory> onHandshakeComplete = new CompletableFuture<>();
    final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    final int frameSize;
    final char expectedContent;
    WebSocketFrameFactory webSocketFrameFactory;
    volatile ChannelHandlerContext ctx;
    int framesReceived;
    int framesSent;
    int fragmentsReceived;

    OutboundTextFragmentationClientHandler(int frameSize, char expectedContent) {
      this.frameSize = frameSize;
      this.expectedContent = expectedContent;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }
      if (opcode == WebSocketProtocol.OPCODE_CLOSE) {
        onFrameExchangeComplete.completeExceptionally(new AssertionError("received close frame"));
        payload.release();
        return;
      }
      switch (fragmentsReceived) {
        case /*short start*/ 0:
        case /*long start*/ 2:
          {
            if (opcode != WebSocketProtocol.OPCODE_TEXT) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError("first fragment with non-text opcode: " + opcode));
              return;
            }
            if (finalFragment) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError("received non-fragmented frame"));
              return;
            }
            int expectedSize = framesReceived + 1;
            int payloadSize = payload.readableBytes();
            if (payloadSize != expectedSize) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError(
                      "received fragment frame with payload size: "
                          + payloadSize
                          + ", expected: "
                          + expectedSize));
              return;
            }
            for (int i = 0; i < payload.readableBytes(); i++) {
              char ch = (char) payload.readByte();
              if (ch != expectedContent) {
                payload.release();
                onFrameExchangeComplete.completeExceptionally(
                    new AssertionError(
                        "received fragment frame with payload: "
                            + ch
                            + ", expected: "
                            + expectedContent));
                return;
              }
            }
            fragmentsReceived++;
          }
          break;
        case /*short end*/ 1:
        case /*long end*/ 4:
          {
            if (opcode != WebSocketProtocol.OPCODE_CONT) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError("first fragment with non-continuation opcode: " + opcode));
              return;
            }
            if (!finalFragment) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError("received non-final fragment, expected final"));
              return;
            }
            int expectedSize = framesReceived + 1;
            int payloadSize = payload.readableBytes();
            if (payloadSize != expectedSize) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError(
                      "received fragment frame with payload size: "
                          + payloadSize
                          + ", expected: "
                          + expectedSize));
              return;
            }
            for (int i = 0; i < payload.readableBytes(); i++) {
              char ch = (char) payload.readByte();
              if (ch != expectedContent) {
                payload.release();
                onFrameExchangeComplete.completeExceptionally(
                    new AssertionError(
                        "received fragment frame with payload: "
                            + ch
                            + ", expected: "
                            + expectedContent));
                return;
              }
            }
            payload.release();
            if (fragmentsReceived == /*long end*/ 4) {
              fragmentsReceived = 0;
              if (++framesReceived == frameSize) {
                onFrameExchangeComplete.complete(null);
              }
            } else {
              fragmentsReceived++;
            }
          }
          break;
        case /*long continuation*/ 3:
          {
            if (opcode != WebSocketProtocol.OPCODE_CONT) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError("first fragment with non-continuation opcode: " + opcode));
              return;
            }
            if (finalFragment) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError("received final fragment, expected non-final"));
              return;
            }
            int expectedSize = framesReceived + 1;
            int payloadSize = payload.readableBytes();
            if (payloadSize != expectedSize) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError(
                      "received fragment frame with payload size: "
                          + payloadSize
                          + ", expected: "
                          + expectedSize));
              return;
            }
            for (int i = 0; i < payload.readableBytes(); i++) {
              char ch = (char) payload.readByte();
              if (ch != expectedContent) {
                payload.release();
                onFrameExchangeComplete.completeExceptionally(
                    new AssertionError(
                        "received fragment frame with payload: "
                            + ch
                            + ", expected: "
                            + expectedContent));
                return;
              }
            }
            payload.release();
            fragmentsReceived++;
          }
          break;
        default:
          throw new AssertionError("Unexpected fragmentsReceived state: " + fragmentsReceived);
      }
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
      onHandshakeComplete.complete(webSocketFrameFactory);
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(new ClosedChannelException());
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(cause);
      }
    }

    CompletableFuture<WebSocketFrameFactory> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    CompletableFuture<Void> startFramesExchange() {
      ChannelHandlerContext c = ctx;
      c.executor().execute(() -> sendFrames(c));
      return onFrameExchangeComplete;
    }

    protected void sendFrames(ChannelHandlerContext c) {
      WebSocketFrameFactory factory = webSocketFrameFactory;
      while (framesSent < frameSize) {
        int size = ++framesSent;
        ByteBuf shortFragmentStart =
            factory.mask(
                withPayload(
                    factory.createTextFragmentStart(c.alloc(), size), expectedContent, size));
        ByteBuf shortFragmentEnd =
            factory.mask(
                withPayload(
                    factory.createContinuationFragmentEnd(c.alloc(), size), expectedContent, size));

        ByteBuf longFragmentStart =
            factory.mask(
                withPayload(
                    factory.createTextFragmentStart(c.alloc(), size), expectedContent, size));
        ByteBuf longFragmentContinuation =
            factory.mask(
                withPayload(
                    factory.createContinuationFragment(c.alloc(), size), expectedContent, size));
        ByteBuf longFragmentEnd =
            factory.mask(
                withPayload(
                    factory.createContinuationFragmentEnd(c.alloc(), size), expectedContent, size));

        ctx.write(shortFragmentStart);
        ctx.write(shortFragmentEnd);
        ctx.write(longFragmentStart);
        ctx.write(longFragmentContinuation);
        ctx.writeAndFlush(longFragmentEnd);
      }
    }

    static ByteBuf withPayload(ByteBuf fragment, char content, int size) {
      for (int i = 0; i < size; i++) {
        fragment.writeByte(content);
      }
      return fragment;
    }
  }

  static class OutboundBinaryFragmentationClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    final CompletableFuture<WebSocketFrameFactory> onHandshakeComplete = new CompletableFuture<>();
    final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    final int frameSize;
    WebSocketFrameFactory webSocketFrameFactory;
    volatile ChannelHandlerContext ctx;
    int framesReceived;
    int framesSent;
    int fragmentsReceived;

    OutboundBinaryFragmentationClientHandler(int frameSize) {
      this.frameSize = frameSize;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }
      if (opcode == WebSocketProtocol.OPCODE_CLOSE) {
        onFrameExchangeComplete.completeExceptionally(new AssertionError("received close frame"));
        payload.release();
        return;
      }
      switch (fragmentsReceived) {
        case /*short start*/ 0:
        case /*long start*/ 2:
          {
            if (opcode != WebSocketProtocol.OPCODE_BINARY) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError("first fragment with non-binary opcode: " + opcode));
              return;
            }
            if (finalFragment) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError("received non-fragmented frame"));
              return;
            }
            int expectedSize = framesReceived + 1;
            int payloadSize = payload.readableBytes();
            if (payloadSize != expectedSize) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError(
                      "received fragment frame with payload size: "
                          + payloadSize
                          + ", expected: "
                          + expectedSize));
              return;
            }
            byte expectedPayload = (byte) 0xFE;
            for (int i = 0; i < payload.readableBytes(); i++) {
              byte b = payload.readByte();
              if (b != expectedPayload) {
                payload.release();
                onFrameExchangeComplete.completeExceptionally(
                    new AssertionError(
                        "received fragment frame with payload: "
                            + Long.toHexString(b)
                            + ", expected: "
                            + Long.toHexString(expectedPayload)));
                return;
              }
            }
            fragmentsReceived++;
          }
          break;
        case /*short end*/ 1:
        case /*long end*/ 4:
          {
            if (opcode != WebSocketProtocol.OPCODE_CONT) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError("first fragment with non-continuation opcode: " + opcode));
              return;
            }
            if (!finalFragment) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError("received non-final fragment, expected final"));
              return;
            }
            int expectedSize = framesReceived + 1;
            int payloadSize = payload.readableBytes();
            if (payloadSize != expectedSize) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError(
                      "received fragment frame with payload size: "
                          + payloadSize
                          + ", expected: "
                          + expectedSize));
              return;
            }
            byte expectedPayload = (byte) 0xFE;
            for (int i = 0; i < payload.readableBytes(); i++) {
              byte b = payload.readByte();
              if (b != expectedPayload) {
                payload.release();
                onFrameExchangeComplete.completeExceptionally(
                    new AssertionError(
                        "received fragment frame with payload: "
                            + Long.toHexString(b)
                            + ", expected: "
                            + Long.toHexString(expectedPayload)));
                return;
              }
            }
            payload.release();
            if (fragmentsReceived == /*long end*/ 4) {
              fragmentsReceived = 0;
              if (++framesReceived == frameSize) {
                onFrameExchangeComplete.complete(null);
              }
            } else {
              fragmentsReceived++;
            }
          }
          break;
        case /*long continuation*/ 3:
          {
            if (opcode != WebSocketProtocol.OPCODE_CONT) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError("first fragment with non-continuation opcode: " + opcode));
              return;
            }
            if (finalFragment) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError("received final fragment, expected non-final"));
              return;
            }
            int expectedSize = framesReceived + 1;
            int payloadSize = payload.readableBytes();
            if (payloadSize != expectedSize) {
              payload.release();
              onFrameExchangeComplete.completeExceptionally(
                  new AssertionError(
                      "received fragment frame with payload size: "
                          + payloadSize
                          + ", expected: "
                          + expectedSize));
              return;
            }
            byte expectedPayload = (byte) 0xFE;
            for (int i = 0; i < payload.readableBytes(); i++) {
              byte b = payload.readByte();
              if (b != expectedPayload) {
                payload.release();
                onFrameExchangeComplete.completeExceptionally(
                    new AssertionError(
                        "received fragment frame with payload: "
                            + Long.toHexString(b)
                            + ", expected: "
                            + Long.toHexString(expectedPayload)));
                return;
              }
            }
            payload.release();
            fragmentsReceived++;
          }
          break;
        default:
          throw new AssertionError("Unexpected fragmentsReceived state: " + fragmentsReceived);
      }
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
      onHandshakeComplete.complete(webSocketFrameFactory);
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(new ClosedChannelException());
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!onFrameExchangeComplete.isDone()) {
        onFrameExchangeComplete.completeExceptionally(cause);
      }
    }

    CompletableFuture<WebSocketFrameFactory> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    CompletableFuture<Void> startFramesExchange() {
      ChannelHandlerContext c = ctx;
      c.executor().execute(() -> sendFrames(c));
      return onFrameExchangeComplete;
    }

    protected void sendFrames(ChannelHandlerContext c) {
      WebSocketFrameFactory factory = webSocketFrameFactory;
      while (framesSent < frameSize) {
        int size = ++framesSent;
        ByteBuf shortFragmentStart =
            factory.mask(
                withPayload(factory.createBinaryFragmentStart(c.alloc(), size), (byte) 0xFE, size));
        ByteBuf shortFragmentEnd =
            factory.mask(
                withPayload(
                    factory.createContinuationFragmentEnd(c.alloc(), size), (byte) 0xFE, size));

        ByteBuf longFragmentStart =
            factory.mask(
                withPayload(factory.createBinaryFragmentStart(c.alloc(), size), (byte) 0xFE, size));
        ByteBuf longFragmentContinuation =
            factory.mask(
                withPayload(
                    factory.createContinuationFragment(c.alloc(), size), (byte) 0xFE, size));
        ByteBuf longFragmentEnd =
            factory.mask(
                withPayload(
                    factory.createContinuationFragmentEnd(c.alloc(), size), (byte) 0xFE, size));

        ctx.write(shortFragmentStart);
        ctx.write(shortFragmentEnd);
        ctx.write(longFragmentStart);
        ctx.write(longFragmentContinuation);
        ctx.writeAndFlush(longFragmentEnd);
      }
    }

    static ByteBuf withPayload(ByteBuf fragment, byte content, int size) {
      for (int i = 0; i < size; i++) {
        fragment.writeByte(content);
      }
      return fragment;
    }
  }

  static class TextFramesTestServerHandler extends ChannelInboundHandlerAdapter {
    final String content;
    final int framesCount;
    int sentFrames;

    TextFramesTestServerHandler(int maxFrameSize, char content) {
      this.framesCount = maxFrameSize;
      this.content = String.valueOf(content);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof WebSocketFrame) {
        ReferenceCountUtil.release(msg);
        return;
      }
      super.channelRead(ctx, msg);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
      boolean writable = ctx.channel().isWritable();
      if (sentFrames > 0 && writable) {
        int toSend = framesCount - sentFrames;
        if (toSend > 0) {
          sendFrames(ctx, toSend);
        }
      }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
        sendFrames(ctx, framesCount - sentFrames);
      }
      super.userEventTriggered(ctx, evt);
    }

    private void sendFrames(ChannelHandlerContext c, int toSend) {
      Channel ch = c.channel();
      boolean pendingFlush = false;
      for (int frameIdx = 0; frameIdx < toSend; frameIdx++) {
        int payloadSize = sentFrames;
        ByteBuf payload = c.alloc().buffer(payloadSize);
        for (int payloadIdx = 0; payloadIdx < payloadSize; payloadIdx++) {
          payload.writeCharSequence(content, StandardCharsets.UTF_8);
        }
        TextWebSocketFrame webSocketFrame = new TextWebSocketFrame(payload);
        sentFrames++;
        if (ch.bytesBeforeUnwritable() < payload.capacity()) {
          c.writeAndFlush(webSocketFrame, c.voidPromise());
          pendingFlush = false;
          if (!ch.isWritable()) {
            return;
          }
        } else {
          c.write(webSocketFrame, c.voidPromise());
          pendingFlush = true;
        }
      }
      if (pendingFlush) {
        c.flush();
      }
    }
  }

  static class WebSocketFramesTestServerHandler extends ChannelInboundHandlerAdapter {
    boolean ready = true;
    boolean pendingFlush;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
        ctx.channel().config().setAutoRead(false);
        ctx.read();
      }
      super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof WebSocketFrame) {
        ctx.write(msg);
        pendingFlush = true;
        if (ready) {
          ctx.read();
        }
        return;
      }
      super.channelRead(ctx, msg);
      ctx.read();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
      if (!ctx.channel().isWritable()) {
        ready = false;
        ctx.flush();
      } else {
        ready = true;
        ctx.read();
      }
      super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
      if (pendingFlush) {
        pendingFlush = false;
        ctx.flush();
      }
      super.channelReadComplete(ctx);
    }
  }

  static class PongFramesTestClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    final CompletableFuture<WebSocketFrameFactory> onHandshakeComplete = new CompletableFuture<>();
    final int framesCount;
    final byte content;
    WebSocketFrameFactory webSocketFrameFactory;
    int receivedFrames;
    volatile ChannelHandlerContext ctx;

    PongFramesTestClientHandler(int maxFrameSize, byte content) {
      this.framesCount = maxFrameSize;
      this.content = content;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.ctx = ctx;
      this.webSocketFrameFactory = webSocketFrameFactory;
      onHandshakeComplete.complete(webSocketFrameFactory);
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (!finalFragment) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-final frame: " + finalFragment));
        payload.release();
        return;
      }
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }
      if (opcode != WebSocketProtocol.OPCODE_PING) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-ping frame: " + Long.toHexString(opcode)));
        payload.release();
        return;
      }

      int readableBytes = payload.readableBytes();
      int expectedSize = receivedFrames;
      if (expectedSize != readableBytes) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError(
                "received frame of unexpected size: "
                    + expectedSize
                    + ", actual: "
                    + readableBytes));
        payload.release();
        return;
      }

      for (int i = 0; i < readableBytes; i++) {
        byte b = payload.readByte();
        if (b != content) {
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError(
                  "received ping frame with unexpected content: "
                      + b
                      + ", expected: "
                      + Long.toHexString(content)));
          payload.release();
          return;
        }
      }
      if (++receivedFrames == framesCount) {
        onFrameExchangeComplete.complete(null);
      }
    }

    CompletableFuture<Void> startFramesExchange() {
      ChannelHandlerContext c = ctx;
      c.executor().execute(() -> sendFrames(c));
      return onFrameExchangeComplete;
    }

    private void sendFrames(ChannelHandlerContext c) {
      Channel ch = c.channel();
      WebSocketFrameFactory factory = webSocketFrameFactory;
      boolean pendingFlush = false;
      for (int frameIdx = 0; frameIdx < framesCount; frameIdx++) {
        if (!c.channel().isOpen()) {
          return;
        }
        ByteBuf pongFrame = factory.createPongFrame(c.alloc(), frameIdx);
        for (int payloadIdx = 0; payloadIdx < frameIdx; payloadIdx++) {
          pongFrame.writeByte(content);
        }
        ByteBuf maskedFrame = factory.mask(pongFrame);
        if (ch.bytesBeforeUnwritable() < pongFrame.capacity()) {
          c.writeAndFlush(maskedFrame, c.voidPromise());
          pendingFlush = false;
        } else {
          c.write(maskedFrame, c.voidPromise());
          pendingFlush = true;
        }
      }
      if (pendingFlush) {
        c.flush();
      }
    }

    CompletableFuture<WebSocketFrameFactory> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
    }
  }

  static class PingFramesTestClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    final CompletableFuture<WebSocketFrameFactory> onHandshakeComplete = new CompletableFuture<>();
    final int framesCount;
    final byte content;
    WebSocketFrameFactory webSocketFrameFactory;
    int receivedFrames;
    volatile ChannelHandlerContext ctx;

    PingFramesTestClientHandler(int maxFrameSize, byte content) {
      this.framesCount = maxFrameSize;
      this.content = content;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.ctx = ctx;
      this.webSocketFrameFactory = webSocketFrameFactory;
      onHandshakeComplete.complete(webSocketFrameFactory);
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (!finalFragment) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-final frame: " + finalFragment));
        payload.release();
        return;
      }
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }
      if (opcode != WebSocketProtocol.OPCODE_PONG) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-pong frame: " + Long.toHexString(opcode)));
        payload.release();
        return;
      }

      int readableBytes = payload.readableBytes();
      int expectedSize = receivedFrames;
      if (expectedSize != readableBytes) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError(
                "received frame of unexpected size: "
                    + expectedSize
                    + ", actual: "
                    + readableBytes));
        payload.release();
        return;
      }

      for (int i = 0; i < readableBytes; i++) {
        byte b = payload.readByte();
        if (b != content) {
          onFrameExchangeComplete.completeExceptionally(
              new AssertionError(
                  "received pong frame with unexpected content: "
                      + b
                      + ", expected: "
                      + Long.toHexString(content)));
          payload.release();
          return;
        }
      }
      payload.release();
      if (++receivedFrames == framesCount) {
        onFrameExchangeComplete.complete(null);
      }
    }

    CompletableFuture<Void> startFramesExchange() {
      ChannelHandlerContext c = ctx;
      c.executor().execute(() -> sendFrames(c));
      return onFrameExchangeComplete;
    }

    private void sendFrames(ChannelHandlerContext c) {
      Channel ch = c.channel();
      WebSocketFrameFactory factory = webSocketFrameFactory;
      boolean pendingFlush = false;
      for (int frameIdx = 0; frameIdx < framesCount; frameIdx++) {
        if (!c.channel().isOpen()) {
          return;
        }
        ByteBuf pingFrame = factory.createPingFrame(c.alloc(), frameIdx);
        for (int payloadIdx = 0; payloadIdx < frameIdx; payloadIdx++) {
          pingFrame.writeByte(content);
        }
        ByteBuf maskedFrame = factory.mask(pingFrame);
        if (ch.bytesBeforeUnwritable() < pingFrame.capacity()) {
          c.writeAndFlush(maskedFrame, c.voidPromise());
          pendingFlush = false;
        } else {
          c.write(maskedFrame, c.voidPromise());
          pendingFlush = true;
        }
      }
      if (pendingFlush) {
        c.flush();
      }
    }

    CompletableFuture<WebSocketFrameFactory> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
    }
  }

  static class CloseFramesTestClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    final CompletableFuture<WebSocketFrameFactory> onHandshakeComplete = new CompletableFuture<>();
    final WebSocketCloseStatus closeStatus;
    final String closeReason;
    WebSocketFrameFactory webSocketFrameFactory;
    volatile ChannelHandlerContext ctx;

    CloseFramesTestClientHandler(WebSocketCloseStatus closeStatus, String closeReason) {
      this.closeStatus = closeStatus;
      this.closeReason = closeReason;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.ctx = ctx;
      this.webSocketFrameFactory = webSocketFrameFactory;
      onHandshakeComplete.complete(webSocketFrameFactory);
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (!finalFragment) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-final frame: " + finalFragment));
        payload.release();
        return;
      }
      if (rsv != 0) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received frame with non-zero rsv: " + rsv));
        payload.release();
        return;
      }
      if (opcode != WebSocketProtocol.OPCODE_CLOSE) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError("received non-close frame: " + Long.toHexString(opcode)));
        payload.release();
        return;
      }

      WebSocketCloseStatus status =
          WebSocketCloseStatus.valueOf(CloseFramePayload.statusCode(payload));
      String reason = CloseFramePayload.reason(payload);
      if (!closeStatus.equals(status) || !closeReason.equals(reason)) {
        onFrameExchangeComplete.completeExceptionally(
            new AssertionError(
                "received close frame with unexpected content, status: "
                    + status
                    + ", reason: "
                    + reason));
      }

      payload.release();
      onFrameExchangeComplete.complete(null);
    }

    CompletableFuture<Void> startFramesExchange() {
      ChannelHandlerContext c = ctx;
      c.executor().execute(() -> sendFrames(c));
      return onFrameExchangeComplete;
    }

    private void sendFrames(ChannelHandlerContext c) {
      WebSocketFrameFactory factory = webSocketFrameFactory;
      ByteBuf closeFrame = factory.createCloseFrame(c.alloc(), closeStatus.code(), closeReason);
      ctx.writeAndFlush(factory.mask(closeFrame));
    }

    CompletableFuture<WebSocketFrameFactory> onHandshakeCompleted() {
      return onHandshakeComplete;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      this.ctx = ctx;
    }
  }

  static class PingPongTestServerHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof PingWebSocketFrame) {
        PingWebSocketFrame pingFrame = (PingWebSocketFrame) msg;
        ctx.writeAndFlush(new PongWebSocketFrame(pingFrame.content().retain()));
        pingFrame.release();
      } else if (msg instanceof PongWebSocketFrame) {
        PongWebSocketFrame pongFrame = (PongWebSocketFrame) msg;
        ctx.writeAndFlush(new PingWebSocketFrame(pongFrame.content().retain()));
        pongFrame.release();
      } else {
        super.channelRead(ctx, msg);
      }
    }
  }

  static class CloseTestServerHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof CloseWebSocketFrame) {
        CloseWebSocketFrame closeWebSocketFrame = (CloseWebSocketFrame) msg;
        ctx.writeAndFlush(
            new CloseWebSocketFrame(
                closeWebSocketFrame.statusCode(), closeWebSocketFrame.reasonText()));
        closeWebSocketFrame.release();
      } else {
        super.channelRead(ctx, msg);
      }
    }
  }

  static class FragmentTestServerHandler extends ChannelInboundHandlerAdapter {
    private final int fragmentSize;

    FragmentTestServerHandler(int fragmentSize) {
      this.fragmentSize = fragmentSize;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof BinaryWebSocketFrame) {
        BinaryWebSocketFrame webSocketFrame = (BinaryWebSocketFrame) msg;
        ByteBuf content = webSocketFrame.content();
        ByteBuf fragmentContent = content.readRetainedSlice(fragmentSize);
        ctx.write(new BinaryWebSocketFrame(false, 0, fragmentContent));
        while (content.readableBytes() > fragmentSize) {
          ctx.write(
              new ContinuationWebSocketFrame(false, 0, content.readRetainedSlice(fragmentSize)));
        }
        ctx.writeAndFlush(
            new ContinuationWebSocketFrame(
                true, 0, content.readRetainedSlice(content.readableBytes())));
        webSocketFrame.release();
      } else {
        super.channelRead(ctx, msg);
      }
    }
  }
}
