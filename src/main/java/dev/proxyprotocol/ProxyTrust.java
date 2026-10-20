package dev.proxyprotocol;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ProxyTrust {
    private final boolean local;
    private final Set<InetAddress> localAddresses;
    private final List<IpNetwork> configured = new ArrayList<IpNetwork>();

    ProxyTrust(boolean local, Collection<String> networks, Collection<InetAddress> localAddresses) {
        this.local = local;
        this.localAddresses = new HashSet<InetAddress>(localAddresses);
        for (String network : networks) configured.add(new IpNetwork(network));
        if (!local && configured.isEmpty()) throw new IllegalArgumentException(
            "Enable trust-local-addresses or set trusted-proxies in plugins/RealIP/config.yml");
    }

    static ProxyTrust discover(boolean local, Collection<String> networks) throws SocketException {
        Set<InetAddress> addresses = new HashSet<InetAddress>();
        if (local) {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface nic : Collections.list(interfaces)) {
                    for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                        if (!address.isAnyLocalAddress()) addresses.add(address);
                    }
                }
            }
        }
        return new ProxyTrust(local, networks, addresses);
    }

    boolean contains(InetAddress peer) {
        if (peer == null || peer.isAnyLocalAddress()) return false;
        if (local && (peer.isLoopbackAddress() || localAddresses.contains(peer))) return true;
        for (IpNetwork network : configured) if (network.contains(peer)) return true;
        return false;
    }
}
