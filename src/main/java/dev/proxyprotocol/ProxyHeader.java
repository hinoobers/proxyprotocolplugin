package dev.proxyprotocol;

import io.netty.buffer.ByteBuf;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

final class ProxyHeader {
    private static final byte[] V1 = "PROXY ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] V2 = {13, 10, 13, 10, 0, 13, 10, 81, 85, 73, 84, 10};
    final int length;
    final InetSocketAddress source; // null for UNKNOWN / LOCAL

    private ProxyHeader(int length, InetSocketAddress source) {
        this.length = length;
        this.source = source;
    }

    static ProxyHeader parse(ByteBuf in, int maximum) {
        int start = in.readerIndex(), size = in.readableBytes();
        if (size == 0) return null;
        byte[] signature = in.getByte(start) == 'P' ? V1 : V2;
        for (int i = 0; i < Math.min(size, signature.length); i++) {
            if (in.getByte(start + i) != signature[i]) throw invalid("Missing PROXY header");
        }
        if (size < signature.length) return null;
        if (signature == V1) {
            int limit = Math.min(Math.min(size, 107), maximum);
            for (int i = V1.length; i < limit - 1; i++) {
                if (in.getByte(start + i) != 13 || in.getByte(start + i + 1) != 10) continue;
                String line = in.toString(start, i, StandardCharsets.US_ASCII);
                String[] words = line.split(" ", -1);
                if (words.length >= 2 && words[1].equals("UNKNOWN")) return new ProxyHeader(i + 2, null);
                if (words.length != 6 || !(words[1].equals("TCP4") || words[1].equals("TCP6"))) throw invalid("Invalid v1 fields");
                boolean ipv6 = words[1].equals("TCP6");
                InetAddress source = IpNetwork.literal(words[2]), destination = IpNetwork.literal(words[3]);
                // Also allow IPv4-mapped IPv6, which Java normalizes to four bytes.
                if ((words[2].indexOf(':') >= 0) != ipv6 || (words[3].indexOf(':') >= 0) != ipv6) throw invalid("Address family mismatch");
                port(words[5]);
                return new ProxyHeader(i + 2, new InetSocketAddress(source, port(words[4])));
            }
            if (size >= Math.min(107, maximum)) throw invalid("v1 header too long");
            return null;
        }
        if (size < 16) return null;
        int versionCommand = in.getUnsignedByte(start + 12), family = in.getUnsignedByte(start + 13);
        int payload = in.getUnsignedShort(start + 14), total = 16 + payload;
        if ((versionCommand >> 4) != 2 || (versionCommand & 15) > 1) throw invalid("Invalid v2 version/command");
        if (total > maximum) throw invalid("v2 header exceeds configured limit");
        if (size < total) return null;
        if ((versionCommand & 15) == 0) return new ProxyHeader(total, null); // LOCAL ignores protocol/address block
        if (family == 0) return new ProxyHeader(total, null); // UNSPEC
        int addressBytes = family == 0x11 ? 4 : family == 0x21 ? 16 : 0;
        if (addressBytes == 0) throw invalid("Only TCP over IPv4/IPv6 is supported");
        int addressBlock = 2 * addressBytes + 4;
        if (payload < addressBlock) throw invalid("Truncated v2 address block");
        // Unknown TLVs are ignored, but their framing must fit inside the header.
        int cursor = start + 16 + addressBlock, end = start + total, checksumAt = -1;
        while (cursor < end) {
            if (end - cursor < 3) throw invalid("Truncated TLV");
            int length = in.getUnsignedShort(cursor + 1);
            cursor += 3;
            if (length > end - cursor) throw invalid("TLV exceeds header");
            if (in.getUnsignedByte(cursor - 3) == 3) {
                if (length != 4 || checksumAt != -1) throw invalid("Invalid CRC32C TLV");
                checksumAt = cursor;
            }
            cursor += length;
        }
        if (checksumAt != -1 && in.getInt(checksumAt) != crc32c(in, start, total, checksumAt)) throw invalid("CRC32C mismatch");
        byte[] bytes = new byte[addressBytes];
        in.getBytes(start + 16, bytes);
        try {
            return new ProxyHeader(total, new InetSocketAddress(InetAddress.getByAddress(bytes), in.getUnsignedShort(start + 16 + addressBytes * 2)));
        } catch (UnknownHostException e) { throw new IllegalArgumentException(e); }
    }

    private static int port(String text) {
        if (!text.matches("0|[1-9][0-9]{0,4}")) throw invalid("Invalid port");
        int value = Integer.parseInt(text);
        if (value > 65535) throw invalid("Port out of range");
        return value;
    }

    // Castagnoli CRC, available here on Java 8 as well as newer runtimes.
    static int crc32c(ByteBuf buffer, int start, int length, int checksumAt) {
        int crc = -1;
        for (int i = start; i < start + length; i++) {
            crc ^= i >= checksumAt && i < checksumAt + 4 ? 0 : buffer.getUnsignedByte(i);
            for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0x82f63b78);
        }
        return ~crc;
    }

    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
