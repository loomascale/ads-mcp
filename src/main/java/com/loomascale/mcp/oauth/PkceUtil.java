package com.loomascale.mcp.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

// RFC 7636 PKCE. Only S256 is supported; the "plain" method is rejected at the
// authorize endpoint before a code is ever issued.
public final class PkceUtil {

  public static final String METHOD_S256 = "S256";

  private PkceUtil() {}

  // true when base64url-nopad(SHA-256(verifier)) equals the stored challenge.
  // Constant-time comparison to avoid leaking the challenge via timing.
  public static boolean verifyS256(String codeVerifier, String codeChallenge) {
    if (codeVerifier == null || codeChallenge == null) {
      return false;
    }
    String computed = sha256Base64Url(codeVerifier);
    return MessageDigest.isEqual(
        computed.getBytes(StandardCharsets.US_ASCII),
        codeChallenge.getBytes(StandardCharsets.US_ASCII));
  }

  public static String sha256Base64Url(String value) {
    byte[] digest = sha256(value.getBytes(StandardCharsets.US_ASCII));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }

  // Hex SHA-256, used to store codes/refresh tokens as hashes (never plaintext).
  public static String sha256Hex(String value) {
    byte[] digest = sha256(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
