package dev.adriangm.booking.domain;

import java.util.Objects;

/** Represents an immutable reservation and the request key identifying its original effect. */
public record Reservation(
    String id, String requestKey, String userId, String roomId, TimeSlot slot, int attendeeCount) {
  /** Requires complete identifiers, a valid slot and a positive attendee count. */
  public Reservation {
    if (id == null
        || id.isBlank()
        || requestKey == null
        || requestKey.isBlank()
        || userId == null
        || userId.isBlank()
        || roomId == null
        || roomId.isBlank()
        || attendeeCount < 1) {
      throw new IllegalArgumentException("A reservation needs valid identifiers and attendees.");
    }
    Objects.requireNonNull(slot, "Slot is required.");
  }

  /** Rejects reuse of the request key for a different identity, slot or attendee count. */
  public void requireSameRequest(String actor, TimeSlot requestedSlot, int attendees) {
    if (!userId.equals(actor) || !slot.equals(requestedSlot) || attendeeCount != attendees) {
      throw new BookingFailure(BookingFailure.Reason.REQUEST_KEY_CONFLICT);
    }
  }
}
