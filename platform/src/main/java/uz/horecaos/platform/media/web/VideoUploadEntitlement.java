package uz.horecaos.platform.media.web;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.EntitlementGate;
import uz.horecaos.platform.web.api.ApiException;

/**
 * Video is off until the platform owner grants it to the tenant (ADR 0157), asked once for every route
 * that allocates an upload: the tenant's and the brand's.
 *
 * <p>The key is named as a string, as {@code content} does, so media takes no edge to
 * {@code commercial}. Asked of {@code checkGranted}: the plain feature check answers yes for a feature
 * nobody granted under the meter-only default, which is the wrong answer for a switch that is off by
 * default. An unknown key or no gate at all reads as not entitled.
 */
@Component
class VideoUploadEntitlement {

    private static final String VIDEO_ENTITLEMENT = "content.video.enabled";

    private final List<EntitlementGate> entitlements;

    VideoUploadEntitlement(List<EntitlementGate> entitlements) {
        this.entitlements = List.copyOf(entitlements);
    }

    /** @throws ApiException {@code ENTITLEMENT_REQUIRED} (403) when the tenant's plan does not include video */
    void require(UUID tenantId) {
        for (EntitlementGate gate : entitlements) {
            Optional<EntitlementGate.Answer> answer = gate.checkGranted(tenantId, VIDEO_ENTITLEMENT);
            if (answer.isPresent()) {
                if (answer.get().entitled()) {
                    return;
                }
                break;
            }
        }
        throw ApiException.entitlementRequired(VIDEO_ENTITLEMENT);
    }
}
