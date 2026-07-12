package com.inventoryart.common;

import java.time.Instant;

/** Non-null PostgreSQL-safe bounds for optional timestamp query parameters. */
public final class QueryTimeBounds {
  private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
  private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

  private QueryTimeBounds() {}

  public static Instant from(Instant value) {
    return value == null ? EARLIEST : value;
  }

  public static Instant to(Instant value) {
    return value == null ? LATEST : value;
  }
}
