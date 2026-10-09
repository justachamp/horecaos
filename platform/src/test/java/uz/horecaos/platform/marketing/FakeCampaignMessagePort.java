package uz.horecaos.platform.marketing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
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
    private final Map<String, Map<String, String>> bodiesByTemplate = new LinkedHashMap<>();
    private boolean wired = true;
    private String notWiredReason = "NO_PROVIDER_BINDING";
    private final java.util.Set<String> refusedPurposes = new java.util.HashSet<>();
    private final List<String> wiringAsked = new ArrayList<>();
    private OptionalDouble ratePerSecond = OptionalDouble.empty();
    private int suppressedForNotSending;

    FakeCampaignMessagePort withBody(String locale, String body) {
        bodies.put(locale, body);
        return this;
    }

    /**
     * The wording of one template, which wins over {@link #withBody} for that key. A scenario's
     * steps carry their own templates, and a test that prices them has to be able to give them
     * different lengths.
     */
    FakeCampaignMessagePort withTemplateBody(String templateKey, String locale, String body) {
        bodiesByTemplate
                .computeIfAbsent(templateKey, key -> new LinkedHashMap<>())
                .put(locale, body);
        return this;
    }

    /** What {@link #countSuppressedForNotSending} answers from now on, regardless of {@code since}. */
    FakeCampaignMessagePort withSuppressedForNotSending(int count) {
        suppressedForNotSending = count;
        return this;
    }

    FakeCampaignMessagePort withRatePerSecond(double rate) {
        ratePerSecond = OptionalDouble.of(rate);
        return this;
    }

    @Override
    public UUID enqueue(MarketingMessage message) {
        sent.add(message);
        return byIdempotencyKey.computeIfAbsent(message.idempotencyKey(), key -> UUID.randomUUID());
    }

    @Override
    public Map<String, String> templateBodies(UUID tenantId, UUID brandId, String templateKey, String channel) {
        return Map.copyOf(bodiesByTemplate.getOrDefault(templateKey, bodies));
    }

    @Override
    public Wiring wiring(UUID tenantId, UUID brandId, String channel, String purpose) {
        wiringAsked.add(channel + "/" + purpose);
        if (!wired) {
            return Wiring.no(notWiredReason);
        }
        // A purpose the fake has been told this brand's account is not cleared for
        // is refused with the same stable code production answers with.
        return refusedPurposes.contains(purpose) ? Wiring.no("SMS_PURPOSE_NOT_PERMITTED") : Wiring.yes();
    }

    @Override
    public OptionalDouble campaignRatePerSecond(String channel) {
        return ratePerSecond;
    }

    @Override
    public int countSuppressedForNotSending(UUID tenantId, UUID campaignId, Instant since) {
        return suppressedForNotSending;
    }

    void unwire() {
        wired = false;
    }

    /** The brand's account is bound but not cleared for this purpose. */
    FakeCampaignMessagePort refusingPurpose(String purpose) {
        refusedPurposes.add(purpose);
        return this;
    }

    /** Every {@code channel/purpose} wiring was asked about, so a test can prove which question a caller put. */
    List<String> wiringAsked() {
        return List.copyOf(wiringAsked);
    }

    List<MarketingMessage> sent() {
        return List.copyOf(sent);
    }

    /** How many distinct messages exist, as opposed to how many calls were made. */
    int distinctMessages() {
        return byIdempotencyKey.size();
    }
}
