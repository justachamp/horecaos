package uz.qoida.platform.migration.api;

/**
 * An import reached something outside the platform (ADR 0024).
 *
 * <p>Always a defect, never a condition to recover from. ADR 0024 imports
 * historical facts as immutable snapshots; an import that finds itself booking a
 * courier or presenting a payment attempt has gone down the live request path,
 * and every row it has written since is suspect for the same reason.
 *
 * <p>Unchecked, and deliberately not caught anywhere. The run fails, the page is
 * not checkpointed, and the extraction cursor stays where it was — which is the
 * whole point of checkpointing only after a target commit. Restarting after the
 * import port is fixed re-reads the same page.
 */
public class ExternalEffectDuringImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ExternalEffect effect;

    public ExternalEffectDuringImportException(ExternalEffect effect, String operation) {
        super(("A historical import called %s, which produces %s. ADR 0024 forbids it: import "
                + "historical facts as snapshots rather than by replaying the live path.")
                .formatted(operation, effect));
        this.effect = effect;
    }

    public ExternalEffect effect() {
        return effect;
    }
}
