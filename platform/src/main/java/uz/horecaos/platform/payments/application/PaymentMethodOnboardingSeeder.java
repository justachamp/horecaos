package uz.horecaos.platform.payments.application;

import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.tenancy.api.TenantCreated;

/**
 * Gives every new tenant the three payment methods almost all of them start
 * with (ADR 0038's checklist line "seed each tenant's methods", row 10.6).
 *
 * <p>Without this the registry "only ever grows by accident, from whatever a
 * checkout happened to tender" — the operations gap map's own words —
 * because {@link JdbcSettlementStore#registerMethod} only ever runs lazily,
 * mid-checkout. A tenant that has registered nothing cannot enable a single
 * payment method on a single channel, since {@code tenant.channel_payment_methods
 * .payment_method_code} is a foreign key onto this registry (V0175): the
 * pilot's very first settings walkthrough would hit that foreign key on the
 * first checkbox.
 *
 * <p>A listener rather than a line inside tenant creation, the same
 * separation {@code StorefrontChannelSeeder} draws for the identical reason:
 * seeding a starter registry is a consequence ADR 0038 attaches to tenant
 * creation, not part of what creating a tenant means. Plain {@link
 * EventListener}, synchronous and therefore inside the creating transaction —
 * {@code StorefrontChannelSeeder}'s own doc gives the reason: a tenant that
 * exists without a single payment method to register a channel against is
 * exactly the broken state a listener here exists to prevent.
 *
 * <p>Idempotent by {@link JdbcSettlementStore#registerMethod}'s own {@code ON
 * CONFLICT ... DO NOTHING}: a replayed {@link TenantCreated} — Spring's
 * in-process retry included — registers nothing a second time.
 *
 * <p>CASH is {@code OPERATOR} responsibility and PARTNER for CLICK and PAYME,
 * the exact backfill V0175 already applies to every pre-existing tenant that
 * had never registered them explicitly — a fresh tenant seeded here and one
 * migrated in by that backfill are indistinguishable.
 */
@Component
public class PaymentMethodOnboardingSeeder {

    private final JdbcSettlementStore store;
    private final Clock clock;

    public PaymentMethodOnboardingSeeder(JdbcSettlementStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @EventListener
    public void on(TenantCreated created) {
        var tenantId = created.tenantId().value();
        var now = clock.instant();
        store.registerMethod(tenantId, "CASH", "Cash", "OPERATOR", false, now);
        store.registerMethod(tenantId, "CLICK", "Click", "PARTNER", false, now);
        store.registerMethod(tenantId, "PAYME", "Payme", "PARTNER", false, now);
    }
}
