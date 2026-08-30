package uz.horecaos.platform.marketing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import uz.horecaos.platform.marketing.api.CampaignMessagePort;

/**
 * A stand-in for the ADR 0020 delivery path.
 *
 * <p>The seam is stubbed rather than driven end to end on purpose: what these
 * tests are about is who gets chosen, who gets refused, and what it costs, and
 * building a genuine notification, template version, endpoint, and provider
 * binding here would drag in four modules to assert nothing about marketing.
 * {@code NotificationDeliveryTests} already covers the other side.
 *
 * <p>It does honour the one contract that matters across the boundary:
 * {@link #enqueue} is idempotent on the key, so a replayed batch produces the same
 * notification id rather than a second message.
 */
final class FakeCampaignMessagePort implements CampaignMessagePort {

    private final Map<String, UUID> byIdempotencyKey = new LinkedHashMap<>();
    private final List<MarketingMessage> sent = new ArrayList<>();
    private final Map<String, String> bodies = new LinkedHashMap<>();
    private boolean wired = true;

    FakeCampaignMessagePort withBody(String locale, String body) {
        bodies.put(locale, body);
        return this;
    }

    @Override
    public UUID enqueue(MarketingMessage message) {
        sent.add(message);
        return byIdempotencyKey.computeIfAbsent(message.idempotencyKey(),
                key -> UUID.randomUUID());
    }

    @Override
    public Map<String, String> templateBodies(UUID tenantId, UUID brandId, String templateKey,
            String channel) {
        return Map.copyOf(bodies);
    }

    @Override
    public boolean isWired() {
        return wired;
    }

    void unwire() {
        wired = false;
    }

    List<MarketingMessage> sent() {
        return List.copyOf(sent);
    }

    /** How many distinct messages exist, as opposed to how many calls were made. */
    int distinctMessages() {
        return byIdempotencyKey.size();
    }
}
