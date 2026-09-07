package com.loomascale.mcp.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;

// Encrypts ad-platform OAuth tokens at rest.
//
// AES-256-GCM with a fresh random 12-byte IV per value, stored as base64(iv || ciphertext
// || tag). GCM rather than CBC because it authenticates: a tampered row fails to decrypt
// rather than yielding attacker-influenced plaintext. The IV must never repeat under one
// key, hence one per value from a SecureRandom rather than anything derived or counted.
//
// The key is base64-encoded 32 bytes. Losing it means every stored token is unreadable and
// every user must reconnect; leaking it means every stored token is readable, so it belongs
// in a secret manager and never in a config file.
@Slf4j
public final class TokenCipher {

  private static final int IV_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int KEY_LENGTH_BYTES = 32;

  private final SecretKeySpec key;
  private final SecureRandom secureRandom = new SecureRandom();

  public TokenCipher(String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      // Not fatal at construction: a deployment may supply its own connection store and
      // never need this. It IS fatal on first use — see requireKey.
      log.warn(
          "mcp.token-encryption-key is not set. The built-in connection store cannot"
              + " store or read credentials until it is.");
      this.key = null;
      return;
    }
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "mcp.token-encryption-key is not valid base64. Generate one with:"
              + " openssl rand -base64 32",
          e);
    }
    if (keyBytes.length != KEY_LENGTH_BYTES) {
      throw new IllegalStateException(
          "mcp.token-encryption-key must be base64-encoded "
              + KEY_LENGTH_BYTES
              + " bytes, but decoded to "
              + keyBytes.length
              + ". Generate one with: openssl rand -base64 32");
    }
    this.key = new SecretKeySpec(keyBytes, "AES");
  }

  public boolean isConfigured() {
    return key != null;
  }

  public String encrypt(String plaintext) {
    if (plaintext == null) {
      return null;
    }
    requireKey();
    try {
      byte[] iv = new byte[IV_LENGTH];
      secureRandom.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (Exception e) {
      // Never include the plaintext or the key in the message: this exception reaches logs.
      throw new IllegalStateException("Token encryption failed", e);
    }
  }

  public String decrypt(String encoded) {
    if (encoded == null) {
      return null;
    }
    requireKey();
    try {
      byte[] combined = Base64.getDecoder().decode(encoded);
      if (combined.length <= IV_LENGTH) {
        throw new IllegalArgumentException("ciphertext too short to contain an IV");
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH));
      byte[] plaintext = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Token decryption failed", e);
    }
  }

  private void requireKey() {
    if (key == null) {
      throw new IllegalStateException(
          "mcp.token-encryption-key is not configured, so credentials cannot be encrypted"
              + " or read. Generate one with: openssl rand -base64 32");
    }
  }
}
