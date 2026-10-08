package uz.horecaos.platform.support.printing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A clock a test moves (CLAUDE.md: a lifetime, lease, expiry or sweep asserted without advancing the clock is
 * asserted against an instant, not a duration). Starts at the real now in whole microseconds -- the precision
 * PostgreSQL keeps -- so an instant written and read back is the same instant.
 */
public final class MutableClock extends Clock {

    private volatile Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void advance(Duration by) {
        now = now.plus(by).truncatedTo(ChronoUnit.MICROS);
    }

    public void set(Instant instant) {
        now = instant.truncatedTo(ChronoUnit.MICROS);
    }

    /** Back to the real now, for the start of each test. */
    public void reset() {
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
