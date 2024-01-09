/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.socket.DatagramPacket;
import io.netty5.handler.ssl.AbstractSniHandler;
import io.netty5.handler.ssl.SslHandler;
import io.netty5.util.concurrent.Future;
import reactor.netty5.NettyPipeline;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;

import static reactor.netty5.ReactorNetty.format;

/**
 * Base {@link ChannelHandler} for collecting metrics on protocol level.
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
public abstract class AbstractChannelMetricsHandler extends ChannelHandlerAdapter {

	private static final Logger log = Loggers.getLogger(AbstractChannelMetricsHandler.class);

	final SocketAddress remoteAddress;

	final boolean onServer;

	boolean channelOpened;

	protected AbstractChannelMetricsHandler(@Nullable SocketAddress remoteAddress, boolean onServer) {
		this.remoteAddress = remoteAddress;
		this.onServer = onServer;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		if (onServer) {
			try {
				channelOpened = true;
				recorder().recordServerConnectionOpened(ctx.channel().localAddress());
			}
			catch (RuntimeException e) {
				// Allow request-response exchange to continue, unaffected by metrics problem
				if (log.isWarnEnabled()) {
					log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
				}
			}
		}
		ctx.fireChannelActive();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		if (onServer) {
			try {
				if (channelOpened) {
					channelOpened = false;
					recorder().recordServerConnectionClosed(ctx.channel().localAddress());
				}
			}
			catch (RuntimeException e) {
				// Allow request-response exchange to continue, unaffected by metrics problem
				if (log.isWarnEnabled()) {
					log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
				}
			}
		}
		ctx.fireChannelInactive();
	}

	@Override
	public void channelRegistered(ChannelHandlerContext ctx) {
		if (!onServer) {
			ctx.pipeline()
			   .addAfter(NettyPipeline.ChannelMetricsHandler,
			             NettyPipeline.ConnectMetricsHandler,
			             connectMetricsHandler());
		}
		ChannelHandler sslHandler = ctx.pipeline().get(NettyPipeline.SslHandler);
		if (sslHandler instanceof SslHandler) {
			ctx.pipeline()
			   .addBefore(NettyPipeline.SslHandler,
			             NettyPipeline.TlsMetricsHandler,
			             tlsMetricsHandler());
		}
		else if (sslHandler instanceof AbstractSniHandler) {
			ctx.pipeline()
			   .addAfter(NettyPipeline.SslHandler,
			            NettyPipeline.TlsMetricsHandler,
			            tlsMetricsHandler());
		}

		ctx.fireChannelRegistered();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof Buffer buffer) {
				if (buffer.readableBytes() > 0) {
					recordRead(ctx, remoteAddress, buffer.readableBytes());
				}
			}
			else if (msg instanceof DatagramPacket p) {
				Buffer buffer = p.content();
				if (buffer.readableBytes() > 0) {
					recordRead(ctx, remoteAddress != null ? remoteAddress : p.sender(), buffer.readableBytes());
				}
			}
		}
		catch (RuntimeException e) {
			// Allow request-response exchange to continue, unaffected by metrics problem
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof Buffer buffer) {
				if (buffer.readableBytes() > 0) {
					recordWrite(ctx, remoteAddress, buffer.readableBytes());
				}
			}
			else if (msg instanceof DatagramPacket p) {
				Buffer buffer = p.content();
				if (buffer.readableBytes() > 0) {
					recordWrite(ctx, remoteAddress != null ? remoteAddress : p.recipient(), buffer.readableBytes());
				}
			}
		}
		catch (RuntimeException e) {
			// Allow request-response exchange to continue, unaffected by metrics problem
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}

		return ctx.write(msg);
	}

	@Override
	public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		try {
			recordException(ctx, remoteAddress != null ? remoteAddress : ctx.channel().remoteAddress());
		}
		catch (RuntimeException e) {
			// Allow request-response exchange to continue, unaffected by metrics problem
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}

		ctx.fireChannelExceptionCaught(cause);
	}

	public abstract ChannelHandler connectMetricsHandler();

	public abstract ChannelHandler tlsMetricsHandler();

	public abstract ChannelMetricsRecorder recorder();

	protected void recordException(ChannelHandlerContext ctx, SocketAddress address) {
		recorder().incrementErrorsCount(address);
	}

	protected void recordRead(ChannelHandlerContext ctx, SocketAddress address, long bytes) {
		recorder().recordDataReceived(address, bytes);
	}

	protected void recordWrite(ChannelHandlerContext ctx, SocketAddress address, long bytes) {
		recorder().recordDataSent(address, bytes);
	}
}
