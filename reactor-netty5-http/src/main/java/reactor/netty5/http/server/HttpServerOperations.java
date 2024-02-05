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
package reactor.netty5.http.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty5.buffer.Buffer;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelFutureListeners;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelOption;
import io.netty5.handler.codec.http.DefaultFullHttpResponse;
import io.netty5.handler.codec.http.DefaultHttpResponse;
import io.netty5.handler.codec.http.DefaultLastHttpContent;
import io.netty5.handler.codec.http.EmptyLastHttpContent;
import io.netty5.handler.codec.http.FullHttpRequest;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpHeaderValues;
import io.netty5.handler.codec.http.HttpObject;
import io.netty5.handler.codec.http.headers.HttpCookiePair;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.handler.codec.http.headers.DefaultHttpHeaders;
import io.netty5.handler.codec.http.HttpMessage;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.HttpUtil;
import io.netty5.handler.codec.http.HttpVersion;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.handler.codec.http.TooLongHttpHeaderException;
import io.netty5.handler.codec.http.TooLongHttpLineException;
import io.netty5.handler.codec.http.headers.HttpSetCookie;
import io.netty.contrib.handler.codec.http.multipart.HttpData;
import io.netty.contrib.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty5.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty5.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty5.handler.timeout.ReadTimeoutHandler;
import io.netty5.util.AsciiString;
import io.netty5.util.Resource;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.FutureListener;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.FutureMono;
import reactor.netty5.NettyOutbound;
import reactor.netty5.NettyPipeline;
import reactor.netty5.ReactorNetty;
import reactor.netty5.channel.AbortedException;
import reactor.netty5.channel.ChannelOperations;
import reactor.netty5.http.HttpOperations;
import reactor.netty5.http.logging.HttpMessageArgProviderFactory;
import reactor.netty5.http.logging.HttpMessageLogFactory;
import reactor.netty5.http.websocket.WebsocketInbound;
import reactor.netty5.http.websocket.WebsocketOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import static io.netty5.buffer.DefaultBufferAllocators.preferredAllocator;
import static io.netty5.handler.codec.http.HttpUtil.isTransferEncodingChunked;
import static reactor.netty5.ReactorNetty.format;
import static reactor.netty5.http.server.HttpServerFormDecoderProvider.DEFAULT_FORM_DECODER_SPEC;
import static reactor.netty5.http.server.HttpServerState.REQUEST_DECODING_FAILED;

/**
 * Conversion between Netty types and Reactor types ({@link HttpOperations}.
 *
 * @author Stephane Maldini1
 */
class HttpServerOperations extends HttpOperations<HttpServerRequest, HttpServerResponse>
		implements HttpServerRequest, HttpServerResponse {

	final BiPredicate<HttpServerRequest, HttpServerResponse> configuredCompressionPredicate;
	final ConnectionInfo connectionInfo;
	final ServerCookies cookieHolder;
	final HttpServerFormDecoderProvider formDecoderProvider;
	final boolean is100ContinueExpected;
	final boolean isHttp2;
	final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle;
	final HttpRequest nettyRequest;
	final HttpResponse nettyResponse;
	final Duration readTimeout;
	final Duration requestTimeout;
	final HttpHeaders responseHeaders;
	final String scheme;
	final ZonedDateTime timestamp;

	BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate;
	Function<? super String, Map<String, String>> paramsResolver;
	String path;
	Future<Void> requestTimeoutFuture;
	Consumer<? super HttpHeaders> trailerHeadersConsumer;

	volatile Context currentContext;

	HttpServerOperations(HttpServerOperations replaced) {
		super(replaced);
		this.compressionPredicate = replaced.compressionPredicate;
		this.configuredCompressionPredicate = replaced.configuredCompressionPredicate;
		this.connectionInfo = replaced.connectionInfo;
		this.cookieHolder = replaced.cookieHolder;
		this.currentContext = replaced.currentContext;
		this.formDecoderProvider = replaced.formDecoderProvider;
		this.is100ContinueExpected = replaced.is100ContinueExpected;
		this.isHttp2 = replaced.isHttp2;
		this.mapHandle = replaced.mapHandle;
		this.nettyRequest = replaced.nettyRequest;
		this.nettyResponse = replaced.nettyResponse;
		this.paramsResolver = replaced.paramsResolver;
		this.path = replaced.path;
		this.readTimeout = replaced.readTimeout;
		this.requestTimeout = replaced.requestTimeout;
		this.responseHeaders = replaced.responseHeaders;
		this.scheme = replaced.scheme;
		this.timestamp = replaced.timestamp;
		this.trailerHeadersConsumer = replaced.trailerHeadersConsumer;
	}

	HttpServerOperations(Connection c, ConnectionObserver listener, HttpRequest nettyRequest,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate,
			ConnectionInfo connectionInfo,
			HttpServerFormDecoderProvider formDecoderProvider,
			HttpMessageLogFactory httpMessageLogFactory,
			boolean isHttp2,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
			boolean secured,
			ZonedDateTime timestamp) {
		this(c, listener, nettyRequest, compressionPredicate, connectionInfo, formDecoderProvider,
				httpMessageLogFactory, isHttp2, mapHandle, readTimeout, requestTimeout, true, secured, timestamp);
	}

	HttpServerOperations(Connection c, ConnectionObserver listener, HttpRequest nettyRequest,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate,
			ConnectionInfo connectionInfo,
			HttpServerFormDecoderProvider formDecoderProvider,
			HttpMessageLogFactory httpMessageLogFactory,
			boolean isHttp2,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
			boolean resolvePath,
			boolean secured,
			ZonedDateTime timestamp) {
		super(c, listener, httpMessageLogFactory);
		this.compressionPredicate = compressionPredicate;
		this.configuredCompressionPredicate = compressionPredicate;
		this.connectionInfo = connectionInfo;
		this.cookieHolder = ServerCookies.newServerRequestHolder(nettyRequest.headers());
		this.currentContext = Context.empty();
		this.formDecoderProvider = formDecoderProvider;
		this.is100ContinueExpected = HttpUtil.is100ContinueExpected(nettyRequest);
		this.isHttp2 = isHttp2;
		this.mapHandle = mapHandle;
		this.nettyRequest = nettyRequest;
		this.nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		if (resolvePath) {
			this.path = resolvePath(nettyRequest.uri());
		}
		else {
			this.path = null;
		}
		this.readTimeout = readTimeout;
		this.requestTimeout = requestTimeout;
		this.responseHeaders = nettyResponse.headers();
		this.responseHeaders.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		this.scheme = secured ? "https" : "http";
		this.timestamp = timestamp;
	}

	@Override
	public NettyOutbound sendHeaders() {
		if (hasSentHeaders()) {
			return this;
		}

		return then(Mono.empty());
	}

	@Override
	public HttpServerOperations withConnection(Consumer<? super Connection> withConnection) {
		Objects.requireNonNull(withConnection, "withConnection");
		withConnection.accept(this);
		return this;
	}

	@Override
	protected HttpMessage newFullBodyMessage(Buffer body) {
		HttpResponse res =
				new DefaultFullHttpResponse(version(), status(), body);

		if (!HttpMethod.HEAD.equals(method())) {
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
			int code = status().code();
			if (!(HttpResponseStatus.NOT_MODIFIED.code() == code ||
					HttpResponseStatus.NO_CONTENT.code() == code)) {

				if (HttpUtil.getContentLength(nettyResponse, -1) == -1) {
					responseHeaders.set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(body.readableBytes()));
				}
			}
		}
		// For HEAD requests:
		// - if there is Transfer-Encoding and Content-Length, Transfer-Encoding will be removed
		// - if there is only Transfer-Encoding, it will be kept and not replaced by
		// Content-Length: body.readableBytes()
		// For HEAD requests, the I/O handler may decide to provide only the headers and complete
		// the response. In that case body will be EMPTY_BUFFER and if we set Content-Length: 0,
		// this will not be correct
		// https://github.com/reactor/reactor-netty/issues/1333
		else if (HttpUtil.getContentLength(nettyResponse, -1) != -1) {
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
		}

		res.headers().set(responseHeaders);
		return res;
	}

	@Override
	public HttpServerResponse addCookie(HttpSetCookie setCookie) {
		if (!hasSentHeaders()) {
			this.responseHeaders.addSetCookie(setCookie);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse addHeader(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.responseHeaders.add(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerOperations chunkedTransfer(boolean chunked) {
		if (!hasSentHeaders() && isTransferEncodingChunked(nettyResponse) != chunked) {
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
			HttpUtil.setTransferEncodingChunked(nettyResponse, chunked);
		}
		return this;
	}

	@Override
	public Map<CharSequence, Set<HttpCookiePair>> cookies() {
		if (cookieHolder != null) {
			return cookieHolder.getCachedCookies();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public Map<CharSequence, List<HttpCookiePair>> allCookies() {
		if (cookieHolder != null) {
			return cookieHolder.getAllCachedCookies();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public Context currentContext() {
		return currentContext;
	}

	@Override
	public HttpServerResponse header(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.responseHeaders.set(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse headers(HttpHeaders headers) {
		if (!hasSentHeaders()) {
			this.responseHeaders.set(headers);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public boolean isFormUrlencoded() {
		CharSequence mimeType = HttpUtil.getMimeType(nettyRequest);
		return mimeType != null &&
				HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.contentEqualsIgnoreCase(mimeType.toString().trim());
	}

	@Override
	public boolean isKeepAlive() {
		return HttpUtil.isKeepAlive(nettyRequest);
	}

	@Override
	public boolean isMultipart() {
		return HttpPostRequestDecoder.isMultipart(nettyRequest);
	}

	@Override
	public boolean isWebsocket() {
		return get(channel()) instanceof WebsocketServerOperations;
	}

	final boolean isHttp2() {
		return isHttp2;
	}

	@Override
	public HttpServerResponse keepAlive(boolean keepAlive) {
		HttpUtil.setKeepAlive(nettyResponse, keepAlive);
		return this;
	}

	@Override
	public HttpMethod method() {
		return nettyRequest.method();
	}

	@Override
	@Nullable
	public String param(CharSequence key) {
		Objects.requireNonNull(key, "key");
		Map<String, String> params = null;
		if (paramsResolver != null) {
			params = this.paramsResolver.apply(uri());
		}
		return null != params ? params.get(key.toString()) : null;
	}

	@Override
	@Nullable
	public Map<String, String> params() {
		return null != paramsResolver ? paramsResolver.apply(uri()) : null;
	}

	@Override
	public HttpServerRequest paramsResolver(Function<? super String, Map<String, String>> paramsResolver) {
		this.paramsResolver = paramsResolver;
		return this;
	}

	@Override
	public Flux<HttpData> receiveForm() {
		return receiveFormInternal(formDecoderProvider);
	}

	@Override
	public Flux<HttpData> receiveForm(Consumer<HttpServerFormDecoderProvider.Builder> formDecoderBuilder) {
		Objects.requireNonNull(formDecoderBuilder, "formDecoderBuilder");
		HttpServerFormDecoderProvider.Build builder = new HttpServerFormDecoderProvider.Build();
		formDecoderBuilder.accept(builder);
		HttpServerFormDecoderProvider config = builder.build();
		return receiveFormInternal(config);
	}

	@Override
	public Flux<?> receiveObject() {
		// Handle the 'Expect: 100-continue' header if necessary.
		// TODO: Respond with 413 Request Entity Too Large
		//   and discard the traffic or close the connection.
		//       No need to notify the upstream handlers - just log.
		//       If decoding a response, just throw an error.
		if (is100ContinueExpected) {
			return FutureMono.deferFuture(() -> {
						if (!hasSentHeaders()) {
							return channel().writeAndFlush(
									new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE,
											preferredAllocator().allocate(0)));
						}
						return channel().newSucceededFuture();
					})

			                 .thenMany(super.receiveObject());
		}
		else {
			return super.receiveObject();
		}
	}

	@Override
	@Nullable
	public InetSocketAddress hostAddress() {
		return this.connectionInfo.getHostAddress();
	}

	final SocketAddress hostSocketAddress() {
		return this.connectionInfo.hostAddress;
	}

	@Override
	@Nullable
	public SocketAddress connectionHostAddress() {
		return channel().localAddress();
	}

	@Override
	@Nullable
	public InetSocketAddress remoteAddress() {
		return this.connectionInfo.getRemoteAddress();
	}

	final SocketAddress remoteSocketAddress() {
		return this.connectionInfo.remoteAddress;
	}

	@Override
	@Nullable
	public SocketAddress connectionRemoteAddress() {
		return channel().remoteAddress();
	}

	@Override
	public HttpHeaders requestHeaders() {
		if (nettyRequest != null) {
			return nettyRequest.headers();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public String scheme() {
		return this.connectionInfo.getScheme();
	}

	@Override
	public String connectionScheme() {
		return scheme;
	}

	@Override
	public String hostName() {
		return connectionInfo.getHostName();
	}

	@Override
	public int hostPort() {
		return connectionInfo.getHostPort();
	}

	@Override
	public HttpHeaders responseHeaders() {
		return responseHeaders;
	}

	@Override
	public String protocol() {
		return nettyRequest.protocolVersion().text();
	}

	@Override
	public ZonedDateTime timestamp() {
		return timestamp;
	}

	@Override
	public Mono<Void> send() {
		return FutureMono.deferFuture(() -> markSentHeaderAndBody() ?
				channel().writeAndFlush(newFullBodyMessage(channel().bufferAllocator().allocate(0))) :
				channel().newSucceededFuture());
	}

	@Override
	public NettyOutbound sendFile(Path file) {
		try {
			return sendFile(file, 0L, Files.size(file));
		}
		catch (IOException e) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Path not resolved"), e);
			}
			return then(sendNotFound());
		}
	}

	@Override
	public Mono<Void> sendNotFound() {
		return this.status(HttpResponseStatus.NOT_FOUND)
		           .send();
	}

	@Override
	public Mono<Void> sendRedirect(String location) {
		Objects.requireNonNull(location, "location");
		return this.status(HttpResponseStatus.FOUND)
		           .header(HttpHeaderNames.LOCATION, location)
		           .send();
	}

	/**
	 * The {@code Content-Type} setting SSE for this http connection (e.g. event-stream).
	 *
	 * @return the {@code Content-Type} setting SSE for this http connection (e.g. event-stream)
	 */
	@Override
	public HttpServerResponse sse() {
		header(HttpHeaderNames.CONTENT_TYPE, EVENT_STREAM);
		return this;
	}

	@Override
	public HttpResponseStatus status() {
		return this.nettyResponse.status();
	}

	@Override
	public HttpServerResponse status(HttpResponseStatus status) {
		if (!hasSentHeaders()) {
			this.nettyResponse.setStatus(status);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse trailerHeaders(Consumer<? super HttpHeaders> trailerHeaders) {
		this.trailerHeadersConsumer = Objects.requireNonNull(trailerHeaders, "trailerHeaders");
		return this;
	}

	@Override
	public Mono<Void> sendWebsocket(
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler,
			WebsocketServerSpec configurer) {
		return withWebsocketSupport(uri(), configurer, websocketHandler);
	}

	@Override
	public String uri() {
		if (nettyRequest != null) {
			return nettyRequest.uri();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public String fullPath() {
		if (path != null) {
			return path;
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public HttpVersion version() {
		if (nettyRequest != null) {
			return nettyRequest.protocolVersion();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public HttpServerResponse compression(boolean compress) {
		compressionPredicate = compress ? configuredCompressionPredicate : COMPRESSION_DISABLED;
		if (!compress) {
			removeHandler(NettyPipeline.CompressionHandler);
		}
		else if (channel().pipeline()
		                  .get(NettyPipeline.CompressionHandler) == null) {
			SimpleCompressionHandler handler = new SimpleCompressionHandler();
			try {
				// decode(...) is needed only to initialize the acceptEncodingQueue
				handler.decode(channel().pipeline().context(NettyPipeline.ReactiveBridge), nettyRequest, false);

				addHandlerFirst(NettyPipeline.CompressionHandler, handler);
			}
			catch (Throwable e) {
				log.error(format(channel(), ""), e);
			}
		}
		return this;
	}

	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpRequest) {
			boolean isFullHttpRequest = msg instanceof FullHttpRequest;
			if (!(isHttp2() && isFullHttpRequest)) {
				if (readTimeout != null) {
					addHandlerFirst(NettyPipeline.ReadTimeoutHandler,
							new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS));
				}
				if (requestTimeout != null) {
					requestTimeoutFuture =
							ctx.executor().schedule(new RequestTimeoutTask(ctx), Math.max(requestTimeout.toMillis(), 1), TimeUnit.MILLISECONDS);
				}
			}
			try {
				listener().onStateChange(this, HttpServerState.REQUEST_RECEIVED);
			}
			catch (Exception e) {
				onInboundError(e);
				Resource.dispose(msg);
				return;
			}
			if (isFullHttpRequest) {
				FullHttpRequest request = (FullHttpRequest) msg;
				if (request.payload().readableBytes() > 0) {
					super.onInboundNext(ctx, msg);
				}
				else {
					request.close();
				}
				if (isHttp2()) {
					//force auto read to enable more accurate close selection now inbound is done
					channel().setOption(ChannelOption.AUTO_READ, true);
					onInboundComplete();
				}
			}
			return;
		}
		if (msg instanceof HttpContent) {
			if (!(msg instanceof EmptyLastHttpContent emptyLastHttpContent)) {
				super.onInboundNext(ctx, msg);
			}
			else {
				emptyLastHttpContent.close();
			}
			if (msg instanceof LastHttpContent) {
				removeHandler(NettyPipeline.ReadTimeoutHandler);
				if (requestTimeoutFuture != null) {
					requestTimeoutFuture.cancel();
					requestTimeoutFuture = null;
				}
				//force auto read to enable more accurate close selection now inbound is done
				channel().setOption(ChannelOption.AUTO_READ, true);
				onInboundComplete();
			}
		}
		else {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	protected void onInboundClose() {
		discardWhenNoReceiver();
		if (!(isInboundCancelled() || isInboundDisposed())) {
			onInboundError(new AbortedException("Connection has been closed"));
		}
		terminate();
	}

	@Override
	protected void afterMarkSentHeaders() {
		if (compressionPredicate != null && compressionPredicate.test(this, this)) {
			compression(true);
		}
	}

	@Override
	protected void beforeMarkSentHeaders() {
		if (is100ContinueExpected && !isInboundComplete()) {
			int code = status().code();
			if (code < 200 || code > 299) {
				keepAlive(false);
			}
		}
	}

	@Override
	protected boolean isContentAlwaysEmpty() {
		int code = status().code();
		if (HttpResponseStatus.NOT_MODIFIED.code() == code) {
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
			responseHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
			return true;
		}
		return HttpResponseStatus.NO_CONTENT.code() == code ||
				HttpResponseStatus.RESET_CONTENT.code() == code;
	}

	@Override
	protected void onHeadersSent() {
		//noop
	}

	@Override
	protected void onOutboundComplete() {
		if (isWebsocket()) {
			return;
		}

		final Future<Void> f;
		if (log.isDebugEnabled()) {
			log.debug(format(channel(), "Last HTTP response frame"));
		}
		if (markSentHeaderAndBody()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "No sendHeaders() called before complete, sending " +
						"zero-length header"));
			}

			f = channel().writeAndFlush(newFullBodyMessage(channel().bufferAllocator().allocate(0)));
		}
		else if (markSentBody()) {
			HttpHeaders trailerHeaders = null;
			// https://datatracker.ietf.org/doc/html/rfc7230#section-4.1.2
			// A trailer allows the sender to include additional fields at the end
			// of a chunked message in order to supply metadata that might be
			// dynamically generated while the message body is sent, such as a
			// message integrity check, digital signature, or post-processing
			// status.
			if (trailerHeadersConsumer != null && isTransferEncodingChunked(nettyResponse)) {
				// https://datatracker.ietf.org/doc/html/rfc7230#section-4.4
				// When a message includes a message body encoded with the chunked
				// transfer coding and the sender desires to send metadata in the form
				// of trailer fields at the end of the message, the sender SHOULD
				// generate a Trailer header field before the message body to indicate
				// which fields will be present in the trailers.
				CharSequence declaredHeaderNames = responseHeaders.get(HttpHeaderNames.TRAILER);
				if (declaredHeaderNames != null) {
					trailerHeaders = new TrailerHeaders(declaredHeaderNames.toString());
					try {
						trailerHeadersConsumer.accept(trailerHeaders);
					}
					catch (IllegalArgumentException e) {
						// A sender MUST NOT generate a trailer when header names are
						// HttpServerOperations.TrailerHeaders.DISALLOWED_TRAILER_HEADER_NAMES
						log.error(format(channel(), "Cannot apply trailer headers [{}]"), declaredHeaderNames, e);
					}
				}
			}

			f = channel().writeAndFlush(trailerHeaders != null && !trailerHeaders.isEmpty() ?
					new DefaultLastHttpContent(channel().bufferAllocator().allocate(0), trailerHeaders) :
					new EmptyLastHttpContent(channel().bufferAllocator()));
		}
		else {
			discard();
			return;
		}
		f.addListener(s -> {
			discard();
			if (!s.isSuccess() && log.isDebugEnabled()) {
				log.debug(format(channel(), "Failed flushing last frame"), s.cause());
			}
		});

	}

	static void cleanHandlerTerminate(Channel ch) {
		ChannelOperations<?, ?> ops = get(ch);

		if (ops == null) {
			return;
		}

		ops.discard();

		//Try to defer the disposing to leave a chance for any synchronous complete following this callback
		if (!ops.isSubscriptionDisposed()) {
			ch.executor()
			  .execute(((HttpServerOperations) ops)::terminate);
		}
		else {
			//if already disposed, we can immediately call terminate
			((HttpServerOperations) ops).terminate();
		}
	}

	static long requestsCounter(Channel channel) {
		HttpServerOperations ops = Connection.from(channel).as(HttpServerOperations.class);

		if (ops == null) {
			return -1;
		}

		return ((AtomicLong) ops.connection()).get();
	}

	static void sendDecodingFailures(
			ChannelHandlerContext ctx,
			ConnectionObserver listener,
			boolean secure,
			Throwable t,
			Object msg,
			HttpMessageLogFactory httpMessageLogFactory,
			@Nullable ZonedDateTime timestamp,
			@Nullable ConnectionInfo connectionInfo,
			SocketAddress remoteAddress) {
		sendDecodingFailures(ctx, listener, secure, t, msg, httpMessageLogFactory, false, timestamp, connectionInfo, remoteAddress);
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	static void sendDecodingFailures(
			ChannelHandlerContext ctx,
			ConnectionObserver listener,
			boolean secure,
			Throwable t,
			Object msg,
			HttpMessageLogFactory httpMessageLogFactory,
			boolean isHttp2,
			@Nullable ZonedDateTime timestamp,
			@Nullable ConnectionInfo connectionInfo,
			SocketAddress remoteAddress) {

		Throwable cause = t.getCause() != null ? t.getCause() : t;

		if (log.isWarnEnabled()) {
			log.warn(format(ctx.channel(), "Decoding failed: {}"),
					msg instanceof HttpObject ?
							httpMessageLogFactory.warn(HttpMessageArgProviderFactory.create(msg)) : msg);
		}

		Resource.dispose(msg);

		final HttpResponseStatus status;
		if (cause instanceof TooLongHttpLineException) {
			status = HttpResponseStatus.REQUEST_URI_TOO_LONG;
		}
		else if (cause instanceof TooLongHttpHeaderException) {
			status = HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE;
		}
		else {
			status = HttpResponseStatus.BAD_REQUEST;
		}
		HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
				ctx.bufferAllocator().allocate(0));
		response.headers()
		        .set(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO)
		        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

		Connection ops = ChannelOperations.get(ctx.channel());
		if (ops == null) {
			Connection conn = Connection.from(ctx.channel());
			if (msg instanceof HttpRequest request) {
				ops = new FailedHttpServerRequest(conn, listener, request, response, httpMessageLogFactory, isHttp2,
						secure, timestamp == null ? ZonedDateTime.now(ReactorNetty.ZONE_ID_SYSTEM) : timestamp,
						connectionInfo == null ? new ConnectionInfo(ctx.channel().localAddress(), remoteAddress, secure) : connectionInfo);
				ops.bind();
			}
			else {
				ops = conn;
			}
		}

		ctx.channel().writeAndFlush(response);

		listener.onStateChange(ops, REQUEST_DECODING_FAILED);
	}

	/**
	 * There is no need of invoking {@link #discard()}, the inbound will
	 * be canceled on channel inactive event if there is no subscriber available.
	 *
	 * @param err the {@link Throwable} cause
	 */
	@Override
	protected void onOutboundError(Throwable err) {

		if (!channel().isActive()) {
			super.onOutboundError(err);
			return;
		}

		if (markSentHeaders()) {
			log.error(format(channel(), "Error starting response. Replying error status"), err);

			nettyResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
			responseHeaders.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
			channel().writeAndFlush(newFullBodyMessage(channel().bufferAllocator().allocate(0)))
			         .addListener(channel(), ChannelFutureListeners.CLOSE);
			return;
		}

		markSentBody();
		log.error(format(channel(), "Error finishing response. Closing connection"), err);
		channel().writeAndFlush(channel().bufferAllocator().allocate(0))
		         .addListener(channel(), ChannelFutureListeners.CLOSE);
	}

	@Override
	protected HttpMessage outboundHttpMessage() {
		return nettyResponse;
	}

	final Flux<HttpData> receiveFormInternal(HttpServerFormDecoderProvider config) {
		boolean isMultipart = isMultipart();
		if (!Objects.equals(method(), HttpMethod.POST) || !(isFormUrlencoded() || isMultipart)) {
			return Flux.error(new IllegalStateException(
					"Request is not POST or does not have Content-Type " +
							"with value 'application/x-www-form-urlencoded' or 'multipart/form-data'"));
		}
		return Flux.defer(() ->
				config.newHttpPostRequestDecoder(nettyRequest, isMultipart).flatMapMany(decoder ->
						receiveObject() // receiveContent uses filter operator, this operator buffers, but we don't want it
								.concatMap(object -> {
									if (!(object instanceof HttpContent<?> objHttpContent)) {
										return Mono.empty();
									}
									HttpContent<?> httpContent = config.maxInMemorySize > -1 ?
											objHttpContent.send().receive() : objHttpContent;

									return config.maxInMemorySize == -1 ?
											Flux.using(
													() -> decoder.offer(httpContent),
													d -> Flux.fromIterable(decoder.currentHttpData(!config.streaming)),
													d -> decoder.cleanCurrentHttpData(!config.streaming)) :
											Flux.usingWhen(
													Mono.fromCallable(() -> decoder.offer(httpContent))
													    .subscribeOn(config.scheduler)
													    .doFinally(sig -> httpContent.close()),
													d -> Flux.fromIterable(decoder.currentHttpData(true)),
													// FIXME Can we have cancellation for the resourceSupplier that will
													// cause this one to not be invoked?
													d -> Mono.fromRunnable(() -> decoder.cleanCurrentHttpData(true)));
								}, 0) // There is no need of prefetch, we already have the buffers in the Reactor Netty inbound queue
								.doFinally(sig -> decoder.destroy())));
	}

	final Mono<Void> withWebsocketSupport(String url,
			WebsocketServerSpec websocketServerSpec,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		Objects.requireNonNull(websocketServerSpec, "websocketServerSpec");
		Objects.requireNonNull(websocketHandler, "websocketHandler");
		if (markSentHeaders()) {
			WebsocketServerOperations ops = new WebsocketServerOperations(url, websocketServerSpec, this);

			return FutureMono.from(ops.handshakerResult)
			                 .doOnEach(signal -> {
			                     if (!signal.hasError() && (websocketServerSpec.protocols() == null || ops.selectedSubprotocol() != null)) {
			                         websocketHandler.apply(ops, ops)
			                                         .subscribe(new WebsocketSubscriber(ops, Context.of(signal.getContextView())));
			                     }
			                 });
		}
		else {
			log.error(format(channel(), "Cannot enable websocket if headers have already been sent"));
		}
		return Mono.error(new IllegalStateException("Failed to upgrade to websocket"));
	}

	static final class WebsocketSubscriber implements CoreSubscriber<Void>, FutureListener<Void> {
		final WebsocketServerOperations ops;
		final Context                context;

		WebsocketSubscriber(WebsocketServerOperations ops, Context context) {
			this.ops = ops;
			this.context = context;
		}

		@Override
		public void onSubscribe(Subscription s) {
			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(Void aVoid) {

		}

		@Override
		public void onError(Throwable t) {
			ops.onError(t);
		}

		@Override
		public void operationComplete(Future<? extends Void> future)  {
			ops.terminate();
		}

		@Override
		public void onComplete() {
			if (ops.channel()
			       .isActive()) {
				ops.sendCloseNow(new CloseWebSocketFrame(ops.channel().bufferAllocator(),
						WebSocketCloseStatus.NORMAL_CLOSURE), this);
			}
		}

		@Override
		public Context currentContext() {
			return context;
		}
	}

	static final Logger log = Loggers.getLogger(HttpServerOperations.class);
	static final AsciiString      EVENT_STREAM = new AsciiString("text/event-stream");

	static final BiPredicate<HttpServerRequest, HttpServerResponse> COMPRESSION_DISABLED = (req, res) -> false;

	static final class FailedHttpServerRequest extends HttpServerOperations {

		final HttpResponse customResponse;

		FailedHttpServerRequest(
				Connection c,
				ConnectionObserver listener,
				HttpRequest nettyRequest,
				HttpResponse nettyResponse,
				HttpMessageLogFactory httpMessageLogFactory,
				boolean isHttp2,
				boolean secure,
				ZonedDateTime timestamp,
				ConnectionInfo connectionInfo) {
			super(c, listener, nettyRequest, null, connectionInfo,
					DEFAULT_FORM_DECODER_SPEC, httpMessageLogFactory, isHttp2, null, null, null, false, secure, timestamp);
			this.customResponse = nettyResponse;
			String tempPath = "";
			try {
				tempPath = resolvePath(nettyRequest.uri());
			}
			catch (RuntimeException e) {
				tempPath = "/bad-request";
			}
			finally {
				this.path = tempPath;
			}
		}

		@Override
		protected HttpMessage outboundHttpMessage() {
			return customResponse;
		}

		@Override
		public HttpResponseStatus status() {
			return customResponse.status();
		}
	}


	final class RequestTimeoutTask implements Runnable {

		final ChannelHandlerContext ctx;

		RequestTimeoutTask(ChannelHandlerContext ctx) {
			this.ctx = ctx;
		}

		@Override
		public void run() {
			if (ctx.channel().isActive() && !(isInboundCancelled() || isInboundDisposed())) {
				onInboundError(RequestTimeoutException.INSTANCE);
				ctx.close();
			}
		}
	}

	static final class TrailerHeaders extends DefaultHttpHeaders {

		static final Set<String> DISALLOWED_TRAILER_HEADER_NAMES = new HashSet<>(14);
		static {
			// https://datatracker.ietf.org/doc/html/rfc7230#section-4.1.2
			// A sender MUST NOT generate a trailer that contains a field necessary
			// for message framing (e.g., Transfer-Encoding and Content-Length),
			// routing (e.g., Host), request modifiers (e.g., controls and
			// conditionals in Section 5 of [RFC7231]), authentication (e.g., see
			// [RFC7235] and [RFC6265]), response control data (e.g., see Section
			// 7.1 of [RFC7231]), or determining how to process the payload (e.g.,
			// Content-Encoding, Content-Type, Content-Range, and Trailer).
			DISALLOWED_TRAILER_HEADER_NAMES.add("age");
			DISALLOWED_TRAILER_HEADER_NAMES.add("cache-control");
			DISALLOWED_TRAILER_HEADER_NAMES.add("content-encoding");
			DISALLOWED_TRAILER_HEADER_NAMES.add("content-length");
			DISALLOWED_TRAILER_HEADER_NAMES.add("content-range");
			DISALLOWED_TRAILER_HEADER_NAMES.add("content-type");
			DISALLOWED_TRAILER_HEADER_NAMES.add("date");
			DISALLOWED_TRAILER_HEADER_NAMES.add("expires");
			DISALLOWED_TRAILER_HEADER_NAMES.add("location");
			DISALLOWED_TRAILER_HEADER_NAMES.add("retry-after");
			DISALLOWED_TRAILER_HEADER_NAMES.add("trailer");
			DISALLOWED_TRAILER_HEADER_NAMES.add("transfer-encoding");
			DISALLOWED_TRAILER_HEADER_NAMES.add("vary");
			DISALLOWED_TRAILER_HEADER_NAMES.add("warning");
		}

		/**
		 * Contains the headers names specified with {@link HttpHeaderNames#TRAILER}.
		 */
		final Set<String> declaredHeaderNames;

		TrailerHeaders(String declaredHeaderNames) {
			super(16, true, true, true);
			this.declaredHeaderNames = filterHeaderNames(declaredHeaderNames);
		}

		@Override
		protected CharSequence validateKey(@Nullable CharSequence name, boolean forAdd) {
			if (name == null || !declaredHeaderNames.contains(name.toString())) {
				throw new IllegalArgumentException("Trailer header name [" + name +
						"] not declared with [Trailer] header, or it is not a valid trailer header name");
			}
			return super.validateKey(name, forAdd);
		}

		static Set<String> filterHeaderNames(String declaredHeaderNames) {
			Objects.requireNonNull(declaredHeaderNames, "declaredHeaderNames");
			Set<String> result = new HashSet<>();
			String[] names = declaredHeaderNames.split(",", -1);
			for (String name : names) {
				String trimmedStr = name.trim();
				if (trimmedStr.isEmpty() ||
						DISALLOWED_TRAILER_HEADER_NAMES.contains(trimmedStr.toLowerCase(Locale.ENGLISH))) {
					continue;
				}
				result.add(trimmedStr);
			}
			return result;
		}
	}
}
