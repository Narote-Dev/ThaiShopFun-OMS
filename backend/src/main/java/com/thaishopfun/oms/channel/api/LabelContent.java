package com.thaishopfun.oms.channel.api;

/** Raw label bytes (PDF for TSF section 4.7). */
public record LabelContent(byte[] bytes) {

  public LabelContent {
    if (bytes == null) {
      throw new IllegalArgumentException("bytes is required");
    }
  }
}
