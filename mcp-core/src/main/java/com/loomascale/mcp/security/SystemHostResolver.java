package com.loomascale.mcp.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class SystemHostResolver implements HostResolver {

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    return InetAddress.getAllByName(host);
  }
}
