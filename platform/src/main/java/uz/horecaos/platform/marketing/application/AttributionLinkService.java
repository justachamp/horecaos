package uz.horecaos.platform.marketing.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAttributionLinkStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAttributionLinkStore.AttributionLinkRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Minting a trackable acquisition link (ADR 0044 "Attribution and
 * referrals", operations §6.6a) — a website {@code ?ref=} link or a
 * Telegram {@code startapp} deep link for a campaign or an influencer.
 *
 * <p>What this class does not do, on purpose: it never records which account
 * or order a link brought. That half of the mechanism needs columns on a
 * customer account and an order — other modules' tables, per V0309's own
 * doc — and is follow-on integration work for whichever surface actually
 * serves a {@code ?ref=} redirect or a Telegram deep link. What exists here
 * is the half marketing owns outright: mint the link, and count the clicks
 * it actually receives.
 */
@Service
public class AttributionLinkService {

    private static final Set<String> CHANNELS = Set.of("WEB", "TELEGRAM_BOT", "TELEGRAM_MINI_APP", "MOBILE_APP");
    private static final Set<String> DESTINATION_TYPES = Set.of("CAMPAIGN", "STOREFRONT_HOME", "INFLUENCER");
    private static final String TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int TOKEN_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcAttributionLinkStore store;
    private final Clock clock;

    public AttributionLinkService(JdbcAttributionLinkStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public UUID mint(
            UUID tenantId,
            UUID brandId,
            String label,
            @Nullable String ownerNote,
            String channel,
            String destinationType,
            @Nullable UUID destinationId,
            @Nullable Instant validUntil,
            UUID createdBy) {

        if (label == null || label.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "label must not be blank");
        }
        if (!CHANNELS.contains(channel)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "channel must be one of " + CHANNELS);
        }
        if (!DESTINATION_TYPES.contains(destinationType)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "destinationType must be one of " + DESTINATION_TYPES);
        }
        if ("CAMPAIGN".equals(destinationType) != (destinationId != null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A CAMPAIGN destination names a destinationId; STOREFRONT_HOME/INFLUENCER carry none");
        }

        Instant now = clock.instant();
        if (validUntil != null && !validUntil.isAfter(now)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "validUntil must be in the future");
        }

        UUID id = Ids.newId();
        String token = newToken(tenantId);
        store.insert(
                id,
                tenantId,
                brandId,
                label,
                token,
                ownerNote,
                channel,
                destinationType,
                destinationId,
                now,
                validUntil,
                createdBy,
                now);
        return id;
    }

    @Transactional
    public void archive(UUID tenantId, UUID id) {
        if (!store.archive(tenantId, id, clock.instant())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This link is already archived, or does not exist");
        }
    }

    @Transactional
    public void recordClick(UUID tenantId, UUID id) {
        if (!store.recordClick(tenantId, id, clock.instant())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND, "No attribution link %s belongs to this tenant".formatted(id));
        }
    }

    @Transactional(readOnly = true)
    public List<AttributionLinkRow> list(UUID tenantId, UUID brandId) {
        return store.listByBrand(tenantId, brandId);
    }

    public AttributionLinkRow require(UUID tenantId, UUID id) {
        return store.find(tenantId, id)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No attribution link %s belongs to this tenant".formatted(id)));
    }

    /**
     * A short, URL-safe, unguessable token — never an encoded struct, per
     * ADR 0044's own note that a Telegram {@code start} deep link payload is
     * limited to 64 URL-safe characters. Retried on the vanishingly unlikely
     * collision rather than failing the mint outright.
     */
    private String newToken(UUID tenantId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder token = new StringBuilder(TOKEN_LENGTH);
            for (int i = 0; i < TOKEN_LENGTH; i++) {
                token.append(TOKEN_ALPHABET.charAt(RANDOM.nextInt(TOKEN_ALPHABET.length())));
            }
            String candidate = token.toString();
            if (!store.tokenTaken(tenantId, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not mint a unique attribution token after 5 attempts");
    }
}
