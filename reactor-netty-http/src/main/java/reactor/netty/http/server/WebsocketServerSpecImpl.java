/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import reactor.netty.http.websocket.WebsocketSpecImpl;

/**
 * Websocket server configuration.
 *
 * @author Violeta Georgieva
 * @since 0.9.5
 */
final class WebsocketServerSpecImpl extends WebsocketSpecImpl implements WebsocketServerSpec {

	@Override
	public boolean compressionAllowServerNoContext() {
		return allowServerNoContext;
	}

	@Override
	public boolean compressionPreferredClientNoContext() {
		return preferredClientNoContext;
	}

	private final boolean allowServerNoContext;
	private final boolean preferredClientNoContext;

	WebsocketServerSpecImpl(WebsocketServerSpec.Builder builder) {
		super(builder);
		this.allowServerNoContext = builder.allowServerNoContext;
		this.preferredClientNoContext = builder.preferredClientNoContext;
	}
}
