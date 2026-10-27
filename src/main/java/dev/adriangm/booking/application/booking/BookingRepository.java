package dev.adriangm.booking.application.booking;

import dev.adriangm.booking.domain.Reservation;
import dev.adriangm.booking.domain.Room;
import dev.adriangm.booking.domain.TimeSlot;
import java.util.List;
import java.util.Optional;

/** Stores rooms and reservations and provides a protected reservation transaction. */
public interface BookingRepository {
  /** Returns the configured room catalog. */
  List<Room> rooms();

  /** Returns reservations that overlap the supplied time slot. */
  List<Reservation> reservationsIn(TimeSlot slot);

  /** Finds the stored result associated with an idempotency key. */
  Optional<Reservation> findByRequestKey(String requestKey);

  /** Opens a transaction and locks the state required to decide and save this reservation. */
  ReservationTransaction beginReservation(ReservationContext context, String roomId);

  /** Keeps reservation state protected until commit or rollback on close. */
  interface ReservationTransaction extends AutoCloseable {
    /** Returns the selected room, if it exists. */
    Optional<Room> room();

    /** Returns the selected room's reservations overlapping the requested slot. */
    List<Reservation> overlapping();

    /** Returns the reservation previously stored under this request key, if any. */
    Optional<Reservation> previous();

    /** Writes a validated reservation without committing the transaction. */
    void save(Reservation reservation);

    /** Confirms the transaction and releases its database lock. */
    void commit();

    /** Rolls back an uncommitted transaction and closes its storage resources. */
    @Override
    void close();
  }
}
