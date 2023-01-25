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
    Channel s = server = nettyServer(new BinaryFramesTestServerHandler(), mask, false);
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
  @MethodSource("maskingArgs")
  @ParameterizedTest
  void allSizeBinaryFramesDefaultDecoder(
      boolean mask, Class<?> webSocketFrameFactoryType, Class<ChannelHandler> webSocketDecoderType)
      throws Exception {
    int maxFrameSize = DEFAULT_CODEC_MAX_FRAME_SIZE;
    Channel s = server = nettyServer(new BinaryFramesTestServerHandler(), mask, false);
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

  @Timeout(15)
  @Test
  void binaryFramesSmallDecoder() throws Exception {
    int maxFrameSize = SMALL_CODEC_MAX_FRAME_SIZE;
    Channel s = server = nettyServer(new BinaryFramesTestServerHandler(), false, false);
    BinaryFramesTestClientHandler clientHandler = new BinaryFramesTestClientHandler(maxFrameSize);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), false, false, maxFrameSize, clientHandler);

    WebSocketFrameFactory webSocketFrameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(webSocketFrameFactory)
        .isExactlyInstanceOf(NonMaskingWebSocketEncoder.FrameFactory.class);
    Assertions.assertThat(client.pipeline().get(SmallWebSocketDecoder.class)).isNotNull();

    CompletableFuture<Void> onCompleted = clientHandler.startFramesExchange();
    onCompleted.join();
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
  @Test
  void textFramesSmallDecoder() throws Exception {
    int maxFrameSize = SMALL_CODEC_MAX_FRAME_SIZE;
    char content = 'a';
    Channel s =
        server = nettyServer(new TextFramesTestServerHandler(maxFrameSize, content), false, false);
    TextFramesTestClientHandler clientHandler =
        new TextFramesTestClientHandler(maxFrameSize, content);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), false, false, maxFrameSize, clientHandler);

    WebSocketFrameFactory webSocketFrameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(webSocketFrameFactory)
        .isExactlyInstanceOf(NonMaskingWebSocketEncoder.FrameFactory.class);
    Assertions.assertThat(client.pipeline().get(SmallWebSocketDecoder.class)).isNotNull();

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
  @Test
  void pingFramesSmallDecoder() throws Exception {
    int maxFrameSize = SMALL_CODEC_MAX_FRAME_SIZE;
    Channel s = server = nettyServer(new PingPongTestServerHandler(), false, false);
    PingFramesTestClientHandler clientHandler =
        new PingFramesTestClientHandler(maxFrameSize, (byte) 0xFE);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), false, false, maxFrameSize, clientHandler);

    WebSocketFrameFactory webSocketFrameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(webSocketFrameFactory)
        .isExactlyInstanceOf(NonMaskingWebSocketEncoder.FrameFactory.class);
    Assertions.assertThat(client.pipeline().get(SmallWebSocketDecoder.class)).isNotNull();

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
  @Test
  void pongFramesSmallDecoder() throws Exception {
    int maxFrameSize = SMALL_CODEC_MAX_FRAME_SIZE;
    Channel s = server = nettyServer(new PingPongTestServerHandler(), false, false);
    PongFramesTestClientHandler clientHandler =
        new PongFramesTestClientHandler(maxFrameSize, (byte) 0xFE);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), false, false, maxFrameSize, clientHandler);

    WebSocketFrameFactory webSocketFrameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(webSocketFrameFactory)
        .isExactlyInstanceOf(NonMaskingWebSocketEncoder.FrameFactory.class);
    Assertions.assertThat(client.pipeline().get(SmallWebSocketDecoder.class)).isNotNull();

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
  @Test
  void closeFramesSmallDecoder() throws Exception {
    int maxFrameSize = SMALL_CODEC_MAX_FRAME_SIZE;
    Channel s = server = nettyServer(new CloseTestServerHandler(), false, false);
    CloseFramesTestClientHandler clientHandler =
        new CloseFramesTestClientHandler(WebSocketCloseStatus.NORMAL_CLOSURE, "NORMAL_CLOSURE");
    Channel client =
        webSocketCallbacksClient(s.localAddress(), false, false, maxFrameSize, clientHandler);

    WebSocketFrameFactory webSocketFrameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(webSocketFrameFactory)
        .isExactlyInstanceOf(NonMaskingWebSocketEncoder.FrameFactory.class);
    Assertions.assertThat(client.pipeline().get(SmallWebSocketDecoder.class)).isNotNull();

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
    FragmentationFramesTestClientHandler clientHandler =
        new FragmentationFramesTestClientHandler(3333);
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
  void fragmentSmallDecoder() throws Exception {
    int maxFrameSize = SMALL_CODEC_MAX_FRAME_SIZE;

    Channel s = server = nettyServer(new FragmentTestServerHandler(33), false, false);
    FragmentationFramesTestClientHandler clientHandler =
        new FragmentationFramesTestClientHandler(70);
    Channel client =
        webSocketCallbacksClient(s.localAddress(), false, false, maxFrameSize, clientHandler);

    WebSocketFrameFactory webSocketFrameFactory = clientHandler.onHandshakeCompleted().join();
    Assertions.assertThat(webSocketFrameFactory)
        .isExactlyInstanceOf(NonMaskingWebSocketEncoder.FrameFactory.class);
    Assertions.assertThat(client.pipeline().get(SmallWebSocketDecoder.class)).isNotNull();

    CompletableFuture<Void> onComplete = clientHandler.startFramesExchange();
    onComplete.join();
    client.close();
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
              .withUTF8Validator(false)
              .allowExtensions(false)
              .maxFramePayloadLength(65535)
              .build();

      WebSocketServerProtocolHandler webSocketProtocolHandler =
          new WebSocketServerProtocolHandler("/test", null, false, false, 15_000, decoderConfig);

      ch.pipeline()
          .addLast(http1Codec, http1Aggregator, webSocketProtocolHandler, webSocketHandler);
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

  static class FragmentationFramesTestClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    final CompletableFuture<WebSocketFrameFactory> onHandshakeComplete = new CompletableFuture<>();
    final CompletableFuture<Void> onFrameExchangeComplete = new CompletableFuture<>();
    final int frameSize;
    WebSocketFrameFactory webSocketFrameFactory;
    volatile ChannelHandlerContext ctx;

    FragmentationFramesTestClientHandler(int frameSize) {
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

  static class BinaryFramesTestServerHandler extends ChannelInboundHandlerAdapter {
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
