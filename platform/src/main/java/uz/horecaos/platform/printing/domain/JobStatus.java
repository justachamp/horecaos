package uz.horecaos.platform.printing.domain;

/**
 * Where a print job is (ADR 0154, Decision 7).
 *
 * <pre>
 * QUEUED --claim--> CLAIMED --result PRINTED--> PRINTED
 *    ^                 |  \--result FAILED, attempt &lt; 6--> QUEUED (next attempt later)
 *    |                 |  \--result FAILED, attempt = 6--> DEAD
 *    +--lease lapses---+      QUEUED past expires_at --> EXPIRED      QUEUED --cancel--> CANCELLED
 * </pre>
 */
public enum JobStatus {
    QUEUED(false),
    CLAIMED(false),
    PRINTED(true),
    /** The browser path: all the platform can know is that the preview was handed to a person. */
    HANDED_OFF(true),
    DEAD(true),
    CANCELLED(true),
    EXPIRED(true);

    private final boolean terminal;

    JobStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }
}
