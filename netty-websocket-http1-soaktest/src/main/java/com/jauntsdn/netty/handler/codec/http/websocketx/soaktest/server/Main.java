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

package com.jauntsdn.netty.handler.codec.http.websocketx.soaktest.server;

import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketCallbacksHandler;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameFactory;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketFrameListener;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketProtocol;
import com.jauntsdn.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import com.jauntsdn.netty.handler.codec.http.websocketx.test.Security;
import com.jauntsdn.netty.handler.codec.http.websocketx.test.Transport;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
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
    int frameSizeLimit = Integer.parseInt(System.getProperty("SIZE", "65535"));
    boolean expectMasked = Boolean.parseBoolean(System.getProperty("MASKED", "false"));
    boolean maskMismatch = !Boolean.parseBoolean(System.getProperty("STRICT", "false"));
    String keyStoreFile = System.getProperty("KEYSTORE", "localhost.p12");
    String keyStorePassword = System.getProperty("KEYSTORE_PASS", "localhost");

    boolean isOpensslAvailable = OpenSsl.isAvailable();
    boolean isEpollAvailable = Transport.isEpollAvailable();
    boolean isKqueueAvailable = Transport.isKqueueAvailable();

    logger.info("\n==> http1 websocket soak test server\n");
    logger.info("\n==> bind address: {}:{}", host, port);
    logger.info("\n==> epoll available: {}", isEpollAvailable);
    logger.info("\n==> kqueue available: {}", isKqueueAvailable);
    logger.info("\n==> openssl available: {}", isOpensslAvailable);

    Transport transport = Transport.get(/*native IO*/ true);
    logger.info("\n==> io transport: {}", transport.type());
    SslContext sslContext = Security.serverSslContext(keyStoreFile, keyStorePassword);

    ServerBootstrap bootstrap = new ServerBootstrap();
    Channel server =
        bootstrap
            .group(transport.eventLoopGroup())
            .channel(transport.serverChannel())
            .childHandler(
                new ConnectionAcceptor(sslContext, frameSizeLimit, expectMasked, maskMismatch))
            .bind(host, port)
            .sync()
            .channel();
    logger.info("\n==> Server is listening on {}:{}", host, port);
    server.closeFuture().sync();
  }

  private static class ConnectionAcceptor extends ChannelInitializer<SocketChannel> {
    private final SslContext sslContext;
    private final WebSocketDecoderConfig webSocketDecoderConfig;

    ConnectionAcceptor(
        SslContext sslContext,
        int frameSizeLimit,
        boolean expectMasked,
        boolean allowMaskMismatch) {
      this.sslContext = sslContext;
      this.webSocketDecoderConfig =
          WebSocketDecoderConfig.newBuilder()
              .allowMaskMismatch(allowMaskMismatch)
              .expectMaskedFrames(expectMasked)
              .maxFramePayloadLength(frameSizeLimit)
              .withUTF8Validator(false)
              .build();
    }

    @Override
    protected void initChannel(SocketChannel ch) {
      HttpServerCodec http1Codec = new HttpServerCodec();
      HttpObjectAggregator http1Aggregator = new HttpObjectAggregator(65536);
      WebSocketCallbacksHandler webSocketHandler = new WebSocketServerHandler();

      WebSocketServerProtocolHandler webSocketProtocolHandler =
          WebSocketServerProtocolHandler.create()
              .path("/echo")
              .decoderConfig(webSocketDecoderConfig)
              .webSocketCallbacksHandler(webSocketHandler)
              .build();

      ChannelPipeline pipeline = ch.pipeline();
      if (sslContext != null) {
        SslHandler sslHandler = sslContext.newHandler(ch.alloc());
        pipeline.addLast(sslHandler);
      }
      pipeline.addLast(http1Codec).addLast(http1Aggregator).addLast(webSocketProtocolHandler);
    }
  }

  private static class WebSocketServerHandler
      implements WebSocketCallbacksHandler, WebSocketFrameListener {
    private WebSocketFrameFactory frameFactory;

    @Override
    public WebSocketFrameListener exchange(
        ChannelHandlerContext ctx, WebSocketFrameFactory frameFactory) {
      this.frameFactory = frameFactory;
      return this;
    }

    @Override
    public void onChannelRead(
        ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload) {
      WebSocketFrameFactory factory = frameFactory;
      switch (opcode) {
        case WebSocketProtocol.OPCODE_BINARY:
          {
            ByteBuf frame = factory.createBinaryFrame(ctx.alloc(), payload.readableBytes());
            frame.writeBytes(payload);
            factory.mask(frame);
            payload.release();
            ctx.write(frame, ctx.voidPromise());
          }
          break;
        case WebSocketProtocol.OPCODE_CLOSE:
          {
            WebSocketCloseStatus status =
                WebSocketCloseStatus.valueOf(CloseFramePayload.statusCode(payload));
            String reason = CloseFramePayload.reason(payload);
            logger.info("websocket peer closed with code: {}, reason: {}", status, reason);
            payload.release();
            ByteBuf frame = factory.createCloseFrame(ctx.alloc(), status.code(), reason);
            factory.mask(frame);
            ctx.write(frame).addListener(ChannelFutureListener.CLOSE);
          }
          break;
        default:
          logger.info("Received unhandled frame, opcode: {}", opcode);
          payload.release();
      }
    }

    @Override
    public void onChannelReadComplete(ChannelHandlerContext ctx) {
      ctx.flush();
    }

    @Override
    public void onChannelWritabilityChanged(ChannelHandlerContext ctx) {
      if (!ctx.channel().isWritable()) {
        ctx.flush();
      }
    }

    @Override
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (cause instanceof IOException) {
        return;
      }
      logger.info("Unexpected websocket error", cause);
      ctx.close();
    }
  }
}
