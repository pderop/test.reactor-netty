/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.logging;

import io.netty5.handler.codec.http.FullHttpResponse;
import io.netty5.handler.codec.http.headers.HttpHeaders;

import static reactor.netty5.http.logging.HttpMessageType.FULL_RESPONSE;

final class FullHttpResponseArgProvider extends LastHttpContentArgProvider {

	final HttpHeaders httpHeaders;
	final String protocol;
	final String status;

	FullHttpResponseArgProvider(FullHttpResponse fullHttpResponse) {
		super(fullHttpResponse);
		this.httpHeaders = fullHttpResponse.headers();
		this.protocol = fullHttpResponse.protocolVersion().text();
		this.status = fullHttpResponse.status().toString();
	}

	@Override
	public HttpHeaders headers() {
		return httpHeaders;
	}

	@Override
	public HttpMessageType httpMessageType() {
		return FULL_RESPONSE;
	}

	@Override
	public String protocol() {
		return protocol;
	}

	@Override
	public String status() {
		return status;
	}
}