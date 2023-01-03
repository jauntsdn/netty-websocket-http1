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

package com.jauntsdn.netty.handler.codec.http.websocketx.perftest.client;

import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketCallbacksHandler;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameFactory;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameListener;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketProtocol;
import com.jauntsdn.netty.handler.codec.http.websocketx.test.Security;
import com.jauntsdn.netty.handler.codec.http.websocketx.test.Transport;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ResourceLeakDetector;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);

    String host = System.getProperty("HOST", "localhost");
    int port = Integer.parseInt(System.getProperty("PORT", "8088"));
    int duration = Integer.parseInt(System.getProperty("DURATION", "600"));
    boolean isNativeTransport = Boolean.parseBoolean(System.getProperty("NATIVE", "true"));
    boolean isEncrypted = Boolean.parseBoolean(System.getProperty("ENCRYPT", "true"));
    boolean isMasked = Boolean.parseBoolean(System.getProperty("MASK", "false"));
    boolean isTotalFrames = Boolean.parseBoolean(System.getProperty("TOTAL", "false"));
    int frameSize = Integer.parseInt(System.getProperty("FRAME", "64"));
    int outboundFramesWindow = Integer.parseInt(System.getProperty("WINDOW", "2222"));

    boolean isOpensslAvailable = OpenSsl.isAvailable();
    boolean isEpollAvailable = Transport.isEpollAvailable();
    boolean isKqueueAvailable = Transport.isKqueueAvailable();

    logger.info("\n==> http1 websocket test client\n");
    logger.info("\n==> remote address: {}:{}", host, port);
    logger.info("\n==> duration: {}", duration);
    logger.info("\n==> native transport: {}", isNativeTransport);
    logger.info("\n==> epoll available: {}", isEpollAvailable);
    logger.info("\n==> kqueue available: {}", isKqueueAvailable);
    logger.info("\n==> openssl available: {}\n", isOpensslAvailable);
    logger.info("\n==> encryption: {}\n", isEncrypted);
    logger.info("\n==> frame masking: {}\n", isMasked);
    logger.info("\n==> frame payload size: {}", frameSize);
    logger.info("\n==> outbound frames window: {}", outboundFramesWindow);

    Transport transport = Transport.get(isNativeTransport);
    logger.info("\n==> io transport: {}", transport.type());
    SslContext sslContext = isEncrypted ? Security.clientLocalSslContext() : null;

    List<ByteBuf> framesPayload = framesPayload(1000, frameSize);
    FrameCounters frameCounters = new FrameCounters(isTotalFrames);

    Channel channel =
        new Bootstrap()
            .group(transport.eventLoopGroup())
            .channel(transport.clientChannel())
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {

                    HttpClientCodec http1Codec = new HttpClientCodec();
                    HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);
                    WebSocketCallbacksHandler webSocketHandler =
                        new WebSocketClientHandler(
                            frameCounters,
                            framesPayload,
                            ThreadLocalRandom.current(),
                            outboundFramesWindow);

                    WebSocketClientProtocolHandler webSocketProtocolHandler =
                        WebSocketClientProtocolHandler.create()
                            .path("/echo")
                            .mask(isMasked)
                            .allowMaskMismatch(true)
                            .maxFramePayloadLength(65_535)
                            .webSocketHandler(webSocketHandler)
                            .build();

                    ChannelPipeline pipeline = ch.pipeline();
                    if (sslContext != null) {
                      SslHandler sslHandler = sslContext.newHandler(ch.alloc());
                      pipeline.addLast(sslHandler);
                    }
                    pipeline.addLast(http1Codec, http1Aggregator, webSocketProtocolHandler);
                  }
                })
            .connect(new InetSocketAddress(host, port))
            .sync()
            .channel();

    int warmupMillis = 5000;
    logger.info("==> warming up for {} millis...", warmupMillis);
    channel
        .eventLoop()
        .schedule(
            () -> {
              logger.info("==> warm up completed");
              frameCounters.start();
              channel
                  .eventLoop()
                  .scheduleAtFixedRate(
                      new StatsReporter(frameCounters, frameSize),
                      1000,
                      1000,
                      TimeUnit.MILLISECONDS);
            },
            warmupMillis,
            TimeUnit.MILLISECONDS);

    channel.closeFuture().sync();
    logger.info("Client terminated");
  }

  private static class FrameCounters {
    private final Recorder histogram;
    private int frameCount;
    private boolean isStarted;

    public FrameCounters(boolean totalFrames) {
      histogram = totalFrames ? null : new Recorder(36000000000L, 3);
    }

    private long totalFrameCount;

    public void start() {
      isStarted = true;
    }

    public void countFrame(long timestamp) {
      if (!isStarted) {
        return;
      }

      if (histogram == null) {
        totalFrameCount++;
      } else {
        frameCount++;
        if (timestamp >= 0) {
          histogram.recordValue(System.nanoTime() - timestamp);
        }
      }
    }

    public Recorder histogram() {
      return histogram;
    }

    public int frameCount() {
      int count = frameCount;
      frameCount = 0;
      return count;
    }

    public long totalFrameCount() {
      return totalFrameCount;
    }
  }

  private static class StatsReporter implements Runnable {
    private final FrameCounters frameCounters;
    private final int frameSize;
    private int iteration;

    public StatsReporter(FrameCounters frameCounters, int frameSize) {
      this.frameCounters = frameCounters;
      this.frameSize = frameSize;
    }

    @Override
    public void run() {
      Recorder histogram = frameCounters.histogram();
      if (histogram != null) {
        Histogram h = histogram.getIntervalHistogram();
        long p50 = h.getValueAtPercentile(50) / 1000;
        long p95 = h.getValueAtPercentile(95) / 1000;
        long p99 = h.getValueAtPercentile(99) / 1000;
        int count = frameCounters.frameCount();

        logger.info("p50 => {} micros", p50);
        logger.info("p95 => {} micros", p95);
        logger.info("p99 => {} micros", p99);
        logger.info("throughput => {} messages", count);
        logger.info("throughput => {} kbytes\n", count * frameSize / (float) 1024);
      } else {
        if (++iteration % 10 == 0) {
          logger.info(
              "total frames, iteration {} => {}", iteration, frameCounters.totalFrameCount());
        }
      }
    }
  }

  static class WebSocketClientHandler implements WebSocketCallbacksHandler, WebSocketFrameListener {

    private final FrameCounters frameCounters;
    private final List<ByteBuf> dataList;
    private final Random random;
    private final int window;
    private int sendIndex;
    private boolean isClosed;
    private FrameWriter frameWriter;

    WebSocketClientHandler(
        FrameCounters frameCounters, List<ByteBuf> dataList, Random random, int window) {
      this.frameCounters = frameCounters;
      this.dataList = dataList;
      this.random = random;
      this.window = window;
    }

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory frameFactory) {
      frameWriter = new FrameWriter(ctx, frameFactory, window);
      return this;
    }

    @Override
    public void onOpen(ChannelHandlerContext ctx) {
      frameWriter.startWrite();
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
      isClosed = true;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      if (opcode != WebSocketProtocol.OPCODE_BINARY) {
        payload.release();
        return;
      }

      long timeStamp = payload.readLong();
      frameCounters.countFrame(timeStamp);
      payload.release();
      frameWriter.tryContinueWrite();
    }

    @Override
    public void onChannelReadComplete(ChannelHandlerContext ctx) {}

    @Override
    public void onUserEventTriggered(ChannelHandlerContext ctx, Object evt) {}

    @Override
    public void onChannelWritabilityChanged(ChannelHandlerContext ctx) {
      Channel ch = ctx.channel();
      if (!ch.isWritable()) {
        ch.flush();
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (!isClosed) {
        isClosed = true;
        logger.error("Channel error", cause);
        ctx.close();
      }
    }

    class FrameWriter {
      private final ChannelHandlerContext ctx;
      private final WebSocketFrameFactory frameFactory;
      private final int window;
      private int queued;

      FrameWriter(ChannelHandlerContext ctx, WebSocketFrameFactory frameFactory, int window) {
        this.ctx = ctx;
        this.frameFactory = frameFactory;
        this.window = window;
      }

      void startWrite() {
        if (isClosed) {
          return;
        }
        int cur = queued;
        int w = window;
        int writeCount = w - cur;
        ChannelHandlerContext c = ctx;
        for (int i = 0; i < writeCount; i++) {
          c.write(webSocketFrame(c), c.voidPromise());
        }
        queued = w;
        c.flush();
      }

      void tryContinueWrite() {
        int q = --queued;
        if (q <= window / 2) {
          startWrite();
        }
      }

      ByteBuf webSocketFrame(ChannelHandlerContext ctx) {
        List<ByteBuf> dl = dataList;
        int dataIndex = random.nextInt(dl.size());
        ByteBuf data = dl.get(dataIndex);
        int index = sendIndex++;
        int dataSize = data.readableBytes();

        WebSocketFrameFactory factory = frameFactory;

        ByteBuf frame = factory.createBinaryFrame(ctx.alloc(), Long.BYTES + dataSize);
        frame.writeLong(index % 50_000 == 0 ? System.nanoTime() : -1).writeBytes(data, 0, dataSize);

        return factory.mask(frame);
      }
    }
  }

  private static List<ByteBuf> framesPayload(int count, int size) {
    Random random = new Random();
    List<ByteBuf> data = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      byte[] bytes = new byte[size];
      random.nextBytes(bytes);
      data.add(Unpooled.wrappedBuffer(bytes));
    }
    return data;
  }
}
