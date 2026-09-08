package uz.horecaos.platform.commercial.application;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.UsageMeter;
import uz.horecaos.platform.commercial.api.UsageMovement;
import uz.horecaos.platform.tenancy.api.BrandCreated;
import uz.horecaos.platform.tenancy.api.LocationCreated;
import uz.horecaos.platform.tenancy.api.TenancyEvent;

/**
 * The caller {@link UsageMeter} was missing for {@code brands.max_count} and
 * {@code locations.max_count} (ADR 0021): a listener here rather than a call
 * inside {@code TenantControlPlaneService}, because that is the direction this
 * platform's module graph already runs. {@code commercial} depends on {@code
 * tenancy.api} for {@link EnforcementCeiling}'s own ADR 0030 configuration
 * lookup, so {@code tenancy} cannot depend back on {@code commercial.api}
 * without closing a cycle {@code ModularArchitectureTests} exists to catch —
 * the same reasoning {@code iam.application.GrantManagementService}'s own
 * Javadoc gives for keeping the platform-grant maker-checker gate in {@code
 * audit} rather than in {@code iam}. Listening for the fact tenancy already
 * publishes reaches the same outcome without the edge.
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, the phase every other cross-module
 * trigger in this codebase uses ({@code OrderCompletionAccrualTrigger}, {@code
 * ReferralOrderCompletionTrigger}): a brand or location that exists without the
 * usage fact that it was created would be a ledger a rebuild could never
 * reconstruct, since the row it should have been reconstructed from was never
 * written. Running before the same commit {@code TenantControlPlaneService}
 * still holds open keeps the two atomic without giving this class a database
 * connection of its own.
 *
 * <p>No enforcement here, only metering. {@code locations.max_count} is the ADR's
 * own "line every plan is actually sold on", and a hard refusal belongs beside
 * the insert it refuses — before the row is written, per the ADR's own
 * enforcement semantics — not after it, inside a listener that can only unwind a
 * transaction that already did the work. {@link
 * uz.horecaos.platform.catalog.application.CatalogAuthoringService#createProduct}
 * is where this ADR's one enforced limit lives instead, for exactly that reason.
 *
 * <p>Idempotent by the same mechanism every meter caller relies on: {@link
 * UsageMeter#record} is keyed on {@code (tenantId, entitlementKey, sourceType,
 * sourceEventId)}, and the brand or location's own id is stable and never reused,
 * so a redelivered event — Spring's in-process retry included — is dropped
 * rather than double-counted.
 */
@Component
public class TenancyUsageMeterTrigger {

    private final UsageMeter usage;

    public TenancyUsageMeterTrigger(UsageMeter usage) {
        this.usage = usage;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTenancyEvent(TenancyEvent event) {
        if (event instanceof BrandCreated created) {
            usage.record(UsageMovement.of(
                    created.tenantId().value(),
                    EntitlementKeys.BRANDS_MAX_COUNT,
                    1,
                    "tenancy.BrandCreated",
                    created.brandId().value().toString(),
                    created.occurredAt()));
        } else if (event instanceof LocationCreated created) {
            usage.record(new UsageMovement(
                    created.tenantId().value(),
                    EntitlementKeys.LOCATIONS_MAX_COUNT,
                    1,
                    "tenancy.LocationCreated",
                    created.locationId().value().toString(),
                    created.occurredAt(),
                    Map.of("brand_id", created.brandId().value().toString())));
        }
    }
}
