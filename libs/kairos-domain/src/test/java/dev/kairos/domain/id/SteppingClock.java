package dev.kairos.domain.id;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock whose time the test sets explicitly. */
final class SteppingClock extends Clock {

    private long millis;

    SteppingClock(long millis) {
        this.millis = millis;
    }

    void set(long newMillis) {
        this.millis = newMillis;
    }

    void advance(long deltaMillis) {
        this.millis += deltaMillis;
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
