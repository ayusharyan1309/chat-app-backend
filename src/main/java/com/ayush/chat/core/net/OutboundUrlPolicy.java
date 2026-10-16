package com.ayush.chat.core.net;

import com.ayush.chat.core.exception.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Guards outbound requests to tenant-supplied URLs (webhooks, push webhooks, JWKS) against SSRF:
 * only http(s), and no loopback, private, link-local (cloud metadata), CGNAT or multicast targets
 * unless {@code platform.security.allow-private-network-callbacks} is enabled (e.g. local development).
 */
@Component
public class OutboundUrlPolicy {

    private final boolean allowPrivate;

    public OutboundUrlPolicy(@Value("${platform.security.allow-private-network-callbacks:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    public URI check(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw DomainException.badRequest("Invalid URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw DomainException.badRequest("URL must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw DomainException.badRequest("URL must include a host");
        }
        if (allowPrivate) {
            return uri;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw DomainException.badRequest("Host cannot be resolved: " + uri.getHost());
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw DomainException.badRequest("URL resolves to a private or internal address (" + address.getHostAddress()
                        + "). Set platform.security.allow-private-network-callbacks=true to allow internal targets.");
            }
        }
        return uri;
    }

    static boolean isInternal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            return (b[0] & 0xFE) == 0xFC; // fc00::/7 unique local
        }
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return (first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 carrier-grade NAT
                || first == 0 || first >= 240;                   // "this network" and reserved
    }
}
