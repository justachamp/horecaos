package uz.horecaos.platform.referral.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.CodeRow;

/**
 * One referral code per customer per brand, minted the first time they ask
 * for it (operations §6.6 Referrals).
 *
 * <p>Eight characters of Crockford base32 from a CSPRNG with {@code I}, {@code
 * L}, {@code O}, and {@code U} removed — the same alphabet ADR 0044 specifies
 * for a coded benefit grant, so the two code surfaces a customer can encounter
 * look and behave the same way. Sequential or short codes are enumerable, and
 * an enumerable code here is redeemed by whoever guesses it first rather than
 * the friend it was shared with.
 *
 * <p>Issuing a code does not require a referral program to be running: a code
 * is a durable, harmless identity a customer can hold before or after a
 * program exists, and requiring one active only when it is actually redeemed
 * ({@link ReferralRedemptionService}) means turning a program off and back on
 * never orphans a code a customer already shared.
 */
@Service
public class ReferralCodeService {

    private static final int CODE_LENGTH = 8;
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford base32, minus I L O U
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcReferralStore store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ReferralCodeService(JdbcReferralStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** The caller's own code for this brand, minting one on first use. */
    @Transactional
    public CodeRow myCode(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return store.findCodeByOwner(tenantId, brandId, customerAccountId)
                .orElseGet(() -> mint(tenantId, brandId, customerAccountId));
    }

    private CodeRow mint(UUID tenantId, UUID brandId, UUID customerAccountId) {
        Instant now = clock.instant();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return store.insertCodeIfAbsent(
                        UUID.randomUUID(), tenantId, brandId, customerAccountId, generate(), now);
            } catch (DataIntegrityViolationException collision) {
                // uq_referral_code_value: a different owner already holds the
                // generated code. Retried with a fresh one rather than
                // propagated — this is the ordinary cost of a random code, not
                // a caller error.
            }
        }
        throw new IllegalStateException("Could not mint a unique referral code for account " + customerAccountId
                + " after " + MAX_ATTEMPTS + " attempts");
    }

    private String generate() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
