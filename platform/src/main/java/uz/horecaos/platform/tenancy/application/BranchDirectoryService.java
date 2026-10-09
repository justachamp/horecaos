package uz.horecaos.platform.tenancy.application;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.tenancy.api.BranchDirectory;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontLocationProfileStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontLocationProfileStore.BranchRow;

/**
 * {@link BranchDirectory} over the storefront's own location profile read and
 * the timetable bound to each fulfilment mode (ADR 0069, ADR 0036).
 *
 * <p>The weekly windows are read from the bound schedule's rules and nothing
 * else. A dated exception (a holiday closure) is deliberately left out of
 * "what are your hours": it is not a weekly fact, and whether the branch is open
 * today is the serviceability resolver's answer, which already accounts for it.
 */
@Service
public class BranchDirectoryService implements BranchDirectory {

    /** The two modes a customer asks hours about; dine-in has a floor, not a promise made over a chat. */
    private static final List<FulfillmentMode> ASKED_MODES = List.of(FulfillmentMode.PICKUP, FulfillmentMode.DELIVERY);

    private final JdbcStorefrontLocationProfileStore profiles;
    private final JdbcServiceabilityStore schedules;

    public BranchDirectoryService(JdbcStorefrontLocationProfileStore profiles, JdbcServiceabilityStore schedules) {
        this.profiles = profiles;
        this.schedules = schedules;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Branch> activeBranches(UUID tenantId, UUID brandId) {
        List<Branch> branches = new ArrayList<>();
        for (BranchRow row : profiles.listActiveForBrand(tenantId, brandId)) {
            Map<FulfillmentMode, List<WeeklyWindow>> hours = new EnumMap<>(FulfillmentMode.class);
            for (FulfillmentMode mode : ASKED_MODES) {
                schedules.scheduleFor(tenantId, row.locationId(), mode).ifPresent(bound -> {
                    List<WeeklyWindow> windows = bound.schedule().rules().stream()
                            .map(rule -> new WeeklyWindow(rule.dayOfWeek(), rule.opensAt(), rule.closesAt()))
                            .toList();
                    if (!windows.isEmpty()) {
                        hours.put(mode, windows);
                    }
                });
            }
            var profile = row.profile();
            GeoPoint point = profile.latitude() != null && profile.longitude() != null
                    ? new GeoPoint(profile.latitude(), profile.longitude())
                    : null;
            branches.add(new Branch(
                    row.locationId(),
                    profile.displayName(),
                    profile.addressLine(),
                    profile.district(),
                    profile.city(),
                    profile.landmark(),
                    profile.contactPhone(),
                    point,
                    row.timezone(),
                    hours));
        }
        return List.copyOf(branches);
    }
}
