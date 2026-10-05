package uz.horecaos.platform.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.inventory.api.InventoryConfigurationKeys;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcStopMaterialisationStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcStopMaterialisationStore.BrandRef;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationWriteGuard;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Refuses to turn {@code inventory.stops.read_enabled} off until it would lose nothing nobody has
 * been told about (ADR 0141, rollback switch three).
 *
 * <p>Turning it off is the only switch that can sell a stopped dish again. So for every brand in
 * the scope of the write that has ever had a stop, the write is refused with {@code 409
 * RESOURCE_CONFLICT {conflict: MATERIALISATION_REQUIRED}} unless
 *
 * <ol>
 *   <li>a finished materialisation run of that brand has had its report acknowledged by a holder
 *       of {@code inventory.stop.manage} at brand scope, and
 *   <li>every stop of the brand that is in force now is in the set of stops such a run carried
 *       (a stop created after the run is not, and re-blocks the switch).
 * </ol>
 *
 * <p>A brand that has never had a stop has nothing to lose and is not asked for a run. Only
 * <em>turning off</em> is guarded: turning it on, or recording an explicit null (which continues
 * resolution to the value above), restores what the switch withheld.
 *
 * <p>Called inside the configuration author's transaction, before any row is written, so a refusal
 * leaves no value, no cache eviction and no audit fact behind. The look itself is made in a
 * transaction of its own: binding the platform ({@code SET LOCAL ROLE}, see {@link TenantRlsSession})
 * is scoped to a transaction, and a platform-wide write must not leave the author's own statements
 * running under the bypass role for the rest of theirs.
 */
@Component
public class StopReadSwitchGuard implements ConfigurationWriteGuard {

    /** The stable code of the refusal, on {@code RESOURCE_CONFLICT}. */
    public static final String MATERIALISATION_REQUIRED = "MATERIALISATION_REQUIRED";

    private final JdbcStopMaterialisationStore materialisation;
    private final TenantRlsSession rls;
    private final Clock clock;
    private final TransactionTemplate look;

    public StopReadSwitchGuard(
            JdbcStopMaterialisationStore materialisation,
            TenantRlsSession rls,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.materialisation = materialisation;
        this.rls = rls;
        this.clock = clock;
        this.look = new TransactionTemplate(transactionManager);
        this.look.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.look.setReadOnly(true);
    }

    @Override
    public void beforeSet(ConfigurationKey<?> key, ResourceScope scope, @Nullable Object value, boolean explicitNull) {
        if (!InventoryConfigurationKeys.STOPS_READ_ENABLED_CODE.equals(key.code())) {
            return;
        }
        if (explicitNull || !Boolean.FALSE.equals(value)) {
            return;
        }
        Instant now = clock.instant();
        List<BrandRef> blocked = look.execute(status -> {
            List<BrandRef> found = new ArrayList<>();
            for (BrandRef brand : brandsInScope(scope)) {
                rls.bindTenant(brand.tenantId());
                boolean acknowledged = materialisation.hasAcknowledgedRun(brand.tenantId(), brand.brandId());
                int uncarried =
                        materialisation.stopsInForceNoAcknowledgedRunCarried(brand.tenantId(), brand.brandId(), now);
                if (!acknowledged || uncarried > 0) {
                    found.add(brand);
                }
            }
            return found;
        });
        if (blocked == null || blocked.isEmpty()) {
            return;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("conflict", MATERIALISATION_REQUIRED);
        properties.put("blockedBrandCount", blocked.size());
        if (scope.type() != ResourceScope.ScopeType.PLATFORM) {
            // Within one tenant a brand id is the tenant's own; across tenants it is not for the
            // caller of a platform-wide write to be handed.
            properties.put(
                    "blockedBrandIds",
                    blocked.stream().map(BrandRef::brandId).map(UUID::toString).toList());
        }
        throw new ApiException(
                ErrorCode.RESOURCE_CONFLICT,
                "Stops cannot be switched off until a materialisation run has carried every stop in force and "
                        + "its report was acknowledged",
                properties);
    }

    private List<BrandRef> brandsInScope(ResourceScope scope) {
        return switch (scope.type()) {
            case PLATFORM -> {
                rls.bindPlatform();
                yield materialisation.allBrandsWithStops();
            }
            case TENANT -> {
                UUID tenantId = requireTenant(scope);
                rls.bindTenant(tenantId);
                yield materialisation.brandsWithStops(tenantId).stream()
                        .map(brandId -> new BrandRef(tenantId, brandId))
                        .toList();
            }
            case BRAND, LOCATION -> {
                UUID tenantId = requireTenant(scope);
                UUID brandId = scope.brandId();
                if (brandId == null) {
                    throw new IllegalStateException("A brand-scoped write names a brand");
                }
                rls.bindTenant(tenantId);
                // A brand that has never had a stop has nothing to lose, here as at tenant scope.
                yield materialisation.brandsWithStops(tenantId).contains(brandId)
                        ? List.of(new BrandRef(tenantId, brandId))
                        : List.of();
            }
        };
    }

    private static UUID requireTenant(ResourceScope scope) {
        UUID tenantId = scope.tenantId();
        if (tenantId == null) {
            throw new IllegalStateException("A tenant-scoped write names a tenant");
        }
        return tenantId;
    }
}
