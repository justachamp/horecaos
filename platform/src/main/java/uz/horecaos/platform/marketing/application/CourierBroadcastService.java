package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore.CourierBroadcastRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A dispatcher's own operational SMS blast to a courier group or every
 * active courier (V0307, operations §6.4b) — a shift change, a weather
 * closure, a route closure. Never a customer campaign: see the migration's
 * own doc for why couriers cannot reuse {@link CampaignService}'s pipeline,
 * and {@link CampaignMessagePort#isWired} for the honesty this class shares
 * with it — a send is refused, visibly, rather than silently producing
 * nothing.
 */
@Service
public class CourierBroadcastService {

    /** The one channel V0307's own CHECK allows today. */
    private static final String CHANNEL = "SMS";

    private final JdbcCourierBroadcastStore store;
    private final CampaignMessagePort messages;
    private final Clock clock;

    public CourierBroadcastService(JdbcCourierBroadcastStore store, CampaignMessagePort messages, Clock clock) {
        this.store = store;
        this.messages = messages;
        this.clock = clock;
    }

    @Transactional
    public UUID draft(
            UUID tenantId,
            UUID brandId,
            String targetKind,
            @Nullable UUID targetGroupId,
            String message,
            UUID authorId) {
        if (!"ALL_ACTIVE".equals(targetKind) && !"GROUP".equals(targetKind)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "targetKind must be ALL_ACTIVE or GROUP");
        }
        if ("GROUP".equals(targetKind) == (targetGroupId == null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A GROUP broadcast names a targetGroupId; ALL_ACTIVE carries none");
        }
        if (message == null || message.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "message must not be blank");
        }
        if (message.length() > 480) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "message must be 480 characters or fewer");
        }

        UUID id = Ids.newId();
        store.insert(id, tenantId, brandId, targetKind, targetGroupId, message, authorId, clock.instant());
        return id;
    }

    /**
     * Resolves the target and sends, or refuses visibly when the channel has
     * no wired delivery path — {@code isWired} is the same fix this wave
     * applies to customer campaigns, applied here at draft-to-send time
     * rather than discovered after a dispatcher already believes couriers
     * were told.
     */
    @Transactional
    public CourierBroadcastRow send(UUID tenantId, UUID broadcastId) {
        CourierBroadcastRow broadcast = require(tenantId, broadcastId);
        if (!"DRAFT".equals(broadcast.status())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This broadcast is not a draft, so it cannot be sent");
        }

        Instant now = clock.instant();
        int recipientCount = store.countTarget(tenantId, broadcast.targetKind(), broadcast.targetGroupId());

        if (!messages.isWired(CHANNEL)) {
            String reason = "No ADR 0020 delivery path is wired for SMS yet";
            store.recordFailed(tenantId, broadcastId, reason, now);
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, reason);
        }

        // No ADR 0020 delivery path is wired for SMS in this build (row 6.4a:
        // "no SMS contract exists"), so the branch above always throws today.
        // This is where a real per-courier enqueue would go once one exists —
        // recordSent is written so the shape is complete and testable ahead
        // of that wiring, not left as a TODO with nothing behind it.
        store.recordSent(tenantId, broadcastId, recipientCount, now);
        return require(tenantId, broadcastId);
    }

    @Transactional(readOnly = true)
    public List<CourierBroadcastRow> list(UUID tenantId, UUID brandId) {
        return store.listByBrand(tenantId, brandId);
    }

    public CourierBroadcastRow require(UUID tenantId, UUID broadcastId) {
        return store.find(tenantId, broadcastId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "No courier broadcast %s belongs to this tenant".formatted(broadcastId)));
    }
}
