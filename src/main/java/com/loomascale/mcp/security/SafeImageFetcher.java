package com.loomascale.mcp.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

// Fetches an image from a URL supplied by an assistant, for upload to a Meta ad
// account. Every rule here exists because the URL is attacker-controllable.
//
// Redirects are followed manually with Redirect.NEVER, because Redirect.ALWAYS
// re-resolves and reconnects without consulting anyone, which would make the
// pre-flight policy check decorative.
//
// ACCEPTED RESIDUAL RISK — DNS rebinding. OutboundUrlPolicy resolves the host, then
// HttpClient resolves it again when it connects; a hostile zero-TTL zone can answer
// differently the second time. Closing that needs a per-request DNS resolver, which
// java.net.http does not expose (the alternatives are an Apache HttpClient
// dependency or a JVM-global JEP 418 resolver that would change DNS for JDBC and
// Graph calls too). It is mitigated by pinning the JDK positive DNS cache
// (networkaddress.cache.ttl) so the connect reuses the validated answer, and it is
// acceptable here specifically because this is BLIND SSRF: the bytes go only to
// Meta, no fetched content reaches the model or the tool result, and the policy's
// port rule confines a winning attacker to an unauthenticated GET on port 80/443
// with no read-back channel.
//
// Note that ImageDownloadService and CheckUrlController make the same kind of
// outbound fetch with none of these checks; they should migrate onto this policy.
@Slf4j
public class SafeImageFetcher {

  private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  private static final String JPEG = "image/jpeg";
  private static final String PNG = "image/png";
  private static final int CHUNK_BYTES = 8192;
  private static final List<Integer> REDIRECT_STATUSES = List.of(301, 302, 303, 307, 308);

  private final OutboundUrlPolicy policy;
  private final HttpClient httpClient;
  private final long maxBytes;
  private final long minBytes;
  private final Duration readTimeout;
  private final long totalTimeoutMs;
  private final int maxRedirects;
  private final List<String> allowedContentTypes;
  private final String userAgent;

  public SafeImageFetcher(
      OutboundUrlPolicy policy,
      @Value("${mcp.image-upload.max-bytes:8388608}") long maxBytes,
      @Value("${mcp.image-upload.min-bytes:512}") long minBytes,
      @Value("${mcp.image-upload.connect-timeout-ms:3000}") long connectTimeoutMs,
      @Value("${mcp.image-upload.read-timeout-ms:10000}") long readTimeoutMs,
      @Value("${mcp.image-upload.total-timeout-ms:20000}") long totalTimeoutMs,
      @Value("${mcp.image-upload.max-redirects:3}") int maxRedirects,
      @Value("${mcp.image-upload.allowed-content-types:image/jpeg,image/png}")
          String allowedContentTypes,
      @Value("${mcp.image-upload.user-agent:ads-mcp/1.0}") String userAgent) {
    this.policy = policy;
    this.userAgent = userAgent;
    this.maxBytes = maxBytes;
    this.minBytes = minBytes;
    this.readTimeout = Duration.ofMillis(readTimeoutMs);
    this.totalTimeoutMs = totalTimeoutMs;
    this.maxRedirects = maxRedirects;
    this.allowedContentTypes =
        Stream.of(allowedContentTypes.split(","))
            .map(type -> type.trim().toLowerCase(Locale.ROOT))
            .filter(type -> !type.isBlank())
            .toList();
    this.httpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .build();
  }

  // Throws OutboundUrlRejectedException when the URL or its content is one this
  // server refuses (a permanent condition the user has to fix) and IOException when
  // the fetch merely failed (transient, so the caller may fall back).
  public FetchedCreativeImage fetch(String imageUrl) throws IOException, InterruptedException {
    URI uri = parse(imageUrl);
    policy.check(uri);
    long deadlineNanos = System.nanoTime() + Duration.ofMillis(totalTimeoutMs).toNanos();

    for (int hop = 0; hop <= maxRedirects; hop++) {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(uri)
              .header("Accept", "image/*")
              .header("User-Agent", userAgent)
              .timeout(readTimeout)
              .GET()
              .build();
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

      if (REDIRECT_STATUSES.contains(response.statusCode())) {
        uri = redirectTarget(uri, response);
        // Full re-validation of the new target: scheme, credentials, port, DNS and
        // every resolved address, exactly as for the original URL.
        policy.check(uri);
        continue;
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IOException("Image URL returned HTTP " + response.statusCode() + ".");
      }
      String declaredType = contentType(response);
      byte[] bytes = readCapped(response.body(), deadlineNanos);
      return sniff(bytes, declaredType);
    }
    throw new IOException("Image URL redirected more than " + maxRedirects + " times.");
  }

  private URI parse(String imageUrl) {
    try {
      return URI.create(imageUrl.trim());
    } catch (IllegalArgumentException e) {
      throw new OutboundUrlRejectedException("The image URL could not be parsed.");
    }
  }

  private URI redirectTarget(URI current, HttpResponse<InputStream> response) {
    String location =
        response
            .headers()
            .firstValue("location")
            .filter(value -> !value.isBlank())
            .orElseThrow(
                () -> new OutboundUrlRejectedException("Image URL redirected without a target."));
    try {
      // resolve() handles a relative Location, which is legal and common.
      return current.resolve(location.trim());
    } catch (IllegalArgumentException e) {
      throw new OutboundUrlRejectedException("Image URL redirected to an unusable target.");
    }
  }

  private String contentType(HttpResponse<InputStream> response) {
    // A missing Content-Type is a rejection rather than a guess.
    String declared =
        response
            .headers()
            .firstValue("content-type")
            .map(value -> value.split(";")[0].trim().toLowerCase(Locale.ROOT))
            .orElseThrow(
                () ->
                    new OutboundUrlRejectedException(
                        "The image URL returned no content type. Expected one of "
                            + String.join(", ", allowedContentTypes)
                            + "."));
    if (!allowedContentTypes.contains(declared)) {
      throw new OutboundUrlRejectedException(
          "The image URL returned "
              + declared
              + ". Expected one of "
              + String.join(", ", allowedContentTypes)
              + ".");
    }
    return declared;
  }

  // Content-Length is a hint at best: chunked responses omit it and a hostile server
  // lies about it, so the cap is enforced against bytes actually read. The deadline
  // is checked every chunk because HttpRequest.timeout only covers the arrival of
  // the response headers — with ofInputStream() the body can then trickle forever.
  private byte[] readCapped(InputStream body, long deadlineNanos) throws IOException {
    try (InputStream in = body) {
      ByteArrayOutputStream collected = new ByteArrayOutputStream(CHUNK_BYTES);
      byte[] buffer = new byte[CHUNK_BYTES];
      long total = 0;
      int read;
      while ((read = in.read(buffer)) != -1) {
        if (System.nanoTime() > deadlineNanos) {
          throw new IOException("Timed out while downloading the image.");
        }
        total += read;
        if (total > maxBytes) {
          throw new OutboundUrlRejectedException(
              "The image is larger than the " + (maxBytes / (1024 * 1024)) + " MB limit.");
        }
        collected.write(buffer, 0, read);
      }
      if (total < minBytes) {
        throw new OutboundUrlRejectedException(
            "The image URL returned only " + total + " bytes, too small to be a real image.");
      }
      return collected.toByteArray();
    }
  }

  // The declared type is not trusted either: an HTML error page served as
  // image/jpeg is the common case, and Meta would reject those bytes anyway. The
  // sniffed type is what gets sent to Graph. ImageIO.read() is deliberately NOT
  // used — decoding attacker-controlled input invites decompression bombs, and
  // Meta validates dimensions for us.
  private FetchedCreativeImage sniff(byte[] bytes, String declaredType) {
    Optional<String> sniffed = sniffType(bytes);
    if (sniffed.isEmpty()) {
      throw new OutboundUrlRejectedException(
          "The image URL did not return a JPEG or PNG image (it claimed " + declaredType + ").");
    }
    String type = sniffed.get();
    String fileName = JPEG.equals(type) ? "creative.jpg" : "creative.png";
    log.debug("Fetched creative image: {} bytes, sniffed {}", bytes.length, type);
    return new FetchedCreativeImage(bytes, type, fileName);
  }

  private Optional<String> sniffType(byte[] bytes) {
    if (startsWith(bytes, JPEG_MAGIC)) {
      return Optional.of(JPEG);
    }
    if (startsWith(bytes, PNG_MAGIC)) {
      return Optional.of(PNG);
    }
    return Optional.empty();
  }

  private boolean startsWith(byte[] bytes, byte[] magic) {
    if (bytes.length < magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if (bytes[i] != magic[i]) {
        return false;
      }
    }
    return true;
  }
}
