package com.thaishopfun.oms.listing;

import java.util.List;
import java.util.Map;

public class ListingApiException extends RuntimeException {

  private final int status;
  private final String code;
  private final List<?> details;

  public ListingApiException(int status, String code, String message) {
    this(status, code, message, List.of());
  }

  public ListingApiException(int status, String code, String message, List<?> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details == null ? List.of() : List.copyOf(details);
  }

  public static ListingApiException notFound() {
    return new ListingApiException(404, "NOT_FOUND", "Listing not found");
  }

  public static ListingApiException disconnected() {
    return new ListingApiException(422, "DISCONNECTED", "Channel account is disconnected");
  }

  public static ListingApiException capabilityUnsupported(String message) {
    return new ListingApiException(422, "CAPABILITY_UNSUPPORTED", message);
  }

  public static ListingApiException forbidden() {
    return new ListingApiException(403, "FORBIDDEN", "OWNER or ADMIN role is required");
  }

  public static ListingApiException missingChannelAccountId() {
    return fieldError("channel_account_id", "channel_account_id is required");
  }

  public static ListingApiException invalidChannelAccountId() {
    return fieldError("channel_account_id", "channel_account_id is invalid");
  }

  private static ListingApiException fieldError(String field, String message) {
    return new ListingApiException(
        422, "VALIDATION_FAILED", message, List.of(Map.of("field", field, "message", message)));
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public List<?> details() {
    return details;
  }
}
