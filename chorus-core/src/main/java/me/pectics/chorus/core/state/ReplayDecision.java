package me.pectics.chorus.core.state;

/** Only ACCEPTED permits login; all other outcomes reject it. */
public enum ReplayDecision {
    ACCEPTED,
    REPLAYED,
    UNAVAILABLE,
    CAPACITY_EXCEEDED
}
