package me.pectics.chorus.core.replay;

/** Durable replay outcome. Only ACCEPTED permits login. */
public enum ReplayDecision {
    ACCEPTED,
    REPLAYED,
    UNAVAILABLE
}
