package dev.proxyprotocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class ProxyDecoder extends ByteToMessageDecoder {
    interface AddressWriter { void set(InetSocketAddress source) throws Exception; }
    private final int maximum;
    private final long timeoutMillis;
    private final AddressWriter writer;
    private final Consumer<Throwable> failure;
    private ScheduledFuture<?> timeout;
    private boolean complete;

    ProxyDecoder(int maximum, long timeoutMillis, AddressWriter writer, Consumer<Throwable> failure) {
        this.maximum = maximum; this.timeoutMillis = timeoutMillis; this.writer = writer; this.failure = failure;
    }

    @Override public void handlerAdded(final ChannelHandlerContext ctx) {
        if (ctx.channel().isRegistered()) startTimeout(ctx);
    }

    @Override public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        startTimeout(ctx);
        super.channelRegistered(ctx);
    }

    private void startTimeout(final ChannelHandlerContext ctx) {
        if (timeout != null || timeoutMillis <= 0) return;
        timeout = ctx.executor().schedule(new Runnable() {
            @Override public void run() { if (!complete) ctx.close(); }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (complete) return;
        ProxyHeader header = ProxyHeader.parse(in, maximum);
        if (header == null) return;
        if (header.source != null) writer.set(header.source);
        in.skipBytes(header.length);
        complete = true;
        cancelTimeout();
        // Removal forwards the remaining cumulation exactly once, including coalesced handshake bytes.
        ctx.pipeline().remove(this);
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelTimeout();
        super.channelInactive(ctx);
    }

    @Override public void handlerRemoved0(ChannelHandlerContext ctx) { cancelTimeout(); }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        complete = true;
        cancelTimeout();
        failure.accept(cause);
        ctx.close();
    }

    private void cancelTimeout() { if (timeout != null) timeout.cancel(false); }
}
