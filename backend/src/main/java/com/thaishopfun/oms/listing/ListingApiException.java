package com.thaishopfun.oms.listing;

public class ListingApiException extends RuntimeException {

  private final int status;
  private final String code;

  public ListingApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
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

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
