package com.smsframework.dlr.security;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** IPv4 / IPv6 address and CIDR matching without DNS lookups. */
public final class IpAllowlist {

    private record Range(byte[] network, int prefix) {
    }

    private final List<Range> ranges;

    public IpAllowlist(List<String> cidrs) {
        this.ranges = cidrs.stream().map(String::trim).filter(s -> !s.isEmpty()).map(IpAllowlist::parse).toList();
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    public boolean allows(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        byte[] addr;
        try {
            addr = literal(ip.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Range r : ranges) {
            if (r.network.length == addr.length && matches(r, addr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Range r, byte[] addr) {
        int bits = r.network.length * 8;
        BigInteger mask = r.prefix == 0 ? BigInteger.ZERO
                : BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
                .xor(BigInteger.ONE.shiftLeft(bits - r.prefix).subtract(BigInteger.ONE));
        return new BigInteger(1, r.network).and(mask).equals(new BigInteger(1, addr).and(mask));
    }

    private static Range parse(String cidr) {
        String[] parts = cidr.split("/", 2);
        byte[] net = literal(parts[0]);
        int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : net.length * 8;
        if (prefix < 0 || prefix > net.length * 8) {
            throw new IllegalStateException("Invalid CIDR prefix in ip allowlist: " + cidr);
        }
        return new Range(net, prefix);
    }

    /** Parses an IP literal only (never resolves host names). */
    private static byte[] literal(String ip) {
        boolean looksLikeIp = ip.contains(":") || ip.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (!looksLikeIp) {
            throw new IllegalArgumentException("Not an IP literal: " + ip);
        }
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            // Treat IPv4-mapped IPv6 (::ffff:a.b.c.d) as IPv4
            if (b.length == 16 && isV4Mapped(b)) {
                byte[] v4 = new byte[4];
                System.arraycopy(b, 12, v4, 0, 4);
                return v4;
            }
            return b;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP: " + ip, e);
        }
    }

    private static boolean isV4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }
}
