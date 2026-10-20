package dev.proxyprotocol;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

final class ConnectionAddress {
    static void set(Channel channel, InetSocketAddress source) throws ReflectiveOperationException {
        List<Target> targets = new ArrayList<Target>();
        for (String name : channel.pipeline().names()) {
            ChannelHandler handler = channel.pipeline().get(name);
            if (handler == null) continue;
            List<Field> fields = fields(handler.getClass());
            boolean ownsChannel = false;
            for (Field field : fields) {
                if (Channel.class.isAssignableFrom(field.getType()) && read(field, handler) == channel) ownsChannel = true;
            }
            if (!ownsChannel) continue;
            for (Field field : fields) {
                if (SocketAddress.class.isAssignableFrom(field.getType()) && !Modifier.isFinal(field.getModifiers())
                        && field.getType().isAssignableFrom(source.getClass())
                        && channel.remoteAddress().equals(read(field, handler))) targets.add(new Target(handler, field));
            }
        }
        if (targets.size() != 1) throw new IllegalStateException("Expected one connection address field; found " + targets.size());
        Target target = targets.get(0);
        target.field.set(target.owner, source);
        if (!source.equals(target.field.get(target.owner))) throw new IllegalStateException("Address update did not persist");
    }

    static List<Field> fields(Class<?> type) {
        List<Field> fields = new ArrayList<Field>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) fields.add(field);
        }
        return fields;
    }

    static Object read(Field field, Object owner) throws IllegalAccessException {
        field.setAccessible(true);
        return field.get(owner);
    }

    private static final class Target {
        final Object owner;
        final Field field;
        Target(Object owner, Field field) { this.owner = owner; this.field = field; }
    }
}
