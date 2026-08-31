package uz.horecaos.platform.reporting.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.protection.FieldProtection;

/**
 * Turns a customer account into the pseudonym reporting is allowed to keep
 * (ADR 0029, ADR 0043).
 *
 * <p>Reporting counts returning customers and cohorts, which needs a stable
 * identifier, and it must never hold one that identifies a person. The ADR 0029
 * keyed lookup hash gives both: equal accounts produce equal values within a
 * tenant, and the value is worthless without the tenant's lookup key.
 *
 * <p>Its own type rather than a call to {@link FieldProtection} scattered through
 * the close job, because the lookup domain is the thing that must not vary. Two
 * domains for the same subject would make the same customer two customers, and
 * the repeat-purchase rate would quietly halve.
 */
@Component
public class SubjectPseudonym {

    /**
     * Distinct from the customer module's own lookup domains on purpose. A shared
     * domain would let a reporting hash be used to confirm a phone number against
     * the customer table, which is exactly the linkage the pseudonym exists to
     * prevent.
     */
    public static final String DOMAIN = "reporting.customer_subject";

    private final FieldProtection protection;

    public SubjectPseudonym(FieldProtection protection) {
        this.protection = protection;
    }

    /**
     * The pseudonym for one customer account.
     *
     * @return null for an order with no customer account, which is not an error
     */
    public @Nullable String of(UUID tenantId, @Nullable UUID customerAccountId) {
        return customerAccountId == null ? null : protection.lookupHash(tenantId, DOMAIN, customerAccountId.toString());
    }
}
