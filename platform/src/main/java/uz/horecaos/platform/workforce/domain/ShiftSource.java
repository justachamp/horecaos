package uz.horecaos.platform.workforce.domain;

/**
 * Where a shift's open or close came from (ADR 0156 Decision 6). Two console sources in v1, both at
 * a branch, neither with a position. The terminal source is reserved by name and not built: the
 * record that gives a shared device a human identity adds {@code TERMINAL} in its own migration,
 * because a column value no writer produces is the configuration that silently does nothing.
 */
public enum ShiftSource {
    /** The person clocked themselves in or out. The time is the server's, never the request's. */
    CONSOLE_SELF,
    /** A manager recorded it. The request names the time. */
    CONSOLE_MANAGER
}
