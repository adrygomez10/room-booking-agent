package dev.adriangm.booking.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Defines a half-open booking interval lasting between fifteen minutes and eight hours. */
public record TimeSlot(Instant startsAt, Instant endsAt) {
  /** Requires ordered instants with at most millisecond precision and a supported duration. */
  public TimeSlot {
    Objects.requireNonNull(startsAt, "Start is required.");
    Objects.requireNonNull(endsAt, "End is required.");

    Duration duration = Duration.between(startsAt, endsAt);
    if (duration.compareTo(Duration.ofMinutes(15)) < 0
        || duration.compareTo(Duration.ofHours(8)) > 0) {
      throw new IllegalArgumentException("A slot must last between 15 minutes and 8 hours.");
    }

    if (!startsAt.equals(Instant.ofEpochMilli(startsAt.toEpochMilli()))
        || !endsAt.equals(Instant.ofEpochMilli(endsAt.toEpochMilli()))) {
      throw new IllegalArgumentException("Times must have at most millisecond precision.");
    }
  }

  /** Detects intersecting intervals while allowing adjacent bookings. */
  public boolean overlaps(TimeSlot other) {
    return startsAt.isBefore(other.endsAt) && endsAt.isAfter(other.startsAt);
  }
}
