package uz.horecaos.platform.courier.web;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The courier a request is about: always the caller's own, resolved from the signed token (ADR 0049).
 *
 * <p>A courier's own surface never takes a courier id from a path or a body, so there is no
 * parameter through which one courier could name another. An account that is nobody's courier in
 * this tenant is answered as a resource that does not exist, which is also what a courier of
 * another tenant gets: the lookup is by subject <em>within the path's tenant</em>.
 */
@Component
class CallingCourier {

    private final JdbcCourierStore couriers;
    private final CurrentActor currentActor;

    CallingCourier(JdbcCourierStore couriers, CurrentActor currentActor) {
        this.couriers = couriers;
        this.currentActor = currentActor;
    }

    /** The caller's token subject. Identifies a login, never a person's name. */
    String subject() {
        return currentActor.get().subject();
    }

    /** The id of the caller's own courier row in this tenant. */
    UUID idIn(UUID tenantId) {
        return couriers.findCourierBySubject(tenantId, subject())
                .map(JdbcCourierStore.CourierRow::id)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The caller is not a courier of this tenant"));
    }
}
