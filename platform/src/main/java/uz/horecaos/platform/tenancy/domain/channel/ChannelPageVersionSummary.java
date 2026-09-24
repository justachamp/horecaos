package uz.horecaos.platform.tenancy.domain.channel;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A publishing-history row, without bodies — mirrors {@code legal.domain.TermsVersionSummary}. */
public record ChannelPageVersionSummary(
        UUID id, ChannelPageSlug slug, int version, Set<String> locales, String publishedBy, Instant publishedAt) {}
