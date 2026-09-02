package uz.horecaos.platform.ordering.api;

import java.util.List;
import java.util.Map;

/**
 * The curated reject-reason list, for a caller outside {@code ordering}
 * (wave 24, V0119).
 *
 * <p>Exists for exactly one consumer today — {@code TelegramUpdateHandler} in
 * {@code integration}, building the follow-up keyboard a Reject tap presents —
 * mirroring how {@link OrderDecisionPort} exists so that same package can
 * drive a decision without importing an {@code ordering.application} type
 * Spring Modulith's {@code verify()} would refuse.
 */
public interface RejectReasonDirectory {

    /**
     * The reasons a picker may offer, in display order.
     *
     * <p>{@code OTHER} is deliberately excluded: it needs a note, and the bot
     * has no free-text follow-up wired into this flow (a deliberate wave-24
     * scope line, not an oversight — a reject that needs explaining is
     * finished in Operations). Every other active reason is included.
     */
    List<Option> topOptions();

    /** @param labelsByLocale the reason's label, keyed by {@code ru}/{@code uz-Latn}/{@code en} */
    record Option(String code, Map<String, String> labelsByLocale) {}
}
