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

package com.jauntsdn.netty.handler.codec.http.websocketx.soaktest.client;

import com.jauntsdn.netty.handler.codec.http.websocketx.test.Security;
import com.jauntsdn.netty.handler.codec.http.websocketx.test.Transport;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);
  private static final int FRAME_BATCH_SIZE = 100;

  public static void main(String[] args) throws Exception {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);

    String host = System.getProperty("HOST", "localhost");
    int port = Integer.parseInt(System.getProperty("PORT", "8088"));
    boolean isMasked = Boolean.parseBoolean(System.getProperty("MASK", "false"));
    int totalFrames = Integer.parseInt(System.getProperty("FRAMES", "100000000" /*100m*/));
    int connections = Integer.parseInt(System.getProperty("CONNS", "4"));
    int frameSizeLimit = Integer.parseInt(System.getProperty("SIZE", "1500"));
    int framesPerConnection = totalFrames / connections;

    logger.info("\n==> http1 websocket soak test client\n");
    logger.info("\n==> remote address: {}:{}", host, port);
    logger.info("\n==> epoll available: {}", Transport.isEpollAvailable());
    logger.info("\n==> kqueue available: {}", Transport.isKqueueAvailable());
    logger.info("\n==> openssl available: {}\n", OpenSsl.isAvailable());
    logger.info("\n==> frame masking: {}\n", isMasked);
    logger.info("\n==> frame payload range: [0; {}]", frameSizeLimit);
    logger.info("\n==> total frames: {}", totalFrames);
    logger.info("\n==> connections: {}", connections);

    Transport transport = Transport.get(/*native IO*/ true);
    logger.info("\n==> io transport: {}", transport.type());
    SslContext sslContext = Security.clientLocalSslContext();

    List<byte[]> randomPayloads = randomPayloads(frameSizeLimit);
    CountDownLatch onComplete = new CountDownLatch(connections);

    Bootstrap clientBootstrap =
        new Bootstrap()
            .group(transport.eventLoopGroup())
            .channel(transport.clientChannel())
            .handler(
                new ChannelInitializer<SocketChannel>() {

                  @Override
                  protected void initChannel(SocketChannel ch) {

                    HttpClientCodec http1Codec = new HttpClientCodec();
                    HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);
                    WebSocketTestHandler webSocketHandler =
                        new WebSocketTestHandler(
                            randomPayloads,
                            onComplete,
                            FRAME_BATCH_SIZE,
                            framesPerConnection,
                            withStatsOnce(connections));

                    WebSocketClientProtocolConfig config =
                        WebSocketClientProtocolConfig.newBuilder()
                            .allowExtensions(false)
                            .allowMaskMismatch(false)
                            .performMasking(isMasked)
                            .maxFramePayloadLength(frameSizeLimit)
                            .webSocketUri("wss://" + host + ":" + port + "/echo")
                            .build();

                    WebSocketClientProtocolHandler webSocketProtocolHandler =
                        new WebSocketClientProtocolHandler(config);

                    ch.pipeline()
                        .addLast(
                            sslContext.newHandler(ch.alloc()),
                            http1Codec,
                            http1Aggregator,
                            webSocketProtocolHandler,
                            webSocketHandler);
                  }

                  private int withStatsOnce(int connectionsCount) {
                    if (!hasStats.get() && hasStats.compareAndSet(false, true)) {
                      return connectionsCount;
                    }
                    return 0;
                  }

                  final AtomicBoolean hasStats = new AtomicBoolean();
                });

    InetSocketAddress remoteAddress = new InetSocketAddress(host, port);
    ExitOnError exitOnError = new ExitOnError(connections);
    for (int i = 0; i < connections; i++) {
      clientBootstrap.connect(remoteAddress).addListener(exitOnError);
    }
    onComplete.await();
    logger.info("Test suite completed successfully...");
    System.exit(0);
  }

  static class WebSocketTestHandler extends ChannelInboundHandlerAdapter {
    static final int BATCH_TIMEOUT_MILLIS = 10_000;

    final List<byte[]> randomPayloads;
    final CountDownLatch onComplete;
    final int framesBatchSize;
    final int totalFramesCount;
    final int connectionsCount;
    final ThreadLocalRandom random;

    final Deque<ByteBuf> curBatch;
    int curFramesCount;
    ScheduledFuture<?> batchTimeout;
    ScheduledFuture<?> frameCountReporter;

    private boolean isClosed;
    private long batchStartMillis;

    WebSocketTestHandler(
        List<byte[]> randomPayloads,
        CountDownLatch onComplete,
        int framesBatchSize,
        int framesCount,
        /*report stats if positive*/ int connectionsCount) {
      this.randomPayloads = randomPayloads;
      this.onComplete = onComplete;
      this.connectionsCount = connectionsCount;
      this.random = ThreadLocalRandom.current();
      this.framesBatchSize = framesBatchSize;
      this.totalFramesCount = framesCount;
      this.curBatch = new ArrayDeque<>(framesBatchSize);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
        if (!isClosed) {
          if (connectionsCount > 0) {
            frameCountReporter =
                ctx.executor()
                    .scheduleAtFixedRate(
                        () -> {
                          double curFramesPercent =
                              (curFramesCount / (double) totalFramesCount) * 100;
                          logger.info(
                              "Processed frames estimate: {} out of {}, {} %",
                              (long) curFramesCount * connectionsCount,
                              (long) totalFramesCount * connectionsCount,
                              String.format("%.2f", curFramesPercent));
                        },
                        0,
                        5_000,
                        TimeUnit.MILLISECONDS);
          }
          sendBatch(ctx);

          batchTimeout =
              ctx.executor()
                  .scheduleAtFixedRate(
                      () -> {
                        long batchDurationMillis = System.currentTimeMillis() - batchStartMillis;
                        if (batchDurationMillis > BATCH_TIMEOUT_MILLIS) {
                          logger.info(
                              "Batch duration timeout: {} exceeded {} millis",
                              batchDurationMillis,
                              BATCH_TIMEOUT_MILLIS);
                          System.exit(1);
                        }
                      },
                      BATCH_TIMEOUT_MILLIS / 2,
                      BATCH_TIMEOUT_MILLIS / 2,
                      TimeUnit.MILLISECONDS);
        }
      }
      super.userEventTriggered(ctx, evt);
    }

    private void sendBatch(ChannelHandlerContext ctx) {
      batchStartMillis = System.currentTimeMillis();

      boolean hasPendingWrite = false;
      Channel channel = ctx.channel();
      for (int i = 0; i < framesBatchSize; i++) {
        ByteBuf payload = randomPayload();
        curBatch.addLast(payload.retainedSlice());
        if (channel.bytesBeforeUnwritable() <= payload.readableBytes()) {
          channel.writeAndFlush(new BinaryWebSocketFrame(payload));
          hasPendingWrite = false;
        } else {
          channel.write(new BinaryWebSocketFrame(payload));
          hasPendingWrite = true;
        }
      }
      if (hasPendingWrite) {
        ctx.flush();
      }
    }

    private void receiveBatch(ChannelHandlerContext ctx, BinaryWebSocketFrame webSocketFrame) {
      ByteBuf expectedPayload = curBatch.pollFirst();
      if (expectedPayload == null) {
        logger.info("Received unexpected response: no batch currently available");
        System.exit(1);
      }

      ByteBuf actualPayload = webSocketFrame.content();
      int expectedBytes = expectedPayload.readableBytes();
      int actualBytes = actualPayload.readableBytes();
      if (expectedBytes != actualBytes) {
        logger.info("Received unexpected payload, size: {} != {}", expectedBytes, actualBytes);
        System.exit(1);
      }
      int readableBytes = expectedBytes;
      while (readableBytes > 0) {
        if (readableBytes >= 8) {
          readableBytes -= 8;
          if (actualPayload.readLong() != expectedPayload.readLong()) {
            logger.info("Received payload with unexpected content for size: {}", expectedBytes);
            System.exit(1);
          }
        } else if (readableBytes >= 4) {
          readableBytes -= 4;
          if (actualPayload.readInt() != expectedPayload.readInt()) {
            logger.info("Received payload with unexpected content for size: {}", expectedBytes);
            System.exit(1);
          }
        } else {
          readableBytes -= 1;
          if (actualPayload.readByte() != expectedPayload.readByte()) {
            logger.info("Received payload with unexpected content for size: {}", expectedBytes);
            System.exit(1);
          }
        }
      }
      expectedPayload.release();

      if (curBatch.isEmpty()) {
        sendBatch(ctx);
      }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      isClosed = true;
      ScheduledFuture<?> timeout = batchTimeout;
      if (timeout != null) {
        timeout.cancel(true);
      }
      ScheduledFuture<?> framesCount = frameCountReporter;
      if (framesCount != null) {
        framesCount.cancel(true);
      }
      super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      if (!(msg instanceof BinaryWebSocketFrame)) {
        logger.info("Unexpected websocket frame received: {}", msg.getClass());
        ReferenceCountUtil.release(msg);
        System.exit(1);
      }
      BinaryWebSocketFrame binaryWebSocketFrame = (BinaryWebSocketFrame) msg;
      try {
        receiveBatch(ctx, binaryWebSocketFrame);
        if (++curFramesCount == totalFramesCount) {
          if (connectionsCount > 0) {
            logger.info(
                "Test succeeded after processing {} frames in batches of {}, finalizing test suite...",
                (long) totalFramesCount * connectionsCount,
                framesBatchSize);
          }
          ctx.close();
          onComplete.countDown();
        }
      } finally {
        binaryWebSocketFrame.release();
      }
    }

    ByteBuf randomPayload() {
      List<byte[]> payloads = randomPayloads;
      int randomIndex = random.nextInt(payloads.size());
      byte[] payload = payloads.get(randomIndex);
      return Unpooled.wrappedBuffer(payload);
    }
  }

  static List<byte[]> randomPayloads(int maxSize) {
    Random random = new Random();
    List<byte[]> res = new ArrayList<>(maxSize);
    for (int size = 0; size < maxSize; size++) {
      byte[] bytes = new byte[size];
      random.nextBytes(bytes);
      res.add(bytes);
    }
    return res;
  }

  static class ExitOnError implements GenericFutureListener<ChannelFuture> {
    final AtomicInteger connected;
    final int connections;

    ExitOnError(int connections) {
      this.connections = connections;
      this.connected = new AtomicInteger();
    }

    @Override
    public void operationComplete(ChannelFuture channelFuture) {
      Throwable cause = channelFuture.cause();
      if (cause == null) {
        if (connected.incrementAndGet() == connections) {
          logger.info("Connections succeeded: {}", connections);
        }
      } else {
        logger.info("Connection error - {}:{}", cause.getClass(), cause.getMessage());
        System.exit(1);
      }
    }
  }
}
