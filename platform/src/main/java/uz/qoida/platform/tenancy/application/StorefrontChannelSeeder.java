package uz.qoida.platform.tenancy.application;

import java.time.Clock;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import uz.qoida.platform.tenancy.api.SalesChannel;
import uz.qoida.platform.tenancy.api.SalesChannelSystemType;
import uz.qoida.platform.tenancy.api.TenantCreated;
import uz.qoida.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * Gives every new tenant the one channel it certainly has (ADR 0036 rollout).
 *
 * <p>{@code STOREFRONT} is the value {@code catalog.publications.channel} already
 * defaults to, and V0020 makes that column a foreign key to the registry. Without
 * this seed a freshly created tenant could author a menu and not publish it, and
 * the failure would be a foreign-key violation naming a table the operator has
 * never heard of.
 *
 * <p>A listener rather than a line inside {@code TenantControlPlaneService},
 * because seeding a channel is not part of what creating a tenant means — it is a
 * consequence ADR 0036 attaches to it, and attaching it here keeps the two
 * decisions separable. The listener is synchronous and therefore runs inside the
 * creating transaction: a tenant that exists without its storefront channel would
 * be exactly the broken state above.
 */
@Component
public class StorefrontChannelSeeder {

    /** The code migration V0020 backfills for existing tenants. */
    public static final String STOREFRONT_CODE = "STOREFRONT";

    private final JdbcSalesChannelStore store;
    private final Clock clock;

    public StorefrontChannelSeeder(JdbcSalesChannelStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @EventListener
    public void on(TenantCreated created) {
        var tenantId = created.tenantId().value();
        if (store.byCode(tenantId, STOREFRONT_CODE).isPresent()) {
            return;
        }
        store.insert(new SalesChannel(
                java.util.UUID.randomUUID(), tenantId, STOREFRONT_CODE,
                SalesChannelSystemType.WEB, "Storefront", SalesChannel.Status.ACTIVE,
                null, false, true, null, 1), clock.instant());
    }
}
