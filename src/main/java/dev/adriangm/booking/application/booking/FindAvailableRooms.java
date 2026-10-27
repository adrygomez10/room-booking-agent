package dev.adriangm.booking.application.booking;

import dev.adriangm.booking.domain.Reservation;
import dev.adriangm.booking.domain.Room;
import java.util.ArrayList;
import java.util.List;

/** Finds rooms satisfying capacity, equipment and current availability requirements. */
public final class FindAvailableRooms {
  private final BookingRepository repository;

  /** Uses the supplied storage contract to read the room catalog and reservations. */
  public FindAvailableRooms(BookingRepository repository) {
    this.repository = repository;
  }

  /** Returns rooms matching the selected slot, attendee count and projector preference. */
  public List<Room> execute(ReservationContext context, boolean needsProjector) {
    List<Reservation> reserved = repository.reservationsIn(context.slot());
    List<Room> available = new ArrayList<>();

    for (Room room : repository.rooms()) {
      if (!room.accommodates(context.attendeeCount(), needsProjector)) {
        continue;
      }

      boolean occupied = false;
      for (Reservation reservation : reserved) {
        if (reservation.roomId().equals(room.id())) {
          occupied = true;
          break;
        }
      }

      if (!occupied) {
        available.add(room);
      }
    }

    return List.copyOf(available);
  }
}
