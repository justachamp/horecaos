package uz.horecaos.platform.conversations.domain;

import java.time.Duration;

/**
 * Arms {@code resume_due_at} and waits for the resume sweeper (ADR 0059: "a
 * delay block genuinely needs a resume") — evaluated at resume time rather
 * than redemption time, unlike every other block, because nothing redeems it.
 *
 * @param duration how long to wait from the moment this block executes
 * @param next the state to advance to once the delay elapses
 */
public record DelayBlock(Duration duration, String next) implements FlowBlock {

    public static final String TYPE = "delay";

    @Override
    public String type() {
        return TYPE;
    }
}
