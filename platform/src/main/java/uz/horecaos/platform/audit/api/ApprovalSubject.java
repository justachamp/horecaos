package uz.horecaos.platform.audit.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a checker is shown about the request they are being asked to sign
 * (ADR 0027, ADR 0095).
 *
 * <p><strong>Why this exists.</strong> A {@code PLATFORM}-scope request carries
 * no tenant by construction — that is the point of the scope: HorecaOS
 * proposing a refund of a tenant's money is not a decision the tenant reads or
 * signs. But every other field such a row carried was constant for the action
 * or one-way. {@code scope_id} is null, {@code threshold_description} is the
 * sentence the policy froze onto every request of that action, the maker's
 * reason is withheld under ADR 0029, and {@code parameters_hash} is a one-way
 * digest. So a refund of fifty million from one tenant and a refund of five
 * million from another reached the approver's queue as two rows differing only
 * in a timestamp. The hash bound what was proposed; nothing rendered what was
 * bound, and the second signature was given blind.
 *
 * <p><strong>Why it is built by {@link ApprovalParameters} and not by the call
 * site.</strong> What the console displays has to be derived from the same
 * components the hash covers, or a maker could get one thing signed and execute
 * another. So this is emitted from one pass over the command record alongside
 * the digest: a component added later either enters both or fails loudly, which
 * is the safe-by-default direction {@code ApprovalParameters} already argues
 * for at length.
 *
 * <p><strong>ADR 0029.</strong> {@code detail} holds canonical scalars only —
 * identifiers, enum names, integer minor units, currency codes — never the
 * maker's prose. A component that is prose is named in
 * {@link ApprovalParameters.Builder#withholding(String...)}, so it stays inside
 * the hash and out of every console.
 *
 * @param tenantId whose account the decision concerns, or null when it concerns
 *                 no one tenant. Deliberately not the request's own
 *                 {@code tenant_id}: that column is the routing key for the
 *                 tenant worklist, the decision route, the pending-request
 *                 dedup and the consume predicate, and a platform decision has
 *                 to stay out of all four
 * @param detail   the proposal in display order, as the hash covers it
 */
public record ApprovalSubject(@Nullable UUID tenantId, Map<String, String> detail) {

    public ApprovalSubject {
        Objects.requireNonNull(detail, "A subject's detail is required, empty if there is none");
        detail = Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    /** Whether there is anything here worth storing beside the request. */
    public boolean isEmpty() {
        return tenantId == null && detail.isEmpty();
    }
}
