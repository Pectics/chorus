package me.pectics.chorus.core.replay;

/** Only ACCEPTED permits login; all other outcomes reject it. */
public enum ReplayDecision {
    ACCEPTED,
    REPLAYED,
    UNAVAILABLE,
    CAPACITY_EXCEEDED
}
