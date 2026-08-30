package uz.qoida.platform.migration.domain;

/**
 * How a migration run ended, or that it has not (ADR 0024).
 *
 * <p>{@link #FAILED} and {@link #CANCELLED} are separate because runs are
 * restartable and safe to repeat: what a failed run leaves behind is a checkpoint
 * to resume from, and what a cancelled one leaves behind is a decision someone
 * made. Collapsing them would make an operator stopping a run look like the
 * migration breaking.
 */
public enum RunStatus {

    RUNNING(false),

    COMPLETED(true),

    FAILED(true),

    CANCELLED(true);

    private final boolean terminal;

    RunStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** Whether the run has stopped and its watermark will not advance further. */
    public boolean terminal() {
        return terminal;
    }
}
