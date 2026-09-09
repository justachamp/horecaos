package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerPhoneLookup;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderDirectory;

/**
 * The phone lookup beside operator-assisted order creation (ADR 0039, orders.md
 * &sect;5.3): "an operator needs to find a returning customer by phone."
 *
 * <p>Resolves through the same {@link CustomerPhoneLookup} port {@code
 * ScreenPopQueryService} already uses for ADR 0064's screen-pop — a keyed hash
 * lookup over {@code customer.customer_accounts}, never a {@code LIKE} over
 * plaintext — and never returns a decrypted contact value: a name from the
 * account's own profile, masked, plus the order history {@link OrderDirectory}
 * already exposes across the module boundary for exactly this purpose.
 *
 * <p>Every call writes a {@link AuditClass#SECURITY} fact, matched or not,
 * because this screen is a PII surface pointed at the tenant's entire customer
 * base: a lookup that finds nobody is still a read against all of it.
 *
 * <p>Kept apart from {@link OperatorOrderingService}, which places the order
 * once a customer is resolved. The two share nothing at runtime — a cart and a
 * checkout transaction on one side, a PII port and an audit recorder on the
 * other — so folding them into one class would only give every test of either
 * half a stand-in for the collaborators the other half needs.
 */
@Service
public class OperatorCustomerLookupService {

    private static final int RECENT_ORDER_LOOKUP_LIMIT = 25;
    private static final String LOOKUP_ACTION = "customer.phone_lookup.performed";
    private static final String LOOKUP_CORRELATION_FALLBACK = "operator-phone-lookup";
    private static final String LOOKUP_REASON = "Operator phone lookup for order intake (ADR 0039)";

    private final CustomerPhoneLookup customerLookup;
    private final OrderDirectory orderDirectory;
    private final AuditRecorder audit;
    private final Clock clock;

    public OperatorCustomerLookupService(
            CustomerPhoneLookup customerLookup, OrderDirectory orderDirectory, AuditRecorder audit, Clock clock) {
        this.customerLookup = customerLookup;
        this.orderDirectory = orderDirectory;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Resolves a phone number to the customer accounts that hold it, for the
     * operator to pick from — never to merge (ADR 0015's prohibition on
     * automatic phone-based merging is not relaxed here, and this method
     * offers no merge control).
     *
     * <p>Zero, one or several results are all ordinary answers: the {@code
     * normalized_hash} index behind {@link CustomerPhoneLookup} is
     * deliberately not unique, because a household shares a phone and a
     * recycled number changes owner.
     */
    @Transactional
    public List<PhoneLookupCandidate> lookupByPhone(
            UUID tenantId, UUID brandId, UUID locationId, String rawPhone, ActorRef actor, String capabilityUsed) {

        List<CustomerAccountRef> matches = customerLookup.findByPhone(tenantId, rawPhone);
        List<PhoneLookupCandidate> candidates = matches.stream()
                .map(ref -> describe(tenantId, brandId, ref.accountId()))
                .toList();

        // Recorded before returning, matched or not: an empty result is still a
        // read against the tenant's whole customer base, and the count is what
        // an investigation needs, never the number that was searched for.
        audit.record(AuditFact.of(LOOKUP_ACTION, AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .because(LOOKUP_REASON)
                .usingCapability(capabilityUsed)
                .changed(Map.of("matchCount", String.valueOf(candidates.size())))
                .correlatedBy(Optional.ofNullable(MDC.get("correlationId")).orElse(LOOKUP_CORRELATION_FALLBACK))
                .occurredAt(clock.instant())
                .build());

        return candidates;
    }

    private PhoneLookupCandidate describe(UUID tenantId, UUID brandId, UUID accountId) {
        String displayName = customerLookup
                .cardProfile(tenantId, accountId)
                .map(CustomerPhoneLookup.CardProfile::displayName)
                .orElse(null);
        List<OrderDirectory.RecentOrder> recent =
                orderDirectory.recentForCustomer(tenantId, brandId, accountId, RECENT_ORDER_LOOKUP_LIMIT);
        Instant lastOrderAt = recent.isEmpty() ? null : recent.get(0).placedAt();
        return new PhoneLookupCandidate(accountId, maskName(displayName), lastOrderAt, recent.size());
    }

    /**
     * Masks a display name for the lookup card (orders.md &sect;5.3): enough
     * to recognise, not enough to read whole, because this screen is a search
     * over the tenant's entire customer base and the operator has not yet
     * picked which account this order belongs to. Once picked, the ordinary,
     * unmasked name is what every other screen — the order detail, the
     * customer record — already shows.
     */
    static @Nullable String maskName(@Nullable String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        StringBuilder masked = new StringBuilder(displayName.length());
        boolean atWordStart = true;
        for (int i = 0; i < displayName.length(); i++) {
            char c = displayName.charAt(i);
            if (Character.isWhitespace(c)) {
                masked.append(c);
                atWordStart = true;
                continue;
            }
            masked.append(atWordStart ? c : '*');
            atWordStart = false;
        }
        return masked.toString();
    }

    /**
     * One phone-lookup result, as the New order screen's customer pane
     * renders it (orders.md &sect;5.3): masked name, last-order date and
     * order count, never a contact value or an address.
     */
    public record PhoneLookupCandidate(
            UUID accountId,
            @Nullable String maskedDisplayName,
            @Nullable Instant lastOrderAt,
            int recentOrderCount) {}
}
