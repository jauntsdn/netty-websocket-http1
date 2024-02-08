![Maven Central](https://img.shields.io/maven-central/v/com.jauntsdn.netty/netty-websocket-http1)
[![Build](https://github.com/jauntsdn/netty-websocket-http1/actions/workflows/ci-build.yml/badge.svg)](https://github.com/jauntsdn/netty-websocket-http1/actions/workflows/ci-build.yml)

# netty-websocket-http1

Alternative Netty implementation of [RFC6455](https://tools.ietf.org/html/rfc6455) - the WebSocket protocol. 

Its advantage is significant per-core throughput improvement (1.8 - 2x) for small frames in comparison to netty's out-of-the-box 
websocket codecs, and minimal heap allocations on frame path. Library is compatible with 
[netty-websocket-http2](https://github.com/jauntsdn/netty-websocket-http2).

### use case & scope

* Intended for efficiently encoded, dense binary data: no extensions (compression) support / outbound text frames / inbound 
utf8 validation.

* Library assumes small frames - many have payload <= 125 bytes, most are < 1500, maximum supported is 65k (65535 bytes).

* Just codec - fragments, pings, close frames are decoded & validated only. It is responsibility of user code 
to handle frames according to protocol (reassemble frame fragments, perform graceful close, 
respond to pings).

* Dedicated decoder for case of exchanging tiny messages over TLS connection: 
only non-masked frames with <= 125 bytes of payload for minimal per-webSocket state (memory) overhead.

* No per-frame heap allocations in websocket frameFactory / decoder.

* Single-threaded (transport IO event-loop) callbacks / frame factory API - 
in practice user code has its own message types to carry data, external means (e.g. mpsc / spsc queues) may be used to 
properly publish messages on eventloop thread.

### performance

Per-core throughput [this codec perf-test](https://github.com/jauntsdn/netty-websocket-http1/tree/develop/netty-websocket-http1-perftest/src/main/java/com/jauntsdn/netty/handler/codec/http/websocketx/perftest), 
[netty built-in codec perf-test](https://github.com/jauntsdn/netty-websocket-http1/tree/netty-codec/netty-builtin-websocket-perftest/src/main/java/io/netty/handler/codec/http/websocketx/perftest) 
comparison with netty's out-of-the-box websocket handlers: 
non-masked frames with 8, 64, 125, 1000 bytes of payload over encrypted/non-encrypted connection.

java 9+
```
./gradlew clean build installDist
./perf_server_run.sh
./perf_client_run.sh
```

* encrypted

| payload size | this codec, million messages | netty's codec, million messages |
| :---         |     :---:     |        :---: |
| 8            | 2.8 | 1.45 |
| 64           | 2.3 |  1.2 |
| 125          | 1.9 | 1.1  |
| 1000         | 0.52| 0.35 |

### websocket-http2

Library may be combined with [jauntsdn/websocket-http2](https://github.com/jauntsdn/netty-websocket-http2) using [http1 codec](https://github.com/jauntsdn/netty-websocket-http2/blob/develop/netty-websocket-http2-callbacks-codec/src/main/java/com/jauntsdn/netty/handler/codec/http2/websocketx/WebSocketCallbacksCodec.java) 

for significantly improved per-core throughput [this codec perf-test](https://github.com/jauntsdn/netty-websocket-http2/tree/develop/netty-websocket-http2-perftest/src/main/java/com/jauntsdn/netty/handler/codec/http2/websocketx/perftest/callbackscodec), 
[netty built-in codec perf-test](https://github.com/jauntsdn/netty-websocket-http2/tree/develop/netty-websocket-http2-perftest/src/main/java/com/jauntsdn/netty/handler/codec/http2/websocketx/perftest/messagecodec):  

* encrypted

| payload size | this codec, million msgs  | netty's codec, million msgs |
| :---:        |     :---:     |        :---: |
| 8      | 0.93 | 0.56   |
| 125    | 0.74 | 0.464  |
| 1000   | 0.275 | 0.211 |

### frameFactory / callbacks API

[WebSocketFrameFactory](https://github.com/jauntsdn/netty-websocket-http1/blob/develop/netty-websocket-http1/src/main/java/com/jauntsdn/netty/handler/codec/http/websocketx/WebSocketFrameFactory.java) 
to create outbound frames. It is library user responsibility to mask outbound frame once payload is written 
`ByteBuf WebSocketFrameFactory.mask(ByteBuf)`

```java
public interface WebSocketFrameFactory {

  ByteBuf createBinaryFrame(ByteBufAllocator allocator, int binaryDataSize);
  // create*Frame are omitted for control frames, created in similar fashion

  ByteBuf mask(ByteBuf frame);
}
```

[WebSocketFrameListener](https://github.com/jauntsdn/netty-websocket-http1/blob/develop/netty-websocket-http1/src/main/java/com/jauntsdn/netty/handler/codec/http/websocketx/WebSocketFrameListener.java) 
to receive inbound frames

```java
public interface WebSocketFrameListener {

  void onChannelRead(
      ChannelHandlerContext ctx, boolean finalFragment, int rsv, int opcode, ByteBuf payload);
   
  // netty handler callbacks are omitted for brevity

  // lifecycle
  default void onOpen(ChannelHandlerContext ctx) {}

  default void onClose(ChannelHandlerContext ctx) {}
}
```

[WebSocketCallbacksHandler](https://github.com/jauntsdn/netty-websocket-http1/blob/develop/netty-websocket-http1/src/main/java/com/jauntsdn/netty/handler/codec/http/websocketx/WebSocketCallbacksHandler.java) 
to exchange `WebSocketFrameListener` for `WebSocketFrameFactory` on successful websocket handshake

```java
public interface WebSocketCallbacksHandler {

  WebSocketFrameListener exchange(
      ChannelHandlerContext ctx, WebSocketFrameFactory webSocketFrameFactory);
}
```

### configuration

#### default messages decoder 

Always expects masked frames, allows mask mismatch [test example](https://github.com/jauntsdn/netty-websocket-http1/blob/bc942b19958c1486ef7414bee9c69ef36a55bfa5/netty-websocket-http1-test/src/test/java/com/jauntsdn/netty/handler/codec/http/websocketx/WebSocketHandshakeTest.java#L121)

server: `allowMaskMismatch: true, maxFramePayloadLength: 65_535`
 
client: `allowMaskMismatch: true, maxFramePayloadLength: 65_535`

#### small messages decoder 

Always expects non-masking frames, disallows mask mismatch [test example](https://github.com/jauntsdn/netty-websocket-http1/blob/bc942b19958c1486ef7414bee9c69ef36a55bfa5/netty-websocket-http1-test/src/test/java/com/jauntsdn/netty/handler/codec/http/websocketx/WebSocketHandshakeTest.java#L140):

server: `expectMasked: false, allowMaskMismatch: false, maxFramePayloadLength: 125`
 
client: `mask: false, allowMaskMismatch: false, maxFramePayloadLength: 125` 

### tests

* WebSocket frames [integration test](https://github.com/jauntsdn/netty-websocket-http1/blob/develop/netty-websocket-http1-test/src/test/java/com/jauntsdn/netty/handler/codec/http/websocketx/WebSocketCodecTest.java): 
control & data frames of all allowed sizes, jauntsdn/netty-websocket-http1 client, netty websocket server. 

* WebSocket frames long-running [soak test](https://github.com/jauntsdn/netty-websocket-http1/tree/develop/netty-websocket-http1-soaktest/src/main/java/com/jauntsdn/netty/handler/codec/http/websocketx/soaktest): 
exercising all logic paths of codec with 100m of randomized frames over multiple connections: netty websocket client, jauntsdn/netty-websocket-http1 server.

* [Perf test](https://github.com/jauntsdn/netty-websocket-http1/tree/develop/netty-websocket-http1-perftest/src/main/java/com/jauntsdn/netty/handler/codec/http/websocketx/perftest): 
per-core throughput of jauntsdn/netty-websocket-http1 client & server.

### examples

`netty-websocket-http1-perftest` may serve as API showcase for both [client](https://github.com/jauntsdn/netty-websocket-http1/blob/develop/netty-websocket-http1-perftest/src/main/java/com/jauntsdn/netty/handler/codec/http/websocketx/perftest/client/Main.java) 
and [server](https://github.com/jauntsdn/netty-websocket-http1/blob/develop/netty-websocket-http1-perftest/src/main/java/com/jauntsdn/netty/handler/codec/http/websocketx/perftest/server/Main.java):

### build & binaries

```
./gradlew
```

Releases are published on MavenCentral
```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation "com.jauntsdn.netty:netty-websocket-http1:1.1.2"
}
```

## LICENSE

Copyright 2022-Present Maksym Ostroverkhov.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.