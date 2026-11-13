package io.github.aleeibrahim.zkmatch.ss21;

/** A template that cannot be decoded, with a reason that is safe to count and log (no template bytes). */
public final class Ss21Exception extends RuntimeException {
    public enum Reason { EMPTY, NOT_BASE64, TOO_SHORT, NOT_SS21, UNSUPPORTED_VARIANT, NO_MINUTIAE, TRUNCATED }

    private final Reason reason;

    public Ss21Exception(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
