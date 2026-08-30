package uz.horecaos.platform.payments.domain;

import java.util.Objects;

/**
 * A checkout surface could not be produced (ADR 0013).
 *
 * <p>Two subclasses because the two answers have opposite consequences, and
 * collapsing them is the single most expensive mistake available on this path.
 *
 * <p>{@link Refused} means the provider — or the platform's own configuration —
 * said no, and said it before anything could have happened. The attempt is
 * untouched, it may be presented again, and nothing about money is in doubt.
 *
 * <p>{@link Uncertain} means a mutating provider call was made and its answer was
 * lost. Neither provider offers an idempotency key on a call that mutates, so the
 * call must never be repeated; the attempt goes to {@code UNCERTAIN} with a named
 * resolver, and the resolver is the only thing that may follow.
 *
 * <p>Carries a HorecaOS failure code and never a provider one. Click answers with
 * small negative integers and note strings that must be echoed verbatim to Click;
 * Payme answers with a localised JSON-RPC error. Neither vocabulary belongs
 * outside its adapter.
 */
public abstract sealed class PresentationFailure extends RuntimeException {

    private final String failureCode;

    private PresentationFailure(String failureCode, String detail) {
        super(detail);
        this.failureCode = Objects.requireNonNull(failureCode, "A failure code is required");
    }

    public String failureCode() {
        return failureCode;
    }

    /** The provider or the configuration said no. Nothing happened; try again. */
    public static final class Refused extends PresentationFailure {

        public Refused(String failureCode, String detail) {
            super(failureCode, detail);
        }
    }

    /**
     * A mutating call was made and its outcome is unknown. Never repeat it.
     *
     * <p>Reachable only from a presentation that mutates — Click's
     * {@code invoice/create} today. A link is a string built in this process and
     * cannot be uncertain about anything.
     */
    public static final class Uncertain extends PresentationFailure {

        public Uncertain(String failureCode, String detail) {
            super(failureCode, detail);
        }
    }
}
