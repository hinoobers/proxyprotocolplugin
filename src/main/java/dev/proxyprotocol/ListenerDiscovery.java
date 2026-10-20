package dev.proxyprotocol;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ServerChannel;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;

final class ListenerDiscovery {
    static List<Channel> find(Object server, int port) {
        Queue<Node> queue = new ArrayDeque<Node>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        List<Channel> result = new ArrayList<Channel>();
        queue.add(new Node(server, 0));
        while (!queue.isEmpty() && visited.size() < 512) {
            Node node = queue.remove();
            if (node.value == null || !visited.add(node.value)) continue;
            if (node.value instanceof ChannelFuture) {
                Channel channel = ((ChannelFuture) node.value).channel();
                if (channel instanceof ServerChannel && channel.isActive() && channel.localAddress() instanceof InetSocketAddress
                        && ((InetSocketAddress) channel.localAddress()).getPort() == port && !result.contains(channel)) result.add(channel);
                continue;
            }
            if (node.value instanceof List) {
                // Server connection lists may be synchronized and modified by Netty threads.
                synchronized (node.value) {
                    for (Object item : (List<?>) node.value) if (item instanceof ChannelFuture) queue.add(new Node(item, node.depth));
                }
                continue;
            }
            String name = node.value.getClass().getName();
            if (node.depth > 3 || name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("io.netty.")) continue;
            for (Field field : ConnectionAddress.fields(node.value.getClass())) {
                if (field.getType().isPrimitive() || field.getType().isArray()) continue;
                try {
                    Object value = ConnectionAddress.read(field, node.value);
                    if (value != null) queue.add(new Node(value, node.depth + 1));
                } catch (RuntimeException | IllegalAccessException ignored) {
                    // Unrelated encapsulated fields need not be accessible. An empty result is fatal to startup.
                }
            }
        }
        return result;
    }

    private static final class Node {
        final Object value; final int depth;
        Node(Object value, int depth) { this.value = value; this.depth = depth; }
    }
}
