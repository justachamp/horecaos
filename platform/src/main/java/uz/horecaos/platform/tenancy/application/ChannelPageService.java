package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageLocale;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageSlug;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageVersion;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPageVersionSummary;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcChannelPageStore;

/**
 * Authoring a channel's static pages — row 10.5's "about, contacts, delivery
 * terms, privacy/offer" — copying {@code legal.application.TermsPublishingService}'s
 * own shape and its own reasoning for why a version is never edited, only
 * superseded: a customer may be reading a page at the moment it is
 * republished, and rewriting the words under a URL already shown to them
 * would make that render evidence of nothing.
 */
@Service
public class ChannelPageService {

    /** Generous, not arbitrary: this is "markdown-ish" plain text (see V0404's own doc), not an upload. */
    private static final int MAXIMUM_BODY_LENGTH = 50_000;

    private final JdbcChannelPageStore store;
    private final SalesChannelService channels;
    private final Clock clock;

    public ChannelPageService(JdbcChannelPageStore store, SalesChannelService channels, Clock clock) {
        this.store = store;
        this.channels = channels;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<ChannelPageVersion> current(UUID tenantId, UUID channelId, ChannelPageSlug slug) {
        channels.require(tenantId, channelId);
        return store.current(tenantId, channelId, slug);
    }

    @Transactional(readOnly = true)
    public Optional<ChannelPageVersion> version(UUID tenantId, UUID channelId, ChannelPageSlug slug, int version) {
        channels.require(tenantId, channelId);
        return store.version(tenantId, channelId, slug, version);
    }

    /** Every slug's publishing history for this channel, newest first per slug. */
    @Transactional(readOnly = true)
    public List<ChannelPageVersionSummary> history(UUID tenantId, UUID channelId) {
        channels.require(tenantId, channelId);
        return store.history(tenantId, channelId);
    }

    /**
     * Publishes the next version of one page.
     *
     * @param contentsByLocale keyed by {@link ChannelPageLocale#tag()}; a
     *                         tenant may author fewer than all three
     *                         languages but must author at least one
     */
    @Transactional
    public ChannelPageVersion publish(
            UUID tenantId,
            UUID channelId,
            ChannelPageSlug slug,
            Map<String, String> contentsByLocale,
            String publishedBy) {

        channels.require(tenantId, channelId);
        Map<String, String> normalized = normalize(contentsByLocale);
        Instant now = clock.instant();
        int version = store.nextVersion(tenantId, channelId, slug);
        UUID id = UUID.randomUUID();

        try {
            store.insert(id, tenantId, channelId, slug, version, publishedBy, now, normalized);
        } catch (DataIntegrityViolationException concurrentPublish) {
            // uq_channel_page_version. Two operators publishing the same page
            // at once would otherwise silently produce a version whose number
            // the other one also holds.
            throw new TenantResourceConflictException(
                    "Another version of this page was published concurrently; re-read and retry");
        }
        return new ChannelPageVersion(id, tenantId, channelId, slug, version, normalized, publishedBy, now);
    }

    /** Trims, drops blanks, rejects an unknown locale or an oversized body, and requires at least one entry. */
    private Map<String, String> normalize(@Nullable Map<String, String> contentsByLocale) {
        if (contentsByLocale == null || contentsByLocale.isEmpty()) {
            throw new IllegalArgumentException("Publishing requires text for at least one language");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : contentsByLocale.entrySet()) {
            ChannelPageLocale locale = ChannelPageLocale.parse(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("\"" + entry.getKey()
                            + "\" is not one of the supported locales " + ChannelPageLocale.tags()));
            String body = entry.getValue() == null ? "" : entry.getValue().strip();
            if (body.isEmpty()) {
                // An operator clearing a field drops that language from this
                // version rather than publishing an empty page in it.
                continue;
            }
            if (body.length() > MAXIMUM_BODY_LENGTH) {
                throw new IllegalArgumentException(
                        locale.tag() + " text exceeds the maximum length of " + MAXIMUM_BODY_LENGTH + " characters");
            }
            normalized.put(locale.tag(), body);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Publishing requires text for at least one language");
        }
        return normalized;
    }
}
