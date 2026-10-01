package com.thaishopfun.oms.stockdoc;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Create ({@code type} required) or update ({@code type} ignored) a DRAFT header. */
public record StockDocumentRequest(
    String type, @JsonProperty("reference_no") String referenceNo, String note) {}
