package com.hairyharri.gameye.sync;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.net.URI;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.stream.ChunkedWriteHandler;

public class SyncStreamManager {

    public SyncStreamManager(@NonNull String url,
                             @NonNull SyncDataInterface dataInterface) {
        this.url = url;
        this.dataInterface = dataInterface;
    }

    public void startStreaming(@NonNull String token) {
        URI uri = null;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            signalError("Error constructing URL", e);
            return;
        }

        String host = uri.getHost();
        boolean https = uri.getScheme().equals("https");
        int port = uri.getPort() == -1 ? (https ? 443 : 80) : uri.getPort();
        String path = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");

        eventGroup = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // Add SSL handler for HTTPS
                        if (https) {
                            SslContext sslContext;
                            try {
                                sslContext = SslContextBuilder.forClient().build();
                            } catch (Exception e) {
                                signalError("Error constructing SSL context", e);
                                return;
                            }
                            pipeline.addLast(sslContext.newHandler(ch.alloc(), host, port));
                        }

                        pipeline.addLast(new HttpClientCodec());
                        pipeline.addLast(new ChunkedWriteHandler());     // Handles chunked data
                        pipeline.addLast(new FullDuplexClientHandler()); // Custom handler
                    }
                });

        // Connect to the server
        try {
            writeChannel = bootstrap.connect(host, port).sync().channel();
        } catch (Exception e) {
            signalError("Error constructing writeChannel", e);
            return;
        }

        // Send the initial HTTP request
        HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, path);
        request.headers().set(HttpHeaderNames.HOST, host);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        request.headers().set("Authorization", "Bearer " + token);

        writeChannel.writeAndFlush(request).addListener((listener) -> {
            if (listener.isSuccess()) {
                // Signals to listener that we can begin sending data via the writeData method
                dataInterface.onReady();
            } else {
                signalError("Error connecting to server", listener.cause());
            }
        });
    }

    public void stopStreaming() {
        finishWriteStream();
    }

    public boolean writeData(String data) {
        if (writeChannel == null)
            return false;

        if (!writeChannel.isWritable()) {
            signalError("Write channel is not writable; possible backpressure", null);
            return false;
        }

        try {
            writeChannel.write(new DefaultHttpContent(Unpooled.wrappedBuffer(data.getBytes())))
                    .addListener((future) -> {
                        if (!future.isSuccess()) {
                            signalError("Error writing data", future.cause());
                        }
                    });
        } catch (Exception e) {
            signalError("Exception writing data", e);
            return false;
        }
        return true;
    }

    public boolean flush() {
        if (writeChannel == null)
            return false;
        writeChannel.flush();
        return true;
    }

    private class FullDuplexClientHandler extends SimpleChannelInboundHandler<HttpObject> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            if (msg instanceof HttpResponse response) {
                Log.d(TAG, "Response status: " + response.status());
                if (response.status().code() < 200 || response.status().code() >= 300) {
                    signalError("Got response:" + response.status().toString(), null);
                }
            } else if (msg instanceof HttpContent content) {
                String data = content.content().toString(io.netty.util.CharsetUtil.UTF_8);
                Log.d(TAG, "Received content: " + data);

                if (content instanceof LastHttpContent) {
                    Log.d(TAG, "End of content");
                    gracefulFinish();
                    return;
                }

                dataInterface.onDataReceived(data);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            signalError("Error mid flight", cause);
            ctx.close();
            FirebaseCrashlytics.getInstance().recordException(cause);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            gracefulFinish();
        }
    }

    private void signalError(String msg, Throwable e) {
        if (e != null)
            dataInterface.onFinished(msg + ":\n" + e.getLocalizedMessage() + "\n" +
                    "File:" + e.getStackTrace()[0].getFileName() +"\nLine:" + e.getStackTrace()[0].getLineNumber());
        else
            dataInterface.onFinished(msg);
        finishWriteStream();
        close();
    }

    private void gracefulFinish() {
        if (!shutDown) {
            dataInterface.onFinished(null); // Signal success
            close();
        }
    }

    private void finishWriteStream() {
        Log.d(TAG, "Finishing the sync write stream");
        if (writeChannel != null) {
            // End the request
            writeChannel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        }
    }

    private void close() {
        Log.d(TAG, "Closing the sync event group and the write channel");
        if (writeChannel != null) {
            try {
                writeChannel.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (eventGroup != null) {
            eventGroup.shutdownGracefully();
        }

        writeChannel = null;
        eventGroup = null;
        shutDown = true;
    }

    EventLoopGroup eventGroup;
    Channel writeChannel = null;
    private boolean shutDown = false;

    private final String url;
    private final SyncDataInterface dataInterface;
    private final String TAG = "SyncStreamManager";
}
