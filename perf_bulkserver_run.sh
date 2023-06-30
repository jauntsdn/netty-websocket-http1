#!/bin/sh

export NETTY_WEBSOCKET_HTTP1_PERFTEST_BULKSERVER_OPTS='--add-exports java.base/sun.security.x509=ALL-UNNAMED'

cd netty-websocket-http1-perftest/build/install/netty-websocket-http1-perftest/bin && ./netty-websocket-http1-perftest-bulkserver