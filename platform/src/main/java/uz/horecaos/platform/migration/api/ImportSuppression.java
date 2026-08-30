package uz.horecaos.platform.migration.api;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What an adapter with an external effect says when it is asked to act during a
 * historical import (ADR 0024).
 *
 * <p>Two calls, matching {@link ExternalEffect.Suppression}'s two answers, and
 * both are written to read as a guard clause at the top of the method that would
 * otherwise reach outside the platform:
 *
 * <pre>{@code
 * if (ImportSuppression.suppress(ExternalEffect.OUTBOX_PUBLICATION, "Order", id)) {
 *     return;
 * }
 * }</pre>
 *
 * <p>Static, with no Spring bean and no meter registry, and that is a deliberate
 * choice rather than a shortcut. These guards go into constructors' worth of
 * adapters across six modules, several of which are constructed directly by
 * tests in other packages; adding a collaborator to each would change signatures
 * whose only fault is being near an external effect. A static call is also the
 * one form that cannot be forgotten at wiring time, and the failure mode of
 * forgetting is a customer receiving a five-year-old order confirmation.
 *
 * <p>No counter yet, for the same reason. Micrometer's global registry is a
 * static that Spring Boot only sometimes binds, and a metric that is silently
 * unbound would be worse evidence than none — it would read as "nothing was
 * suppressed". The suppression log line carries the effect and the subject and
 * is the evidence until an injected registry has a natural home.
 *
 * <p>Nothing logged here is personal (ADR 0029): an effect name, a subject type,
 * and an opaque identifier. Never a recipient, an address, or a payload.
 */
public final class ImportSuppression {

    private static final Logger log = LoggerFactory.getLogger(ImportSuppression.class);

    private ImportSuppression() {
    }

    /**
     * Whether this effect must be skipped because an import is running.
     *
     * <p>Answers false outside an import, so the ordinary request path pays one
     * {@code ScopedValue} read and behaves exactly as it did before the guard was
     * added.
     *
     * @param effect      what would have happened; must be a {@link
     *                    ExternalEffect.Suppression#SKIPPED} effect, because
     *                    quietly skipping a refusable one is how a fabricated
     *                    result gets committed
     * @param subjectType the aggregate the effect belonged to, for the log line
     * @param subjectId   that aggregate's identifier, which is never personal
     */
    public static boolean suppress(ExternalEffect effect, String subjectType, Object subjectId) {
        Objects.requireNonNull(effect, "An effect is required");
        if (effect.suppression() != ExternalEffect.Suppression.SKIPPED) {
            throw new IllegalArgumentException(
                    ("%s is refused during an import, not skipped. Skipping it would return a "
                            + "result the caller can only satisfy by inventing one.")
                            .formatted(effect));
        }
        if (!ImportContext.isImporting()) {
            return false;
        }
        log.info("Import suppressed {} for {} {}", effect, subjectType, subjectId);
        return true;
    }

    /**
     * Refuses to act when an import is running, and does nothing otherwise.
     *
     * <p>The tripwire at the outermost boundary. Reaching a provider inside an
     * import is not a case to handle gracefully — it means an import port took
     * the live path, and the correct outcome is a failed run with a name on it
     * rather than a courier arriving at a restaurant for a delivery that
     * happened in 2021.
     *
     * @param effect    what would have happened; must be a {@link
     *                  ExternalEffect.Suppression#REFUSED} effect
     * @param operation the call being refused, as a reader of the run failure
     *                  would want it named
     */
    public static void refuse(ExternalEffect effect, String operation) {
        Objects.requireNonNull(effect, "An effect is required");
        Objects.requireNonNull(operation, "An operation is required");
        if (effect.suppression() != ExternalEffect.Suppression.REFUSED) {
            throw new IllegalArgumentException(
                    ("%s is skipped during an import, not refused. Failing the run for an effect "
                            + "that has a truthful no-op stops a legitimate import.")
                            .formatted(effect));
        }
        if (ImportContext.isImporting()) {
            throw new ExternalEffectDuringImportException(effect, operation);
        }
    }
}
