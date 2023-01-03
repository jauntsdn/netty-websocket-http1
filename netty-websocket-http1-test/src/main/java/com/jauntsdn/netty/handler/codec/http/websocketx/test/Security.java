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

package com.jauntsdn.netty.handler.codec.http.websocketx.test;

import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Security {
  private static final Logger logger = LoggerFactory.getLogger(Security.class);

  public static SslContext serverSslContext() throws Exception {
    SecureRandom random = new SecureRandom();
    SelfSignedCertificate ssc = new SelfSignedCertificate("com.jauntsdn", random, 1024);

    return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
        .protocols("TLSv1.3")
        .sslProvider(sslProvider())
        .ciphers(supportedCypherSuites(), SupportedCipherSuiteFilter.INSTANCE)
        .build();
  }

  public static SslContext clientLocalSslContext() throws SSLException {
    return SslContextBuilder.forClient()
        .protocols("TLSv1.3")
        .sslProvider(sslProvider())
        .ciphers(supportedCypherSuites(), SupportedCipherSuiteFilter.INSTANCE)
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .build();
  }

  private static SslProvider sslProvider() {
    final SslProvider sslProvider;
    if (OpenSsl.isAvailable()) {
      logger.info("using openssl ssl provider");
      sslProvider = SslProvider.OPENSSL_REFCNT;
    } else {
      logger.info("using jdk ssl provider");
      sslProvider = SslProvider.JDK;
    }
    return sslProvider;
  }

  private static final List<String> CIPHERSUITES =
      Arrays.asList(
          "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
          "TLS_AES_128_GCM_SHA256",
          "TLS_AES_256_GCM_SHA384",
          "TLS_CHACHA20_POLY1305_SHA256");

  static List<String> supportedCypherSuites() {
    return CIPHERSUITES;
  }
}
