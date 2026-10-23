package uz.horecaos.platform.reviews.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.customers.spi.CustomerErasureGuard;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore;

/**
 * Reports the service-recovery cases a customer still has open (ADR 0029: refuse while a dispute is open).
 *
 * <p>The platform has no chargeback or dispute entity; the dispute a customer can have with a restaurant is the
 * service-recovery case a low rating opens (ADR 0159), and it is the only record of one. While a case is {@code NEW} or
 * {@code IN_PROGRESS} the restaurant is mid-conversation with the customer about a remedy, and erasing the notes and the
 * account under it would end the conversation in the middle. A case that is resolved or closed does not block.
 */
@Component
public class ReviewsErasureGuard implements CustomerErasureGuard {

    /** The code the console maps to words. */
    public static final String OPEN_RECOVERY_CASE = "OPEN_RECOVERY_CASE";

    private final JdbcRecoveryCaseStore cases;

    public ReviewsErasureGuard(JdbcRecoveryCaseStore cases) {
        this.cases = cases;
    }

    @Override
    public List<Block> blocksFor(UUID tenantId, UUID customerAccountId) {
        int open = cases.openCasesOf(tenantId, customerAccountId);
        return open == 0 ? List.of() : List.of(new Block(OPEN_RECOVERY_CASE, open));
    }
}
