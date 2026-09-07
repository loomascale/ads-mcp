package com.loomascale.googleads.client;

import java.util.List;
import java.util.Optional;

// Runtime exception for Google OAuth / Google Ads API failures, carrying enough
// metadata for retryability and auth-failure decisions (mirrors MetaApiException).
public class GoogleAdsApiException extends RuntimeException {

  // The one Google Ads error code this layer reasons about by name: the OAuth user's
  // Google account owns no Ads account at all. Qualified with its oneof field, see
  // GoogleAdsApiClient.adsErrorCodes.
  public static final String NOT_ADS_USER = "authenticationError.NOT_ADS_USER";

  private final int httpStatus;
  // Google's machine-readable status, e.g. PERMISSION_DENIED, or the OAuth error
  // code such as invalid_grant. Null when the response carried none.
  private final String errorStatus;
  // Google Ads' own error codes from the GoogleAdsFailure detail, qualified as
  // "<oneofField>.<VALUE>". Empty for OAuth failures and locally-raised errors.
  private final List<String> adsErrorCodes;
  // Google's per-request id from the response header. It is what Google support asks for,
  // and what ties a tool error back to one line in our own logs. Absent for locally-raised
  // errors and for responses that carried no header.
  private final String requestId;

  public GoogleAdsApiException(String message, int httpStatus, String errorStatus) {
    this(message, httpStatus, errorStatus, List.of());
  }

  public GoogleAdsApiException(
      String message, int httpStatus, String errorStatus, List<String> adsErrorCodes) {
    this(message, httpStatus, errorStatus, adsErrorCodes, null);
  }

  public GoogleAdsApiException(
      String message,
      int httpStatus,
      String errorStatus,
      List<String> adsErrorCodes,
      String requestId) {
    super(message);
    this.httpStatus = httpStatus;
    this.errorStatus = errorStatus;
    this.adsErrorCodes = adsErrorCodes == null ? List.of() : List.copyOf(adsErrorCodes);
    this.requestId = requestId;
  }

  public int httpStatus() {
    return httpStatus;
  }

  public String errorStatus() {
    return errorStatus;
  }

  public List<String> adsErrorCodes() {
    return adsErrorCodes;
  }

  public Optional<String> requestId() {
    return Optional.ofNullable(requestId);
  }

  // Throttling, server errors, and network failures (status 0) are retryable.
  public boolean isRetryable() {
    return httpStatus == 0 || httpStatus == 429 || httpStatus >= 500;
  }

  // The access token is dead (expired/revoked) or the refresh token no longer
  // works — callers refresh once or mark the connection EXPIRED.
  public boolean isAuthError() {
    // NOT_ADS_USER also arrives as 401, but the token is perfectly valid — the Google
    // account simply owns no Ads account. Expiring the connection over it sends the
    // user to reconnect, which fails identically, forever.
    return !isNotAdsUser() && (httpStatus == 401 || "invalid_grant".equals(errorStatus));
  }

  public boolean isPermissionDenied() {
    return httpStatus == 403;
  }

  // The Google account that granted the token is not associated with any Google Ads
  // account. Keyed on the error code alone, never on the HTTP status: Google has
  // answered this both UNAUTHENTICATED and PERMISSION_DENIED.
  public boolean isNotAdsUser() {
    return adsErrorCodes.contains(NOT_ADS_USER);
  }
}
