package com.thaishopfun.oms.catalog;

/** One bad cell. {@code row} is the 1-based file line where the record starts (header = 1). */
public record ImportRowError(int row, String column, String error) {}
