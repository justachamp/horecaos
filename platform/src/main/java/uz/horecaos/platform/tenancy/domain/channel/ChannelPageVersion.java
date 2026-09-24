package uz.horecaos.platform.tenancy.domain.channel;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One published version of one channel's static page (migration V0404).
 *
 * <p>Immutable once it exists, mirroring {@code legal.domain.TermsVersion}
 * field for field and reason for reason: publishing never touches this row,
 * it inserts the next {@code version}.
 *
 * @param contentsByLocale keyed by {@link ChannelPageLocale#tag()}; a tenant
 *                         may publish fewer than all three languages, and a
 *                         locale absent here has no translation — the
 *                         storefront answers "not available in this
 *                         language" for it, never another language's text.
 */
public record ChannelPageVersion(
        UUID id,
        UUID tenantId,
        UUID channelId,
        ChannelPageSlug slug,
        int version,
        Map<String, String> contentsByLocale,
        String publishedBy,
        Instant publishedAt) {

    public ChannelPageVersion {
        contentsByLocale = Map.copyOf(contentsByLocale);
    }
}
