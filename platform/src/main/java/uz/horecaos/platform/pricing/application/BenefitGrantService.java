package uz.horecaos.platform.pricing.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.DiscountShape;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcBenefitGrantStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcBenefitGrantStore.GrantRow;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Minting and redeeming a per-recipient coded benefit grant (operations
 * §6.2a, ADR 0018, ADR 0044) — V0265's {@code pricing.benefit_grants}.
 *
 * <p>The shape ADR 0044's own table draws: a shared promo code
 * ({@code pricing.coupon_codes}, {@link PromoCodeAuthoringService}) is one row
 * spent by whoever types it; a benefit grant is bound to one
 * {@code customer_account_id} at mint time and single-use. This is the
 * mechanism a late-order apology needs and a shared code word cannot give —
 * "redeemable by exactly this customer" is a fact of the row here, not an
 * inference from a per-customer limit of one that happens not to have been
 * spent yet.
 *
 * <p>Ten characters of Crockford base32 from a CSPRNG with {@code I}, {@code
 * L}, {@code O}, and {@code U} removed — the alphabet and length ADR 0044
 * specifies for a coded benefit grant, mirroring
 * {@code ReferralCodeService}'s own choice of the same alphabet for the same
 * reason: a code a customer reads back over the phone should not contain a
 * character that is ambiguous read aloud. Stored as a SHA-256 hash only
 * ({@link JdbcPromoCodeStore#hash}), never in the clear — this class's own
 * doc explains why an ADR 0029 envelope ciphertext is deferred rather than
 * built here.
 */
@Service
public class BenefitGrantService {

    private static final int CODE_LENGTH = 10;
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford base32, minus I L O U
    private static final int MAX_MINT_ATTEMPTS = 5;
    private static final int MAX_PERCENTAGE_BASIS_POINTS = 10_000;

    private final JdbcBenefitGrantStore store;
    private final AuditRecorder audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public BenefitGrantService(JdbcBenefitGrantStore store, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param customerAccountId the one account this grant will ever be
     *                          redeemable by
     * @param minBasketMinor    0 for no minimum
     * @param sourceType        {@code OPERATOR_MANUAL}, {@code RECOVERY_CASE},
     *                          {@code CAMPAIGN} or {@code TRIGGER} — only the
     *                          first has a caller in this wave
     * @param validFrom         null takes effect immediately
     */
    public record MintRequest(
            UUID customerAccountId,
            DiscountShape shape,
            long value,
            @Nullable Long maximumDiscountMinor,
            @Nullable String currency,
            long minBasketMinor,
            String sourceType,
            @Nullable UUID sourceId,
            @Nullable Instant validFrom,
            @Nullable Instant expiresAt) {}

    /** @param plaintextCode present only in the mint response — never stored, never returned again */
    public record MintedGrant(UUID grantId, String plaintextCode, String codeHint, Instant validFrom) {}

    @Transactional
    public MintedGrant mint(UUID tenantId, UUID brandId, MintRequest request, ActorRef actor, String correlationId) {
        validate(request);
        Instant now = clock.instant();
        Instant validFrom = request.validFrom() != null ? request.validFrom() : now;

        for (int attempt = 0; attempt < MAX_MINT_ATTEMPTS; attempt++) {
            String code = generate();
            UUID id = Ids.newId();
            try {
                store.insert(
                        id,
                        tenantId,
                        brandId,
                        request.customerAccountId(),
                        request.shape().name(),
                        request.value(),
                        request.maximumDiscountMinor(),
                        request.currency(),
                        request.minBasketMinor(),
                        JdbcPromoCodeStore.hash(code),
                        JdbcPromoCodeStore.hint(code),
                        request.sourceType(),
                        request.sourceId(),
                        validFrom,
                        request.expiresAt(),
                        now);

                // The account id, never the code: the plaintext is the bearer
                // secret and the audit trail is not the place a support ticket
                // or a database dump gets to read it back out.
                audit.record(AuditFact.of("PRICING_BENEFIT_GRANT_MINTED", AuditClass.BUSINESS)
                        .by(actor)
                        .at(ResourceScope.brand(tenantId, brandId))
                        .target("PricingBenefitGrant", id)
                        .because("Minted a %s grant for one named customer via %s"
                                .formatted(request.shape(), request.sourceType()))
                        .changed(Map.of(
                                "customerAccountId", request.customerAccountId(),
                                "benefitType", request.shape().name(),
                                "sourceType", request.sourceType()))
                        .correlatedBy(correlationId)
                        .occurredAt(now)
                        .build());

                return new MintedGrant(id, code, JdbcPromoCodeStore.hint(code), validFrom);
            } catch (DataIntegrityViolationException collision) {
                // ux_benefit_grant_code: a different grant in this tenant already holds
                // the generated code. Retried with a fresh one rather than propagated —
                // the ordinary cost of a random ten-character code, not a caller error.
            }
        }
        throw new IllegalStateException(
                "Could not mint a unique benefit grant code after " + MAX_MINT_ATTEMPTS + " attempts");
    }

    /**
     * The redemption check and the redemption, together: whether a presented
     * code may be spent by this customer right now, and if so, claims it
     * atomically against exactly this order.
     *
     * <p>Every refusal is a {@link RedemptionOutcome.Reason} rather than a
     * boolean, for the same reason {@code MarketingEligibility} returns a
     * {@code RefusalReason} rather than dropping a candidate silently: "wrong
     * customer" and "already redeemed" are different facts a support agent
     * needs told apart.
     */
    @Transactional
    public RedemptionOutcome redeem(UUID tenantId, String presentedCode, UUID customerAccountId, UUID orderId) {
        String normalized = JdbcPromoCodeStore.normalize(presentedCode);
        Optional<GrantRow> found = store.findByCodeHash(tenantId, JdbcPromoCodeStore.hash(normalized));
        if (found.isEmpty()) {
            return RedemptionOutcome.refused(RedemptionOutcome.Reason.CODE_NOT_FOUND);
        }
        GrantRow row = found.get();
        if (!row.customerAccountId().equals(customerAccountId)) {
            // Refused without distinguishing "code exists" from "code does not
            // exist" in the reason returned to the caller below this method —
            // WRONG_CUSTOMER is deliberately as far as this leaks, never the
            // account it actually belongs to.
            return RedemptionOutcome.refused(RedemptionOutcome.Reason.WRONG_CUSTOMER);
        }
        Instant now = clock.instant();
        if (!"ACTIVE".equals(row.status())) {
            return RedemptionOutcome.refused(RedemptionOutcome.Reason.ALREADY_USED);
        }
        if (now.isBefore(row.validFrom())) {
            return RedemptionOutcome.refused(RedemptionOutcome.Reason.NOT_YET_ACTIVE);
        }
        if (row.expiresAt() != null && !now.isBefore(row.expiresAt())) {
            return RedemptionOutcome.refused(RedemptionOutcome.Reason.EXPIRED);
        }

        boolean claimed = store.redeemIfActive(tenantId, row.grantId(), customerAccountId, orderId, null, now);
        if (!claimed) {
            // Lost a race against another attempt on the same grant between the
            // reads above and this UPDATE — the same outcome as ALREADY_USED
            // from the caller's point of view.
            return RedemptionOutcome.refused(RedemptionOutcome.Reason.ALREADY_USED);
        }
        return RedemptionOutcome.redeemed(
                row.grantId(),
                DiscountShape.valueOf(row.benefitType()),
                row.value(),
                row.maximumDiscountMinor(),
                row.currency(),
                row.minBasketMinor());
    }

    public List<GrantRow> forCustomer(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return store.listForCustomer(tenantId, brandId, customerAccountId);
    }

    private String generate() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private void validate(MintRequest request) {
        List<String> problems = new ArrayList<>();
        switch (request.shape()) {
            case PERCENTAGE_OFF_ORDER -> {
                if (request.value() <= 0 || request.value() > MAX_PERCENTAGE_BASIS_POINTS) {
                    problems.add("value must be between 1 and 10000 basis points for a percentage discount");
                }
            }
            case FIXED_AMOUNT_OFF_ORDER -> {
                if (request.value() <= 0) {
                    problems.add("value must be positive for a fixed-amount discount");
                }
            }
            case FREE_DELIVERY -> {
                if (request.value() != 0) {
                    problems.add("value must be zero for a free-delivery grant");
                }
            }
        }
        if (request.currency() == null && request.shape() != DiscountShape.FREE_DELIVERY) {
            problems.add("currency is required unless the shape is FREE_DELIVERY");
        }
        if (request.currency() != null && !request.currency().matches("^[A-Z]{3}$")) {
            problems.add("currency must be a 3-letter ISO code");
        }
        if (request.maximumDiscountMinor() != null && request.maximumDiscountMinor() <= 0) {
            problems.add("maximumDiscountMinor must be positive when set, or omitted for uncapped");
        }
        if (request.minBasketMinor() < 0) {
            problems.add("minBasketMinor cannot be negative");
        }
        if (request.expiresAt() != null
                && request.validFrom() != null
                && !request.expiresAt().isAfter(request.validFrom())) {
            problems.add("expiresAt must be after validFrom");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, String.join("; ", problems));
        }
    }

    /** What a redemption attempt produced. */
    public record RedemptionOutcome(
            boolean redeemed,
            @Nullable Reason reason,
            @Nullable UUID grantId,
            @Nullable DiscountShape shape,
            long value,
            @Nullable Long maximumDiscountMinor,
            @Nullable String currency,
            long minBasketMinor) {

        static RedemptionOutcome redeemed(
                UUID grantId,
                DiscountShape shape,
                long value,
                @Nullable Long maximumDiscountMinor,
                @Nullable String currency,
                long minBasketMinor) {
            return new RedemptionOutcome(
                    true, null, grantId, shape, value, maximumDiscountMinor, currency, minBasketMinor);
        }

        static RedemptionOutcome refused(Reason reason) {
            return new RedemptionOutcome(false, reason, null, null, 0, null, null, 0);
        }

        public enum Reason {
            CODE_NOT_FOUND,
            WRONG_CUSTOMER,
            NOT_YET_ACTIVE,
            EXPIRED,
            ALREADY_USED
        }
    }
}
