package dev.adriangm.booking.application;

/** Carries a stable application error code without exposing provider or storage details. */
public final class TurnFailure extends RuntimeException {
    /** Creates a turn failure identified by its public application error code. */
    public TurnFailure(String code)
    {
        super(code);
    }
}
