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
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class WebSocketValidationTest {
  Channel server;

  @AfterEach
  void tearDown() throws Exception {
    Channel s = server;
    if (s != null) {
      s.close();
    }
  }

  @Test
  void serverBuilderMinimalConfigIsValid() {
    WebSocketServerProtocolHandler serverProtocolHandler =
        WebSocketServerProtocolHandler.create()
            .webSocketCallbacksHandler(
                (ctx, webSocketFrameFactory) -> {
                  throw new AssertionError("never called");
                })
            .build();
  }

  @Test
  void clientBuilderMinimalConfigIsValid() {
    WebSocketClientProtocolHandler clientProtocolHandler =
        WebSocketClientProtocolHandler.create()
            .webSocketHandler(
                (ctx, webSocketFrameFactory) -> {
                  throw new AssertionError("never called");
                })
            .build();
  }

  @Timeout(15)
  @Test
  void frameSizeLimit() throws Exception {
    ValidationTestServerHandler serverHandler = new ValidationTestServerHandler();
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

  @Timeout(15)
  @ValueSource(
      bytes = {
        WebSocketProtocol.OPCODE_PING,
        WebSocketProtocol.OPCODE_PONG,
        WebSocketProtocol.OPCODE_CLOSE
      })
  @ParameterizedTest
  void controlFrameSizeLimit(byte opcode) throws Exception {
    ValidationTestServerHandler serverHandler = new ValidationTestServerHandler();
    Channel s = server = testServer("localhost", 0, decoderConfig(65_535), serverHandler);
    ControlFrameSizeLimitClientHandler clientHandler =
        new ControlFrameSizeLimitClientHandler(opcode);
    Channel client = testClient(s.localAddress(), 65_535, clientHandler);
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

  @Timeout(15)
  @Test
  void frameWithExtensions() throws Exception {
    ValidationTestServerHandler serverHandler = new ValidationTestServerHandler();
    Channel s = server = testServer("localhost", 0, decoderConfig(65_535), serverHandler);
    ExtensionFrameClientHandler clientHandler = new ExtensionFrameClientHandler();
    Channel client = testClient(s.localAddress(), 65_535, clientHandler);
    serverHandler.onClose.join();
    clientHandler.onClose.join();

    Throwable serverInboundException = serverHandler.inboundException;
    Assertions.assertThat(serverInboundException).isNotNull();
    Assertions.assertThat(serverInboundException)
        .isInstanceOf(CorruptedWebSocketFrameException.class);
    WebSocketCloseStatus closeStatus =
        ((CorruptedWebSocketFrameException) serverInboundException).closeStatus();
    Assertions.assertThat(closeStatus.code()).isEqualTo(WebSocketCloseStatus.PROTOCOL_ERROR.code());
    Assertions.assertThat(serverHandler.framesReceived).isEqualTo(0);
    Assertions.assertThat(clientHandler.nonCloseFrames).isEqualTo(0);
    Set<ByteBuf> closeFrames = clientHandler.closeFrames;
    try {
      Assertions.assertThat(closeFrames.size()).isEqualTo(1);
      ByteBuf closeFramePayload = closeFrames.iterator().next();
      Assertions.assertThat(WebSocketFrameListener.CloseFramePayload.statusCode(closeFramePayload))
          .isEqualTo(WebSocketCloseStatus.PROTOCOL_ERROR.code());
    } finally {
      closeFrames.forEach(ByteBuf::release);
    }
  }

  @Timeout(15)
  @Test
  void invalidFragmentStart() throws Exception {
    ValidationTestServerHandler serverHandler = new ValidationTestServerHandler();
    Channel s = server = testServer("localhost", 0, decoderConfig(65_535), serverHandler);
    FragmentStartClientHandler clientHandler = new FragmentStartClientHandler();
    Channel client = testClient(s.localAddress(), 65_535, clientHandler);
    serverHandler.onClose.join();
    clientHandler.onClose.join();

    Throwable serverInboundException = serverHandler.inboundException;
    Assertions.assertThat(serverInboundException).isNotNull();
    Assertions.assertThat(serverInboundException)
        .isInstanceOf(CorruptedWebSocketFrameException.class);
    WebSocketCloseStatus closeStatus =
        ((CorruptedWebSocketFrameException) serverInboundException).closeStatus();
    Assertions.assertThat(closeStatus.code()).isEqualTo(WebSocketCloseStatus.PROTOCOL_ERROR.code());
    Assertions.assertThat(serverHandler.framesReceived).isEqualTo(0);
    Assertions.assertThat(clientHandler.nonCloseFrames).isEqualTo(0);
    Set<ByteBuf> closeFrames = clientHandler.closeFrames;
    try {
      Assertions.assertThat(closeFrames.size()).isEqualTo(1);
      ByteBuf closeFramePayload = closeFrames.iterator().next();
      Assertions.assertThat(WebSocketFrameListener.CloseFramePayload.statusCode(closeFramePayload))
          .isEqualTo(WebSocketCloseStatus.PROTOCOL_ERROR.code());
    } finally {
      closeFrames.forEach(ByteBuf::release);
    }
  }

  @Test
  void invalidFragmentContinuation() throws Exception {
    ValidationTestServerHandler serverHandler = new ValidationTestServerHandler();
    Channel s = server = testServer("localhost", 0, decoderConfig(65_535), serverHandler);
    FragmentContinuationClientHandler clientHandler = new FragmentContinuationClientHandler();
    Channel client = testClient(s.localAddress(), 65_535, clientHandler);
    serverHandler.onClose.join();
    clientHandler.onClose.join();

    Throwable serverInboundException = serverHandler.inboundException;
    Assertions.assertThat(serverInboundException).isNotNull();
    Assertions.assertThat(serverInboundException)
        .isInstanceOf(CorruptedWebSocketFrameException.class);
    WebSocketCloseStatus closeStatus =
        ((CorruptedWebSocketFrameException) serverInboundException).closeStatus();
    Assertions.assertThat(closeStatus.code()).isEqualTo(WebSocketCloseStatus.PROTOCOL_ERROR.code());
    Assertions.assertThat(serverHandler.framesReceived).isEqualTo(1);
    Assertions.assertThat(clientHandler.nonCloseFrames).isEqualTo(0);
    Set<ByteBuf> closeFrames = clientHandler.closeFrames;
    try {
      Assertions.assertThat(closeFrames.size()).isEqualTo(1);
      ByteBuf closeFramePayload = closeFrames.iterator().next();
      Assertions.assertThat(WebSocketFrameListener.CloseFramePayload.statusCode(closeFramePayload))
          .isEqualTo(WebSocketCloseStatus.PROTOCOL_ERROR.code());
    } finally {
      closeFrames.forEach(ByteBuf::release);
    }
  }

  @Test
  void invalidFragmentCompletion() throws Exception {
    ValidationTestServerHandler serverHandler = new ValidationTestServerHandler();
    Channel s = server = testServer("localhost", 0, decoderConfig(65_535), serverHandler);
    FragmentCompletionClientHandler clientHandler = new FragmentCompletionClientHandler();
    Channel client = testClient(s.localAddress(), 65_535, clientHandler);
    serverHandler.onClose.join();
    clientHandler.onClose.join();

    Throwable serverInboundException = serverHandler.inboundException;
    Assertions.assertThat(serverInboundException).isNotNull();
    Assertions.assertThat(serverInboundException)
        .isInstanceOf(CorruptedWebSocketFrameException.class);
    WebSocketCloseStatus closeStatus =
        ((CorruptedWebSocketFrameException) serverInboundException).closeStatus();
    Assertions.assertThat(closeStatus.code()).isEqualTo(WebSocketCloseStatus.PROTOCOL_ERROR.code());
    Assertions.assertThat(serverHandler.framesReceived).isEqualTo(1);
    Assertions.assertThat(clientHandler.nonCloseFrames).isEqualTo(0);
    Set<ByteBuf> closeFrames = clientHandler.closeFrames;
    try {
      Assertions.assertThat(closeFrames.size()).isEqualTo(1);
      ByteBuf closeFramePayload = closeFrames.iterator().next();
      Assertions.assertThat(WebSocketFrameListener.CloseFramePayload.statusCode(closeFramePayload))
          .isEqualTo(WebSocketCloseStatus.PROTOCOL_ERROR.code());
    } finally {
      closeFrames.forEach(ByteBuf::release);
    }
  }

  @Test
  void utf8Validator() {
    String ascii = "Are those shy Eurasian footwear, cowboy chaps, or jolly earthmoving headgear";
    String utf8 = "Чуєш їх, доцю, га? Кумедна ж ти, прощайся без ґольфів!";
    List<ByteBuf> asciiList = stringList(ByteBufAllocator.DEFAULT, ascii);
    List<ByteBuf> utf8List = stringList(ByteBufAllocator.DEFAULT, utf8);
    try {
      WebSocketFrameListener.Utf8FrameValidator validator =
          WebSocketFrameListener.Utf8FrameValidator.create();
      for (ByteBuf byteBuf : asciiList) {
        Assertions.assertThat(validator.validateTextFrame(byteBuf)).isTrue();
      }
      for (ByteBuf byteBuf : utf8List) {
        Assertions.assertThat(validator.validateTextFrame(byteBuf)).isTrue();
      }
    } finally {
      for (ByteBuf byteBuf : asciiList) {
        byteBuf.release();
      }
      for (ByteBuf byteBuf : utf8List) {
        byteBuf.release();
      }
    }
  }

  static List<ByteBuf> stringList(ByteBufAllocator allocator, String string) {
    int length = string.length();
    List<ByteBuf> list = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      String substring = string.substring(0, i + 1);
      ByteBuf byteBuf = ByteBufUtil.writeUtf8(allocator, substring);
      list.add(byteBuf);
    }
    return list;
  }

  @Test
  void utf8TextFrameValidator() {
    ByteBufAllocator alloc = ByteBufAllocator.DEFAULT;
    List<ByteBuf> utf8 =
        Arrays.asList(
            ByteBufUtil.writeUtf8(alloc, "ab"),
            ByteBufUtil.writeUtf8(alloc, "c"),
            ByteBufUtil.writeUtf8(alloc, "def"),
            ByteBufUtil.writeUtf8(alloc, "ghijk"),
            ByteBufUtil.writeUtf8(alloc, "lmn"));
    ByteBuf nonUtf8 = alloc.buffer(2).writeByte(0xc3).writeByte(0x28);

    WebSocketFrameListener.Utf8FrameValidator validator =
        WebSocketFrameListener.Utf8FrameValidator.create();

    try {
      Assertions.assertThat(validator.validateTextFrame(utf8.get(0))).isTrue();
      Assertions.assertThat(validator.state).isEqualTo(0);
      Assertions.assertThat(validator.codep).isEqualTo(0);
      Assertions.assertThat(validator.validateTextFragmentStart(utf8.get(1))).isTrue();
      Assertions.assertThat(validator.validateFragmentContinuation(utf8.get(2))).isTrue();
      Assertions.assertThat(validator.validateFragmentEnd(utf8.get(3))).isTrue();
      Assertions.assertThat(validator.state).isEqualTo(0);
      Assertions.assertThat(validator.codep).isEqualTo(0);
      Assertions.assertThat(validator.validateTextFrame(utf8.get(4))).isTrue();
      Assertions.assertThat(validator.validateTextFrame(nonUtf8)).isFalse();
    } finally {
      for (ByteBuf string : utf8) {
        string.release();
      }
      nonUtf8.release();
    }
  }

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

  static class ExtensionFrameClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    public static final int PAYLOAD_SIZE = 42;
    final CompletableFuture<Void> onClose = new CompletableFuture<>();
    final Set<ByteBuf> closeFrames = ConcurrentHashMap.newKeySet();
    volatile int nonCloseFrames;
    WebSocketFrameFactory webSocketFrameFactory;

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      return this;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      WebSocketFrameFactory factory = webSocketFrameFactory;
      ByteBuf controlFrame = factory.createBinaryFrame(ctx.alloc(), PAYLOAD_SIZE);
      controlFrame.setByte(0, controlFrame.getByte(0) | 0b0100_0000);
      byte[] payloadBytes = new byte[PAYLOAD_SIZE];
      ThreadLocalRandom.current().nextBytes(payloadBytes);
      controlFrame.writeBytes(payloadBytes);
      ctx.writeAndFlush(factory.mask(controlFrame));
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

  static class FragmentStartClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    public static final int PAYLOAD_SIZE = 127;
    final CompletableFuture<Void> onClose = new CompletableFuture<>();
    final Set<ByteBuf> closeFrames = ConcurrentHashMap.newKeySet();
    volatile int nonCloseFrames;
    WebSocketFrameFactory webSocketFrameFactory;

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      return this;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      WebSocketFrameFactory factory = webSocketFrameFactory;
      ByteBuf fragmentFrame = factory.createBinaryFrame(ctx.alloc(), PAYLOAD_SIZE);
      fragmentFrame.setByte(0, 0);
      byte[] payloadBytes = new byte[PAYLOAD_SIZE];
      ThreadLocalRandom.current().nextBytes(payloadBytes);
      fragmentFrame.writeBytes(payloadBytes);
      ctx.writeAndFlush(factory.mask(fragmentFrame));
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

  static class FragmentContinuationClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    public static final int PAYLOAD_SIZE = 127;
    final CompletableFuture<Void> onClose = new CompletableFuture<>();
    final Set<ByteBuf> closeFrames = ConcurrentHashMap.newKeySet();
    volatile int nonCloseFrames;
    WebSocketFrameFactory webSocketFrameFactory;

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      return this;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      byte[] payloadBytes = new byte[PAYLOAD_SIZE];
      ThreadLocalRandom.current().nextBytes(payloadBytes);
      WebSocketFrameFactory factory = webSocketFrameFactory;

      ByteBuf fragmentStartFrame = factory.createBinaryFrame(ctx.alloc(), PAYLOAD_SIZE);
      fragmentStartFrame.setByte(0, fragmentStartFrame.getByte(0) & 0b0111_1111);
      fragmentStartFrame.writeBytes(payloadBytes);
      ctx.write(factory.mask(fragmentStartFrame));

      ByteBuf fragmentContFrame = factory.createBinaryFrame(ctx.alloc(), PAYLOAD_SIZE);
      fragmentContFrame.setByte(0, fragmentContFrame.getByte(0) & 0b0111_1111);
      fragmentContFrame.writeBytes(payloadBytes);
      ctx.writeAndFlush(factory.mask(fragmentContFrame));
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

  static class FragmentCompletionClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    public static final int PAYLOAD_SIZE = 127;
    final CompletableFuture<Void> onClose = new CompletableFuture<>();
    final Set<ByteBuf> closeFrames = ConcurrentHashMap.newKeySet();
    volatile int nonCloseFrames;
    WebSocketFrameFactory webSocketFrameFactory;

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory) {
      this.webSocketFrameFactory = webSocketFrameFactory;
      return this;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      byte[] payloadBytes = new byte[PAYLOAD_SIZE];
      ThreadLocalRandom.current().nextBytes(payloadBytes);
      WebSocketFrameFactory factory = webSocketFrameFactory;

      ByteBuf fragmentStartFrame = factory.createBinaryFrame(ctx.alloc(), PAYLOAD_SIZE);
      fragmentStartFrame.setByte(0, fragmentStartFrame.getByte(0) & 0b0111_1111);
      fragmentStartFrame.writeBytes(payloadBytes);
      ctx.write(factory.mask(fragmentStartFrame));

      ByteBuf fragmentContFrame = factory.createBinaryFrame(ctx.alloc(), PAYLOAD_SIZE);
      fragmentContFrame.writeBytes(payloadBytes);
      ctx.writeAndFlush(factory.mask(fragmentContFrame));
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

  static class ControlFrameSizeLimitClientHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    public static final int PAYLOAD_SIZE = 127;
    final CompletableFuture<Void> onClose = new CompletableFuture<>();
    final Set<ByteBuf> closeFrames = ConcurrentHashMap.newKeySet();
    final byte opcode;
    volatile int nonCloseFrames;
    WebSocketFrameFactory webSocketFrameFactory;

    ControlFrameSizeLimitClientHandler(byte opcode) {
      this.opcode = opcode;
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
      ByteBuf controlFrame = factory.createBinaryFrame(ctx.alloc(), PAYLOAD_SIZE);
      controlFrame.setByte(0, controlFrame.getByte(0) & 0xF0 | opcode);
      if (opcode == WebSocketProtocol.OPCODE_CLOSE) {
        String closeMsg = String.join("", Collections.nCopies(25, "close"));
        controlFrame
            .writeShort(WebSocketCloseStatus.NORMAL_CLOSURE.code())
            .writeCharSequence(closeMsg, StandardCharsets.UTF_8);
      } else {
        byte[] payloadBytes = new byte[PAYLOAD_SIZE];
        ThreadLocalRandom.current().nextBytes(payloadBytes);
        controlFrame.writeBytes(payloadBytes);
      }
      ctx.writeAndFlush(factory.mask(controlFrame));
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

  static class ValidationTestServerHandler
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
      //noinspection NonAtomicOperationOnVolatileField written from single thread
      framesReceived++;
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
