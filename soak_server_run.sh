#!/bin/sh

export NETTY_WEBSOCKET_HTTP1_PERFTEST_SERVER_OPTS='--add-exports java.base/sun.security.x509=ALL-UNNAMED'

cd netty-websocket-http1-test/build/install/netty-websocket-http1-test/bin && ./netty-websocket-http1-test-soakserver