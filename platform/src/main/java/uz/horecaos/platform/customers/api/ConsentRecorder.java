package uz.horecaos.platform.customers.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Recording that a customer agreed to something (ADR 0015), for a caller that
 * already knows exactly what was shown and needs the platform's one
 * append-only evidence store to remember it — never a second, module-local
 * acceptance table.
 *
 * <p>Deliberately narrower than {@link uz.horecaos.platform.customers.application.ConsentService}
 * itself, which stays internal: this port only ever records what the customer
 * themselves did in their own storefront session -- {@link #recordGrant a
 * grant}, and (ADR 0159) {@link #recordWithdrawal the withdrawal of a purpose
 * they granted} -- and takes plain strings rather than {@code ConsentService}'s
 * application-layer enums so a caller outside this module never needs to
 * import them. An import-sourced decision, a backdated one, or a support agent
 * acting on somebody's behalf are different acts with different evidence, and
 * stay behind {@code ConsentService} itself, used only from inside
 * {@code customers}. A customer's own storefront withdrawal is the one
 * withdrawal this port records.
 *
 * <p>Distinct from {@link ConsentDirectory}, whose own Javadoc is emphatic
 * that reading a decision must never become forming one. This is the write
 * side that {@code ConsentDirectory} exists to keep every other module away
 * from — a legal module recording a terms-of-service acceptance is not
 * "another module deciding" in the sense that doc warns against, because the
 * decision was the customer's own and already made; this is only where it is
 * written down.
 */
public interface ConsentRecorder {

    /**
     * Records that the customer granted consent for one purpose, right now.
     *
     * @param brandId null for a tenant-wide purpose, set for a brand-specific one
     * @param channel null when the purpose is not channel-specific
     * @param policyVersion identifies exactly what the customer agreed to —
     *                       the caller's own versioning scheme, opaque to this
     *                       port. Once recorded it is never rewritten: a later
     *                       change to whatever this string names must not
     *                       alter what this row says was accepted.
     * @param source one of the sources {@code ConsentService.Source} declares
     *               ({@code STOREFRONT}, {@code SUPPORT_AGENT}, {@code IMPORT},
     *               {@code MIGRATION}, {@code API}) — a caller acting for a
     *               customer's own storefront action passes {@code STOREFRONT}
     * @param evidenceReference an opaque pointer to the accepted content
     *                          (a document id, a version number), or null
     * @return the new {@code consent_decisions} row id
     */
    UUID recordGrant(
            UUID tenantId,
            UUID accountId,
            @Nullable UUID brandId,
            String purpose,
            @Nullable String channel,
            String policyVersion,
            String source,
            @Nullable String evidenceReference);

    /**
     * Records that the customer withdrew a consent they had granted, right now (ADR 0159, Decision 6).
     *
     * <p>The one withdrawal this port records: a customer's own act, in their own storefront session, of
     * taking back a purpose they granted through their own storefront action -- the review comment they
     * agreed to show publicly, and then did not want shown. It is refused for any other source
     * ({@code IMPORT}, {@code MIGRATION}, {@code SUPPORT_AGENT}, {@code API}), because a withdrawal made
     * by anybody else is a different act with different evidence, and refused when the purpose is not
     * currently granted, because withdrawing nothing would write a decision that never happened. Always
     * dated now, never backdated.
     *
     * <p>When {@code evidenceReference} is given, "currently granted" means the grant that carries that
     * same reference: a purpose granted once per review (ADR 0159) is withdrawn once per review, and
     * withdrawing the first does not make the second look withdrawn. Without a reference it means the
     * purpose's latest decision in the scope.
     *
     * @param source must be {@code STOREFRONT}
     * @return the new {@code WITHDRAWN} {@code consent_decisions} row id
     * @throws IllegalArgumentException when {@code source} is not {@code STOREFRONT}
     * @throws IllegalStateException    when the purpose (or, with a reference, that reference's grant) is not
     *                                  currently granted
     */
    UUID recordWithdrawal(
            UUID tenantId,
            UUID accountId,
            @Nullable UUID brandId,
            String purpose,
            @Nullable String channel,
            String policyVersion,
            String source,
            @Nullable String evidenceReference);
}
