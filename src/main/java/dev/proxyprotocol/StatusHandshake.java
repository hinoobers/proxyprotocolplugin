package dev.proxyprotocol;

import io.netty.buffer.ByteBuf;

/** Recognizes only a complete, bounded modern Minecraft status handshake. */
final class StatusHandshake {
    private StatusHandshake() { }

    // Zero means more bytes are needed. Never consumes the caller's buffer.
    static int length(ByteBuf input) {
        ByteBuf in = input.duplicate();
        int start = in.readerIndex();
        int size = 0, count = 0;
        while (true) {
            if (!in.isReadable()) return 0;
            int b = in.readUnsignedByte();
            size |= (b & 127) << (count++ * 7);
            if (count > 2 || size > 1030) throw invalid();
            if ((b & 128) == 0) break;
        }
        if (size < 6) throw invalid();
        if (in.isReadable() && in.getUnsignedByte(in.readerIndex()) != 0) throw invalid();
        if (in.readableBytes() < size) return 0;
        int end = in.readerIndex() + size;
        in.writerIndex(end);
        try {
            if (varint(in) != 0) throw invalid();
            varint(in); // Client protocol version, including -1 used by status probes.
            int hostnameBytes = varint(in);
            if (hostnameBytes < 0 || hostnameBytes > 1020 || in.readableBytes() < hostnameBytes + 3) throw invalid();
            in.skipBytes(hostnameBytes + 2); // Hostname and unsigned port.
            if (varint(in) != 1 || in.isReadable()) throw invalid();
            return end - start;
        } catch (IndexOutOfBoundsException e) { throw invalid(); }
    }

    private static int varint(ByteBuf in) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.readUnsignedByte();
            if (i == 4 && (b & 0xf0) != 0) throw invalid();
            value |= (b & 127) << (7 * i);
            if ((b & 128) == 0) return value;
        }
        throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Missing PROXY header (only headerless status handshakes are allowed)");
    }
}
