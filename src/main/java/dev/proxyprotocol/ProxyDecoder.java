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
    private int statusStage;

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
        if (statusStage != 0) {
            decodeStatus(in, out);
            return;
        }
        if (complete) return;
        if (!ProxyHeader.hasSignaturePrefix(in)) {
            int length = StatusHandshake.length(in);
            if (length == 0) return;
            // Keep a status-only filter installed: this exception must never allow login.
            statusStage = 1;
            complete = true;
            cancelTimeout();
            out.add(in.readBytes(length));
            return;
        }
        ProxyHeader header = ProxyHeader.parse(in, maximum);
        if (header == null) return;
        if (header.source != null) writer.set(header.source);
        in.skipBytes(header.length);
        complete = true;
        cancelTimeout();
        // Removal forwards the remaining cumulation exactly once, including coalesced handshake bytes.
        ctx.pipeline().remove(this);
    }

    private void decodeStatus(ByteBuf in, List<Object> out) {
        if (!in.isReadable()) return;
        int payload = statusStage == 1 ? 1 : 9;
        int id = statusStage == 1 ? 0 : 1;
        if (statusStage > 2 || in.getUnsignedByte(in.readerIndex()) != payload
            || (in.readableBytes() > 1 && in.getUnsignedByte(in.readerIndex() + 1) != id)) {
            throw new IllegalArgumentException("Only status request/ping allowed without PROXY header");
        }
        if (in.readableBytes() < payload + 1) return;
        out.add(in.readBytes(payload + 1));
        statusStage++;
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelTimeout();
        super.channelInactive(ctx);
    }

    @Override public void handlerRemoved0(ChannelHandlerContext ctx) { cancelTimeout(); }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        statusStage = 0;
        complete = true;
        cancelTimeout();
        failure.accept(cause);
        ctx.close();
    }

    private void cancelTimeout() { if (timeout != null) timeout.cancel(false); }
}
