/*
 * Copyright (c) 2019-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.channel;

import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.ssl.SslHandler;
import io.netty5.util.concurrent.Future;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;

import static reactor.netty5.Metrics.ERROR;
import static reactor.netty5.Metrics.SUCCESS;

/**
 * {@link ChannelHandler} for collecting metrics on protocol level.
 *
 * @author Violeta Georgieva
 */
public class ChannelMetricsHandler extends AbstractChannelMetricsHandler {

	final ChannelMetricsRecorder recorder;

	ChannelMetricsHandler(ChannelMetricsRecorder recorder, @Nullable SocketAddress remoteAddress, boolean onServer) {
		super(remoteAddress, onServer);
		this.recorder = recorder;
	}

	@Override
	public ChannelHandler connectMetricsHandler() {
		return new ConnectMetricsHandler(recorder());
	}

	@Override
	public ChannelHandler tlsMetricsHandler() {
		return new TlsMetricsHandler(recorder);
	}

	@Override
	public ChannelMetricsRecorder recorder() {
		return recorder;
	}

	static final class ConnectMetricsHandler extends ChannelHandlerAdapter {

		final ChannelMetricsRecorder recorder;

		ConnectMetricsHandler(ChannelMetricsRecorder recorder) {
			this.recorder = recorder;
		}

		@Override
		public Future<Void> connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress) {
			long connectTimeStart = System.nanoTime();
			return ctx.connect(remoteAddress, localAddress)
			          .addListener(future -> {
			              ctx.pipeline().remove(this);

			              recorder.recordConnectTime(
			                  remoteAddress,
			                  Duration.ofNanos(System.nanoTime() - connectTimeStart),
			                  future.isSuccess() ? SUCCESS : ERROR);
			          });
		}
	}

	static class TlsMetricsHandler extends ChannelHandlerAdapter {
		protected final ChannelMetricsRecorder recorder;
		TlsMetricsHandler(ChannelMetricsRecorder recorder) {
			this.recorder = recorder;
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			long tlsHandshakeTimeStart = System.nanoTime();
			ctx.pipeline()
			   .get(SslHandler.class)
			   .handshakeFuture()
			   .addListener(f -> {
			           ctx.pipeline().remove(this);
			           recordTlsHandshakeTime(ctx, tlsHandshakeTimeStart, f.isSuccess() ? SUCCESS : ERROR);
			   });
			ctx.fireChannelActive();
		}

		protected void recordTlsHandshakeTime(ChannelHandlerContext ctx, long tlsHandshakeTimeStart, String status) {
			recorder.recordTlsHandshakeTime(
					ctx.channel().remoteAddress(),
					Duration.ofNanos(System.nanoTime() - tlsHandshakeTimeStart),
					status);
		}
	}
}
