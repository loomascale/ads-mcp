package com.loomascale.mcp.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

// Decides whether this server is willing to make an HTTP request to a URL that
// arrived from outside — today the creative image URL an assistant passes to
// meta_create_ad. Everything here is policy, no I/O beyond one DNS lookup, so the
// whole rule set is unit-testable through the injected HostResolver.
//
// Fails closed everywhere: an unparseable URL, a host that resolves to nothing, or
// a host where any single resolved address is blocked all end in a rejection.
// Rejection messages name the host and never the resolved address, so a caller
// cannot use this as an oracle for internal network topology.
@Slf4j
public class OutboundUrlPolicy {

  private static final int MAX_URL_LENGTH = 2048;
  private static final String HTTPS = "https";
  private static final String HTTP = "http";

  private final HostResolver hostResolver;
  private final boolean allowHttp;
  private final Set<String> blockedHosts;

  public OutboundUrlPolicy(
      HostResolver hostResolver,
      @Value("${mcp.image-upload.allow-http:false}") boolean allowHttp,
      @Value("${mcp.image-upload.blocked-hosts:}") String blockedHosts) {
    this.hostResolver = hostResolver;
    this.allowHttp = allowHttp;
    this.blockedHosts =
        Arrays.stream(blockedHosts.split(","))
            .map(String::trim)
            .filter(host -> !host.isBlank())
            .map(host -> host.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
  }

  public void check(URI uri) {
    if (uri == null || uri.toString().length() > MAX_URL_LENGTH) {
      throw new OutboundUrlRejectedException("The image URL is missing or too long.");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!HTTPS.equals(scheme) && !(allowHttp && HTTP.equals(scheme))) {
      throw new OutboundUrlRejectedException(
          "Only https image URLs can be fetched, but this one uses \""
              + (scheme.isBlank() ? "no scheme" : scheme)
              + "\".");
    }
    // https://evil@internal-host/ — the userinfo makes the real host easy to miss.
    if (uri.getUserInfo() != null) {
      throw new OutboundUrlRejectedException("Image URLs must not embed credentials.");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new OutboundUrlRejectedException("The image URL has no host.");
    }
    // A single rule that removes most internal-service probing: everything
    // interesting to an attacker (6379, 8080, 9200, 2375...) is a non-web port.
    int port = uri.getPort();
    if (port != -1 && port != 443 && !(allowHttp && port == 80)) {
      throw new OutboundUrlRejectedException(
          "Image URLs must use the standard web port, not port " + port + ".");
    }
    if (blockedHosts.contains(host.toLowerCase(Locale.ROOT))) {
      throw new OutboundUrlRejectedException("Images cannot be fetched from " + host + ".");
    }

    InetAddress[] addresses;
    try {
      addresses = hostResolver.resolve(host);
    } catch (UnknownHostException e) {
      throw new OutboundUrlRejectedException("The host " + host + " could not be resolved.");
    }
    if (addresses == null || addresses.length == 0) {
      throw new OutboundUrlRejectedException("The host " + host + " could not be resolved.");
    }
    // Every address, not just the first: a host that answers with one public and one
    // private address must be refused outright.
    for (InetAddress address : addresses) {
      if (isBlocked(address)) {
        log.debug("Refusing outbound fetch: {} resolves to blocked {}", host, address);
        throw new OutboundUrlRejectedException(
            "The host "
                + host
                + " resolves to an address on a private or reserved network, which this"
                + " server will not fetch from.");
      }
    }
    log.debug("Outbound URL policy OK for host {} ({} addresses)", host, addresses.length);
  }

  private boolean isBlocked(InetAddress address) {
    if (address instanceof Inet6Address ipv6) {
      return isBlockedIpv6(ipv6);
    }
    return isBlockedIpv4(address.getAddress());
  }

  private boolean isBlockedIpv4(byte[] bytes) {
    int first = bytes[0] & 0xFF;
    int second = bytes[1] & 0xFF;
    int third = bytes[2] & 0xFF;
    // 0.0.0.0/8 "this network", 127/8 loopback, 10/8 private, 100.64/10 CGNAT,
    // 169.254/16 link-local (the cloud metadata endpoint lives at 169.254.169.254),
    // 172.16/12 and 192.168/16 private, 198.18/15 benchmarking, 224/4 multicast,
    // 240/4 reserved (which covers 255.255.255.255).
    if (first == 0 || first == 127 || first == 10 || first >= 224) {
      return true;
    }
    if (first == 100 && second >= 64 && second <= 127) {
      return true;
    }
    if (first == 169 && second == 254) {
      return true;
    }
    if (first == 172 && second >= 16 && second <= 31) {
      return true;
    }
    if (first == 192 && second == 168) {
      return true;
    }
    if (first == 198 && (second == 18 || second == 19)) {
      return true;
    }
    // Protocol assignments (192.0.0/24) and the three documentation ranges, which
    // should never be a real image host and are common in crafted payloads.
    if (first == 192 && second == 0 && (third == 0 || third == 2)) {
      return true;
    }
    if (first == 198 && second == 51 && third == 100) {
      return true;
    }
    return first == 203 && second == 0 && third == 113;
  }

  private boolean isBlockedIpv6(Inet6Address address) {
    byte[] bytes = address.getAddress();
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }
    // ::ffff:0:0/96 IPv4-mapped and 64:ff9b::/96 NAT64 both carry an IPv4 address in
    // their low 32 bits, so ::ffff:169.254.169.254 and 64:ff9b::a9fe:a9fe reach the
    // metadata endpoint unless the IPv4 rules are re-run against the unwrapped tail.
    if (isIpv4Mapped(bytes) || isNat64(bytes)) {
      return isBlockedIpv4(Arrays.copyOfRange(bytes, 12, 16));
    }
    int first = bytes[0] & 0xFF;
    int second = bytes[1] & 0xFF;
    // fc00::/7 unique-local. No JDK predicate covers it.
    if ((first & 0xFE) == 0xFC) {
      return true;
    }
    // 2002::/16 6to4 and 2001::/32 Teredo also embed IPv4, but the embedding is not
    // in the low 32 bits — deny the prefixes outright rather than unwrap them.
    if (first == 0x20 && second == 0x02) {
      return true;
    }
    if (first == 0x20 && second == 0x01 && bytes[2] == 0 && bytes[3] == 0) {
      return true;
    }
    // 2001:db8::/32 documentation and 100::/64 discard-only.
    if (first == 0x20 && second == 0x01 && (bytes[2] & 0xFF) == 0x0D && (bytes[3] & 0xFF) == 0xB8) {
      return true;
    }
    return first == 0x01 && second == 0x00 && allZero(bytes, 2, 8);
  }

  private boolean isIpv4Mapped(byte[] bytes) {
    return allZero(bytes, 0, 10) && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
  }

  private boolean isNat64(byte[] bytes) {
    List<Integer> prefix = List.of(0x00, 0x64, 0xFF, 0x9B);
    for (int i = 0; i < prefix.size(); i++) {
      if ((bytes[i] & 0xFF) != prefix.get(i)) {
        return false;
      }
    }
    return allZero(bytes, 4, 12);
  }

  private boolean allZero(byte[] bytes, int from, int to) {
    for (int i = from; i < to; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return true;
  }
}
