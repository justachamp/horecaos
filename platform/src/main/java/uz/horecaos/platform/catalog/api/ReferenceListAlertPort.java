package uz.horecaos.platform.catalog.api;

import java.time.Instant;
import java.util.Map;

/**
 * Where the catalog tells the platform's staff that the national IKPU/MXIK list needs attention, and
 * that it no longer does (ADR 0173).
 *
 * <p>Declared here by the consumer and implemented by {@code notifications}, which already depends on
 * {@code catalog.api}: the catalog raising an alert straight through {@code notifications.api} would
 * close a cycle (catalog, notifications, catalog) that {@code ModularArchitectureTests} refuses. The
 * incident itself, its audience and its de-duplication are {@code notifications}' business; this port
 * carries only what happened.
 */
public interface ReferenceListAlertPort {

    /** An open condition: the list is not loaded, or it is older than the platform's ceiling. */
    void raise(String eventClass, String subjectType, String subjectId, Map<String, String> variables, Instant at);

    /** The condition ended; resolves the open incident of this class and subject, if there is one. */
    void resolve(String eventClass, String subjectType, String subjectId, String by, String note);
}
