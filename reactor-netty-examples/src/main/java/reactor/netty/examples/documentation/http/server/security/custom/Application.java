/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.examples.documentation.http.server.security.custom;

import reactor.netty.DisposableServer;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.server.HttpServer;

import java.io.File;
import java.time.Duration;

public class Application {

	public static void main(String[] args) {
		File cert = new File("certificate.crt");
		File key = new File("private.key");

		Http11SslContextSpec http11SslContextSpec = Http11SslContextSpec.forServer(cert, key);

		DisposableServer server =
				HttpServer.create()
				          .secure(spec -> spec.sslContext(http11SslContextSpec)
				                              .handshakeTimeout(Duration.ofSeconds(30))         //<1>
				                              .closeNotifyFlushTimeout(Duration.ofSeconds(10))  //<2>
				                              .closeNotifyReadTimeout(Duration.ofSeconds(10)))  //<3>
				          .bindNow();

		server.onDispose()
		      .block();
	}
}