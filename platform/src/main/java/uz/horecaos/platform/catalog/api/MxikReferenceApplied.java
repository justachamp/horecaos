package uz.horecaos.platform.catalog.api;

import java.util.UUID;

/**
 * The national ИКПУ/MXIK list was replaced by an applied import run (ADR 0173).
 *
 * <p>An in-process event, published inside the apply's transaction and heard synchronously, so what it settles
 * (the "not loaded" and "stale" incidents) commits or rolls back with the load. It carries no file content, no
 * code and no label, and is not an outbox event: nothing outside the process consumes it (ADR 0032).
 *
 * @param runId          the run that was applied
 * @param datasetVersion the dataset version the run wrote
 * @param actorSubject   the platform administrator who applied it
 */
public record MxikReferenceApplied(UUID runId, int datasetVersion, String actorSubject) {}
