package com.thaishopfun.oms.catalog;

/** Create or full update. {@code status} defaults to ACTIVE on create and is kept on update. */
public record ProductRequest(String name, String status) {}
