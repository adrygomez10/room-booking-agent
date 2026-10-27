package dev.adriangm.booking.domain;

/** Encapsulates room identity, equipment and capacity requirements. */
public record Room(String id, String name, int capacity, boolean hasProjector) {
  /** Requires an identified room with a name and positive capacity. */
  public Room {
    if (id == null || id.isBlank() || name == null || name.isBlank() || capacity < 1) {
      throw new IllegalArgumentException("A room needs an ID, a name and a positive capacity.");
    }
  }

  /** Checks capacity and the optional projector requirement for a positive attendee count. */
  public boolean accommodates(int attendees, boolean needsProjector) {
    if (attendees < 1) {
      throw new IllegalArgumentException("Attendees must be positive.");
    }

    return capacity >= attendees && (!needsProjector || hasProjector);
  }

  /** Rejects reservations whose attendee count exceeds the room capacity. */
  public void requireCapacity(int attendees) {
    if (!accommodates(attendees, false)) {
      throw new BookingFailure(BookingFailure.Reason.INSUFFICIENT_CAPACITY);
    }
  }
}
