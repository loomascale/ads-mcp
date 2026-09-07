package com.loomascale.mcp.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

// The DNS seam. Exists so OutboundUrlPolicy can be tested against every reserved
// address range without touching the network or relying on a hosts file.
public interface HostResolver {

  InetAddress[] resolve(String host) throws UnknownHostException;
}
