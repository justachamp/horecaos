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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.PlatformLocale;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Tier;
import uz.horecaos.platform.tenancy.api.PlatformLocales;
import uz.horecaos.platform.tenancy.api.SalesChannel;
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
 *
 * <p>Every publication leaves a {@code channel.page.published} audit fact (ADR 0027, staff
 * row {@code 9.3a}) in its transaction: which page, which version, which languages and how
 * long each text is. The text itself stays out of the history -- a page is up to fifty
 * thousand characters of customer-facing copy that its own version table already keeps,
 * addressable by the version number the fact carries, and an audit row is the wrong place
 * for a second copy of it.
 */
@Service
public class ChannelPageService {

    /** Generous, not arbitrary: this is "markdown-ish" plain text (see V0404's own doc), not an upload. */
    private static final int MAXIMUM_BODY_LENGTH = 50_000;

    private final JdbcChannelPageStore store;
    private final SalesChannelService channels;
    private final AuditRecorder audit;
    private final Clock clock;

    public ChannelPageService(
            JdbcChannelPageStore store, SalesChannelService channels, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.channels = channels;
        this.audit = audit;
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
     * @param contentsByLocale keyed by {@link PlatformLocale#tag()}; a
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

        SalesChannel channel = channels.require(tenantId, channelId);
        Map<String, String> normalized = normalize(contentsByLocale);
        Optional<ChannelPageVersion> previous = store.current(tenantId, channelId, slug);
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
        audit.record(AuditFact.of("channel.page.published", AuditClass.BUSINESS)
                .by(ActorRef.user(publishedBy, null))
                .at(ResourceScope.tenant(tenantId))
                .target("tenancy.channel-page", id)
                .targetVersion((long) version)
                .because("Channel page published")
                .changed(ChangeDocuments.diff(
                        snapshotOf(
                                channel,
                                slug,
                                previous.map(ChannelPageVersion::version).orElse(0),
                                previous.map(ChannelPageVersion::contentsByLocale)
                                        .orElse(Map.of())),
                        snapshotOf(channel, slug, version, normalized)))
                .correlatedBy(id.toString())
                .occurredAt(now)
                .build());
        return new ChannelPageVersion(id, tenantId, channelId, slug, version, normalized, publishedBy, now);
    }

    /** Which page, which version, and how many characters each language carries; never the text. */
    private static Map<String, Object> snapshotOf(
            SalesChannel channel, ChannelPageSlug slug, int version, Map<String, String> contentsByLocale) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", channel.code());
        snapshot.put("page", slug.slug());
        snapshot.put("version", version);
        contentsByLocale.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> snapshot.put(
                        "characters." + entry.getKey(), entry.getValue().length()));
        return snapshot;
    }

    /** Trims, drops blanks, rejects an unknown locale or an oversized body, and requires at least one entry. */
    private Map<String, String> normalize(@Nullable Map<String, String> contentsByLocale) {
        if (contentsByLocale == null || contentsByLocale.isEmpty()) {
            throw new IllegalArgumentException("Publishing requires text for at least one language");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : contentsByLocale.entrySet()) {
            PlatformLocale locale = PlatformLocales.parseActive(entry.getKey(), Tier.CONTENT)
                    .orElseThrow(() -> new IllegalArgumentException("\"" + entry.getKey()
                            + "\" is not one of the supported locales " + PlatformLocales.activeTags(Tier.CONTENT)));
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
