#!/bin/sh

export NETTY_WEBSOCKET_HTTP1_PERFTEST_SERVER_OPTS='--add-exports java.base/sun.security.x509=ALL-UNNAMED'

cd netty-websocket-http1-soaktest/build/install/netty-websocket-http1-soaktest/bin && ./netty-websocket-http1-soaktest-server