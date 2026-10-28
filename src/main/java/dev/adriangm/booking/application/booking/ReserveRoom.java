package dev.adriangm.booking.application.booking;

import dev.adriangm.booking.domain.BookingFailure;
import dev.adriangm.booking.domain.Reservation;
import dev.adriangm.booking.domain.Room;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Applies reservation rules while the repository protects the current storage state. */
public final class ReserveRoom {
    private final BookingRepository repository;
    private final Clock clock;

    /** Uses the supplied storage and clock to validate reservation requests. */
    public ReserveRoom(BookingRepository repository, Clock clock)
    {
        this.repository = repository;
        this.clock = clock;
    }

    /** Recovers a confirmed request after verifying the identity and original reservation data. */
    public Optional<Reservation> previous(ReservationContext context)
    {
        if (!context.reservationAllowed()) {
            return Optional.empty();
        }

        Optional<Reservation> previous = repository.findByRequestKey(context.requestKey());
        if (previous.isPresent()) {
            previous.get().requireSameRequest(context.userId(), context.slot(), context.attendeeCount());
        }

        return previous;
    }

    /** Validates and confirms a reservation within a single protected storage transaction. */
    public Reservation execute(ReservationContext context, String roomId)
    {
        if (!context.reservationAllowed()) {
            throw new BookingFailure(BookingFailure.Reason.FORBIDDEN);
        }

        try (BookingRepository.ReservationTransaction transaction = repository.beginReservation(context, roomId)) {
            Optional<Reservation> previous = transaction.previous();
            if (previous.isPresent()) {
                Reservation reservation = reusePreviousReservation(context, roomId, previous.get());
                transaction.commit();
                return reservation;
            }

            validateNewReservation(context, transaction.room(), transaction.overlapping());
            Reservation reservation = createReservation(context, roomId);

            transaction.save(reservation);
            transaction.commit();
            return reservation;
        }
    }

    private Reservation reusePreviousReservation(ReservationContext context, String roomId, Reservation reservation)
    {
        reservation.requireSameRequest(context.userId(), context.slot(), context.attendeeCount());
        if (!reservation.roomId().equals(roomId)) {
            throw new BookingFailure(BookingFailure.Reason.REQUEST_KEY_CONFLICT);
        }

        return reservation;
    }

    private void validateNewReservation(
        ReservationContext context,
        Optional<Room> possibleRoom,
        List<Reservation> overlapping
    )
    {
        if (!context.slot().startsAt().isAfter(clock.instant())) {
            throw new BookingFailure(BookingFailure.Reason.SLOT_IN_PAST);
        }

        if (possibleRoom.isEmpty()) {
            throw new BookingFailure(BookingFailure.Reason.ROOM_NOT_FOUND);
        }

        Room room = possibleRoom.get();
        room.requireCapacity(context.attendeeCount());

        if (!overlapping.isEmpty()) {
            throw new BookingFailure(BookingFailure.Reason.SLOT_UNAVAILABLE);
        }
    }

    private Reservation createReservation(ReservationContext context, String roomId)
    {
        return new Reservation(
            UUID.randomUUID().toString(),
            context.requestKey(),
            context.userId(),
            roomId,
            context.slot(),
            context.attendeeCount()
        );
    }
}
