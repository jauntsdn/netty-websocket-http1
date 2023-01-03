/*
 * Copyright 2020 - present Maksym Ostroverkhov.
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

package io.netty.handler.codec.http.websocketx.perftest.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.perftest.Security;
import io.netty.handler.codec.http.websocketx.perftest.Transport;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakDetector;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws Exception {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);

    String host = System.getProperty("HOST", "localhost");
    int port = Integer.parseInt(System.getProperty("PORT", "8088"));
    boolean isNativeTransport =
        Boolean.parseBoolean(System.getProperty("NATIVE_TRANSPORT", "true"));
    boolean isEncrypted = Boolean.parseBoolean(System.getProperty("ENCRYPT", "true"));
    boolean isOpensslAvailable = OpenSsl.isAvailable();
    boolean isEpollAvailable = Transport.isEpollAvailable();

    logger.info("\n==> http1 built-in codec websocket test server\n");
    logger.info("\n==> bind address: {}:{}", host, port);
    logger.info("\n==> native transport: {}", isNativeTransport);
    logger.info("\n==> epoll available: {}", isEpollAvailable);
    logger.info("\n==> openssl available: {}", isOpensslAvailable);
    logger.info("\n==> encryption: {}\n", isEncrypted);

    Transport transport = Transport.get(isNativeTransport);
    SslContext sslContext = isEncrypted ? Security.serverSslContext() : null;

    ServerBootstrap bootstrap = new ServerBootstrap();
    Channel server =
        bootstrap
            .group(transport.eventLoopGroup())
            .channel(transport.serverChannel())
            .childHandler(new ConnectionAcceptor(sslContext))
            .bind(host, port)
            .sync()
            .channel();
    logger.info("\n==> Server is listening on {}:{}", host, port);
    server.closeFuture().sync();
  }

  private static class ConnectionAcceptor extends ChannelInitializer<SocketChannel> {
    private final SslContext sslContext;
    private final WebSocketDecoderConfig webSocketDecoderConfig;

    ConnectionAcceptor(SslContext sslContext) {
      this.sslContext = sslContext;
      this.webSocketDecoderConfig =
          WebSocketDecoderConfig.newBuilder()
              .allowExtensions(false)
              .allowMaskMismatch(true)
              .expectMaskedFrames(true)
              .maxFramePayloadLength(65_535)
              .withUTF8Validator(false)
              .build();
    }

    @Override
    protected void initChannel(SocketChannel ch) {
      HttpServerCodec http1Codec = new HttpServerCodec();
      HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);
      WebSocketServerCallbacksHandler webSocketHandler = new WebSocketServerCallbacksHandler();

      io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
          webSocketProtocolHandler =
              new io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler(
                  "/echo", null, false, true, 15_000, webSocketDecoderConfig);

      ChannelPipeline pipeline = ch.pipeline();
      if (sslContext != null) {
        SslHandler sslHandler = sslContext.newHandler(ch.alloc());
        pipeline.addLast(sslHandler);
      }
      pipeline.addLast(http1Codec, http1Aggregator, webSocketProtocolHandler, webSocketHandler);
    }
  }

  private static class WebSocketServerCallbacksHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      if (!(msg instanceof BinaryWebSocketFrame)) {
        ReferenceCountUtil.release(msg);
        logger.info("received non-binary websocket frame: {}", msg);
      }
      ByteBuf payload = ((BinaryWebSocketFrame) msg).content();
      ByteBuf outPayload = ctx.alloc().buffer(payload.readableBytes()).writeBytes(payload);
      payload.release();
      ctx.write(new BinaryWebSocketFrame(outPayload), ctx.voidPromise());
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
      ctx.flush();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
      if (!ctx.channel().isWritable()) {
        ctx.flush();
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      logger.info("Unexpected websocket error", cause);
      ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {}
  }
}
