package io.github.aleeibrahim.zkmatch.ss21;

/** A template that cannot be decoded, with a reason that is safe to count and log (no template bytes). */
public final class Ss21Exception extends RuntimeException {
    public enum Reason {
        EMPTY, NOT_BASE64, TOO_SHORT,
        /** Not ZK 10.0 SS21 (some 10.0 templates use another layout). */
        NOT_SS21,
        /** Not ZK 12.0 SR23. */
        NOT_SR23,
        /** Not the ZK 13.0 layout. */
        NOT_ZK13,
        /** A format version this service does not decode. */
        UNKNOWN_FORMAT,
        UNSUPPORTED_VARIANT, NO_MINUTIAE, TRUNCATED
    }

    private final Reason reason;

    public Ss21Exception(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
