package uz.horecaos.platform.fiscal.api;

import java.util.UUID;

/**
 * The state system's check of a marking code, as a seam and not a call (ADR 0161, Decision 9).
 *
 * <p>The repository holds no retailer API to build against, and whether the state system offers a retailer
 * an online check of a code, or a separate consumption call, and whether HorecaOS may make either on a
 * tenant's behalf, are open inputs that name counsel and the platform owner. The sale reaches the state
 * system through the receipt that carries the code; until a record says otherwise this port answers {@link
 * Verdict#NOT_WIRED}.
 *
 * <p>No {@code ProviderCategory} is added to make it real: ADR 0026 declined a {@code FISCAL} category and
 * ADR 0038 rejected making HorecaOS a fiscal operator, and this port does not reopen either by the back
 * door. Making it real is a later record, and its first adapter is built against a controlled fake that
 * includes the accepted-then-lost reply and a malformed body (ADR 0007), under an ADR 0026 installation the
 * owner completes.
 *
 * <p>The code travels as an argument and nowhere else: an implementation must not log it, put it in an
 * error, a metric label or an event, or keep it past the call.
 */
public interface MarkingVerificationPort {

    /** What the state system said about one canonical code. */
    enum Verdict {
        /** The code is genuine and may be sold. */
        VERIFIED,
        /** The state system says the code is not valid for sale. */
        REJECTED,
        /** No answer, a lost reply or a body that settles nothing: not a verdict either way. */
        UNCERTAIN,
        /** No implementation is present; nothing was asked. */
        NOT_WIRED
    }

    Verdict verify(UUID tenantId, String canonicalCode);
}
