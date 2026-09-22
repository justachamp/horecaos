package uz.horecaos.platform.tenancy.application;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontLocationProfileStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontLocationProfileStore.LocationProfileRow;

/**
 * "What is this branch called, and where is it" — the one fact the pickup and
 * order-detail screens need that neither the menu nor the fulfilment-mode read
 * carries (ADR 0016).
 */
@Service
public class StorefrontLocationProfileQuery {

    private final JdbcStorefrontLocationProfileStore locations;

    public StorefrontLocationProfileQuery(JdbcStorefrontLocationProfileStore locations) {
        this.locations = locations;
    }

    @Transactional(readOnly = true)
    public Optional<LocationProfile> profile(UUID tenantId, UUID brandId, UUID locationId) {
        return locations.find(tenantId, brandId, locationId).map(StorefrontLocationProfileQuery::viewOf);
    }

    private static LocationProfile viewOf(LocationProfileRow row) {
        return new LocationProfile(
                row.displayName(),
                row.addressLine(),
                row.district(),
                row.city(),
                row.landmark(),
                row.contactPhone(),
                row.latitude(),
                row.longitude());
    }

    /** The published identity of one branch — nothing a receipt does not already carry. */
    public record LocationProfile(
            String displayName,
            @Nullable String addressLine,
            @Nullable String district,
            @Nullable String city,
            @Nullable String landmark,
            @Nullable String contactPhone,
            @Nullable Double latitude,
            @Nullable Double longitude) {}
}
