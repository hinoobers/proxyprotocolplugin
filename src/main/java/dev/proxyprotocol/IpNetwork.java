package dev.proxyprotocol;

import java.net.InetAddress;
import java.net.UnknownHostException;

final class IpNetwork {
    private final byte[] network;
    private final int bits;

    IpNetwork(String cidr) {
        String[] parts = cidr.split("/", -1);
        if (parts.length > 2) throw new IllegalArgumentException("Invalid CIDR: " + cidr);
        network = literal(parts[0]).getAddress();
        bits = parts.length == 1 ? network.length * 8 : Integer.parseInt(parts[1]);
        if (bits < 0 || bits > network.length * 8) throw new IllegalArgumentException("Invalid prefix: " + cidr);
    }

    boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) return false;
        for (int i = 0; i < bits; i++) {
            int mask = 1 << (7 - i % 8);
            if ((candidate[i / 8] & mask) != (network[i / 8] & mask)) return false;
        }
        return true;
    }

    static InetAddress literal(String value) {
        try {
            if (value.indexOf(':') >= 0) {
                // getByName cannot perform DNS for this restricted IPv6 grammar.
                if (!value.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Invalid IPv6 literal");
                return InetAddress.getByName(value);
            }
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Expected numeric IP: " + value);
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("Invalid IPv4 literal");
                int n = Integer.parseInt(parts[i]);
                if (n > 255) throw new IllegalArgumentException("Invalid IPv4 octet");
                bytes[i] = (byte) n;
            }
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP literal: " + value, e);
        }
    }
}
