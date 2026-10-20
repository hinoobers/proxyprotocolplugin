package dev.proxyprotocol;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

public final class RealIpPlugin extends JavaPlugin {
    private static final String ACCEPTOR = "realip_acceptor";
    private final List<Channel> listeners = new ArrayList<Channel>();
    private final Set<Channel> pending = Collections.newSetFromMap(new ConcurrentHashMap<Channel, Boolean>());
    private final AtomicLong lastWarning = new AtomicLong();
    private final AtomicLong lastUntrustedWarning = new AtomicLong();
    private ProxyTrust trusted;
    private volatile boolean stopping;
    private int maximum;
    private long timeout;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            trusted = ProxyTrust.discover(getConfig().getBoolean("trust-local-addresses", true),
                getConfig().getStringList("trusted-proxies"));
            maximum = getConfig().getInt("max-header-bytes", 4096);
            timeout = getConfig().getLong("header-timeout-millis", 5000);
            if (maximum < 107 || maximum > 65551 || timeout < 100 || timeout > 60000) throw new IllegalArgumentException("Invalid header size or timeout");
            if (!ListenerDiscovery.find(getServer(), getServer().getPort()).isEmpty()) {
                install();
            } else {
                // Some implementations bind after STARTUP plugins. Retry once after initialization.
                getServer().getScheduler().runTask(this, new Runnable() {
                    @Override public void run() { install(); }
                });
            }
        } catch (Exception e) { fatal(e); }
    }

    private void install() {
        try {
            List<Channel> found = ListenerDiscovery.find(getServer(), getServer().getPort());
            if (found.isEmpty()) throw new IllegalStateException("No bound TCP listener found; this server implementation is unsupported");
            for (final Channel listener : found) {
                listener.eventLoop().submit(new Runnable() {
                    @Override public void run() {
                        if (listener.pipeline().get(ACCEPTOR) != null) throw new IllegalStateException("Listener already injected");
                        listener.pipeline().addFirst(ACCEPTOR, new Acceptor());
                    }
                }).syncUninterruptibly();
                listeners.add(listener);
            }
            getLogger().info("Ready: PROXY v1/v2 installed on " + listeners.size() + " listener(s). Only trusted proxies may connect."
                + (getConfig().getBoolean("trust-local-addresses", true) ? " Local machine addresses are trusted automatically." : ""));
        } catch (Exception e) { fatal(e); }
    }

    private final class Acceptor extends ChannelInboundHandlerAdapter {
        @Override public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
            if (!(message instanceof Channel)) { ctx.fireChannelRead(message); return; }
            final Channel child = (Channel) message;
            if (stopping || !(child.remoteAddress() instanceof InetSocketAddress)) { reject(child); return; }
            InetSocketAddress peer = (InetSocketAddress) child.remoteAddress();
            if (!isTrusted(peer)) {
                warnUntrusted(peer);
                reject(child);
                return;
            }
            pending.add(child);
            child.closeFuture().addListener(new ChannelFutureListener() {
                @Override public void operationComplete(ChannelFuture future) { pending.remove(child); }
            });
            // Appended after the server's initializer, but adds the decoder BEFORE all Minecraft handlers.
            child.pipeline().addLast(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(final Channel channel) {
                    if (stopping) { channel.close(); return; }
                    for (String name : channel.pipeline().names()) {
                        ChannelHandler handler = channel.pipeline().get(name);
                        if (handler != null && handler.getClass().getName().equals("io.netty.handler.codec.haproxy.HAProxyMessageDecoder")) {
                            warn(new IllegalStateException("Disable the server's native PROXY protocol option when using RealIP"));
                            channel.close(); return;
                        }
                    }
                    channel.pipeline().addFirst("realip_header", new ProxyDecoder(maximum, timeout,
                        source -> ConnectionAddress.set(channel, source), RealIpPlugin.this::warn));
                }
            });
            ctx.fireChannelRead(message);
        }
    }

    private boolean isTrusted(InetSocketAddress address) {
        return trusted.contains(address.getAddress());
    }

    private void warnUntrusted(InetSocketAddress peer) {
        long now = System.currentTimeMillis(), previous = lastUntrustedWarning.get();
        if (now - previous >= 10000 && lastUntrustedWarning.compareAndSet(previous, now)) {
            getLogger().warning("Received proxy connection from untrusted address " + peer
                + ". Connection rejected. Check trusted-proxies in plugins/RealIP/config.yml"
                + " (warnings limited to one per 10 seconds).");
        }
    }

    private static void reject(Channel channel) {
        // At accept time the child has no event loop yet on Netty 4.0.
        // This is the same forced-close path Netty uses when child registration fails.
        if (channel.isRegistered()) channel.close();
        else channel.unsafe().closeForcibly();
    }

    private void warn(Throwable cause) {
        long now = System.currentTimeMillis(), previous = lastWarning.get();
        if (now - previous >= 10000 && lastWarning.compareAndSet(previous, now)) {
            getLogger().log(Level.WARNING, "Rejected a PROXY connection (warnings limited to one per 10 seconds)", cause);
        }
    }

    private void fatal(Exception cause) {
        getLogger().log(Level.SEVERE, "RealIP could not install. Shutting down to avoid accepting clients with incorrect addresses.", cause);
        getServer().shutdown();
    }

    @Override public void onDisable() {
        stopping = true;
        for (final Channel listener : listeners) {
            if (!listener.isOpen()) continue;
            listener.eventLoop().submit(new Runnable() {
                @Override public void run() {
                    if (listener.pipeline().get(ACCEPTOR) != null) listener.pipeline().remove(ACCEPTOR);
                }
            }).syncUninterruptibly();
        }
        for (Channel channel : pending) reject(channel);
        pending.clear();
        listeners.clear();
    }
}
