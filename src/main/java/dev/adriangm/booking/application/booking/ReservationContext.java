package dev.adriangm.booking.application.booking;

import dev.adriangm.booking.domain.TimeSlot;
import java.util.Objects;

/** Carries the validated identity, slot, attendees and permission for one request. */
public record ReservationContext(
    String userId,
    TimeSlot slot,
    int attendeeCount,
    String requestKey,
    boolean reservationAllowed) {
  /** Rejects incomplete identities, request keys or unsupported attendee counts. */
  public ReservationContext {
    if (userId == null
        || userId.isBlank()
        || requestKey == null
        || requestKey.isBlank()
        || attendeeCount < 1
        || attendeeCount > 100) {
      throw new IllegalArgumentException("Invalid reservation context.");
    }
    Objects.requireNonNull(slot, "Slot is required.");
  }
}
