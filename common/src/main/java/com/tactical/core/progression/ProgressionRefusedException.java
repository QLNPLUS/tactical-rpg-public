package com.tactical.core.progression;

/** A refusal this domain itself decided; the reason code is what the caller displays. */
public final class ProgressionRefusedException extends IllegalStateException {
    private final ProgressionReason reason;

    public ProgressionRefusedException(ProgressionReason reason) {
        this(reason, reason == null ? "unknown refusal" : reason.name());
    }

    public ProgressionRefusedException(ProgressionReason reason, String detail) {
        super(detail);
        if (reason == null || reason.accepted())
            throw new IllegalArgumentException("A refusal needs a refusing reason");
        this.reason = reason;
    }

    public ProgressionReason reason() { return reason; }
}
