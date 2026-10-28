package dev.adriangm.booking.domain;

/** Signals a rejected reservation rule with a specific domain reason. */
public final class BookingFailure extends RuntimeException {
    /** Distinguishes permission, identity, room capacity, availability and time violations. */
    public enum Reason {
        FORBIDDEN,
        ROOM_NOT_FOUND,
        INSUFFICIENT_CAPACITY,
        SLOT_UNAVAILABLE,
        REQUEST_KEY_CONFLICT,
        SLOT_IN_PAST
    }

    private final Reason reason;

    /** Creates a failure for the violated reservation rule. */
    public BookingFailure(Reason reason)
    {
        super(reason.name());
        this.reason = reason;
    }

    /** Returns the reservation rule that rejected the operation. */
    public Reason reason()
    {
        return reason;
    }
}
