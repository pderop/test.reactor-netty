/*
 * Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.udp;

import java.net.InetAddress;
import java.net.NetworkInterface;

import io.netty5.channel.ChannelOption;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.util.concurrent.Future;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.FutureMono;
import reactor.netty5.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import static reactor.netty5.ReactorNetty.format;

/**
 * {@link UdpInbound} and {@link UdpOutbound} that apply to a {@link Connection}.
 *
 * @author Stephane Maldini
 */
final class UdpOperations extends ChannelOperations<UdpInbound, UdpOutbound>
		implements UdpInbound, UdpOutbound {

	UdpOperations(Connection c, ConnectionObserver listener) {
		super(c, listener);
	}

	/**
	 * Join a multicast group.
	 *
	 * @param multicastAddress multicast address of the group to join
	 *
	 * @return a {@link Publisher} that will be complete when the group has been joined
	 */
	@Override
	public Mono<Void> join(final InetAddress multicastAddress, @Nullable NetworkInterface iface) {
		if (!(connection().channel() instanceof DatagramChannel datagramChannel)) {
			throw new UnsupportedOperationException();
		}
		if (null == iface && null != datagramChannel.getOption(ChannelOption.IP_MULTICAST_IF)) {
			iface = datagramChannel.getOption(ChannelOption.IP_MULTICAST_IF);
		}

		final Future<Void> future;
		if (null != iface) {
			future = datagramChannel.joinGroup(multicastAddress, iface, null);
		}
		else {
			future = datagramChannel.joinGroup(multicastAddress);
		}

		return FutureMono.from(future)
		                 .doOnSuccess(v -> {
		                     if (log.isInfoEnabled()) {
		                         log.info(format(datagramChannel, "JOIN {}"), multicastAddress);
		                     }
		                 });
	}

	/**
	 * Leave a multicast group.
	 *
	 * @param multicastAddress multicast address of the group to leave
	 *
	 * @return a {@link Publisher} that will be complete when the group has been left
	 */
	@Override
	public Mono<Void> leave(final InetAddress multicastAddress, @Nullable NetworkInterface iface) {
		if (!(connection().channel() instanceof DatagramChannel datagramChannel)) {
			throw new UnsupportedOperationException();
		}
		if (null == iface && null != datagramChannel.getOption(ChannelOption.IP_MULTICAST_IF)) {
			iface = datagramChannel.getOption(ChannelOption.IP_MULTICAST_IF);
		}

		final Future<Void> future;
		if (null != iface) {
			future = datagramChannel.leaveGroup(multicastAddress, iface, null);
		}
		else {
			future = datagramChannel.leaveGroup(multicastAddress);
		}

		return FutureMono.from(future)
		                 .doOnSuccess(v -> {
		                     if (log.isInfoEnabled()) {
		                         log.info(format(datagramChannel, "JOIN {}"), multicastAddress);
		                     }
		                 });
	}

	static final Logger log = Loggers.getLogger(UdpOperations.class);
}