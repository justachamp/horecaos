package uz.horecaos.platform.courier.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.BranchBindingRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;

/**
 * What the platform knows about the courier who is asking, for the courier's own app (ADR 0179).
 *
 * <p>The courier is found from the caller's own token subject within the tenant and nothing else:
 * there is no id parameter, so there is no way to ask about somebody else, and an account that is
 * nobody's courier gets {@link Optional#empty()}, which the controller answers as a resource that
 * does not exist. What comes back is deliberately the operational minimum -- a label, a status, the
 * registration's validity date, the branches, the open shift. No legal name (it stays protected on
 * the courier row), no registration number, no phone, no ledger.
 *
 * <p>One read transaction, so a courier whose shift closes between two of the four statements is
 * not shown a shift with a state that no longer fits it.
 */
@Service
public class CourierSelfQueryService {

    private final JdbcCourierStore couriers;
    private final JdbcCourierShiftStore shifts;

    public CourierSelfQueryService(JdbcCourierStore couriers, JdbcCourierShiftStore shifts) {
        this.couriers = couriers;
        this.shifts = shifts;
    }

    @Transactional(readOnly = true)
    public Optional<SelfView> viewOf(UUID tenantId, String principalSubject) {
        return couriers.findCourierBySubject(tenantId, principalSubject).map(courier -> {
            return new SelfView(
                    courier,
                    couriers.findLiveEngagement(tenantId, courier.id()).orElse(null),
                    couriers.bindingsOf(tenantId, courier.id()),
                    shifts.findLiveShift(tenantId, courier.id()).orElse(null));
        });
    }

    /**
     * @param engagement the live engagement, absent for a courier who has none (ended, or never
     *                   registered); the registration facts are on it
     * @param branches   the branches the courier is bound to, main branch first
     * @param openShift  the shift the courier has open, absent when they are not working
     */
    public record SelfView(
            CourierRow courier,
            @Nullable EngagementRow engagement,
            List<BranchBindingRow> branches,
            @Nullable ShiftRow openShift) {}
}
