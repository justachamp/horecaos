package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.BrandContentFacts;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Tier;
import uz.horecaos.platform.tenancy.api.PlatformLocales;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.api.StorefrontHostnames;
import uz.horecaos.platform.tenancy.api.TelegramChannelSettings;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTelegramChannelSettingsStore;

/**
 * A TELEGRAM channel's own settings (ADR 0183 D27; {@code settings.md} 10.5 TELEGRAM): the menu button label and the
 * welcome text per language, whether the menu button opens the Mini App and for which brand, the BotFather short name,
 * and whether the tenant's terms page is offered as the public offer.
 *
 * <p><strong>No URL is typed.</strong> The Mini App address and the public-offer address are derived from the brand's
 * verified storefront hostname ({@link StorefrontHostnames}), so a tenant supplies nothing the platform would have to
 * vouch for (the trust decision ADR 0075 named). {@link #view} returns them, or the reason there is none.
 *
 * <p><strong>Pushing the menu button to Telegram is not done here.</strong> {@code tenancy} takes no dependency on the
 * integration module; the Bot API call is {@code TelegramMiniAppSetupService}'s, which reads these settings through
 * {@code TelegramChannelSettingsDirectory}. Saving a setting therefore never calls Telegram and never fails because
 * Telegram is unreachable.
 *
 * <p>Every write is audited in the transaction that made it ({@code channel.telegram.updated}), gated on the channel's
 * own version like every other facet of Channel setup.
 */
@Service
public class TelegramChannelSettingsService {

    static final int MAX_MENU_BUTTON_LABEL = 64;
    static final int MAX_WELCOME_TEXT = 1000;

    /** Telegram's own alphabet for a Mini App short name, and the V0674 check. */
    private static final Pattern SHORT_NAME = Pattern.compile("^[A-Za-z0-9_]{3,64}$");

    private final JdbcTelegramChannelSettingsStore store;
    private final SalesChannelService channels;
    private final StorefrontHostnames hostnames;
    private final BrandContentFacts brands;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;

    public TelegramChannelSettingsService(
            JdbcTelegramChannelSettingsStore store,
            SalesChannelService channels,
            StorefrontHostnames hostnames,
            BrandContentFacts brands,
            AuditRecorder audit,
            CurrentActor currentActor,
            Clock clock) {
        this.store = store;
        this.channels = channels;
        this.hostnames = hostnames;
        this.brands = brands;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    /** The settings plus the addresses derived from them. */
    public record View(
            TelegramChannelSettings settings,
            @Nullable String miniAppUrl,
            @Nullable String miniAppUnavailable,
            @Nullable String publicOfferUrl) {}

    /**
     * What a caller may set. Everything is replaced: an omitted language drops its text, an omitted flag is false.
     *
     * @param menuButtonLabels language tag (or input alias such as {@code uz}) to label
     * @param welcomeTexts language tag (or input alias) to welcome text
     */
    public record Command(
            @Nullable UUID brandId,
            boolean miniAppEnabled,
            @Nullable String miniAppShortName,
            boolean publicOfferLink,
            Map<String, String> menuButtonLabels,
            Map<String, String> welcomeTexts) {}

    @Transactional(readOnly = true)
    public View view(UUID tenantId, UUID channelId) {
        requireTelegramChannel(tenantId, channelId);
        TelegramChannelSettings settings = store.forChannel(tenantId, channelId)
                .orElseThrow(() -> new TenantResourceNotFoundException("No Telegram channel " + channelId));
        return derive(settings);
    }

    @Transactional
    public View update(UUID tenantId, UUID channelId, Command command, int expectedVersion) {
        SalesChannel channel = requireTelegramChannel(tenantId, channelId);
        TelegramChannelSettings before = store.forChannel(tenantId, channelId)
                .orElseThrow(() -> new TenantResourceNotFoundException("No Telegram channel " + channelId));

        String shortName = normalizedShortName(command.miniAppShortName());
        if (command.brandId() != null && !brands.brandExists(tenantId, command.brandId())) {
            throw new IllegalArgumentException("That brand does not belong to this tenant");
        }
        if (command.miniAppEnabled() && command.brandId() == null) {
            throw new IllegalArgumentException("Choose the brand whose storefront the Mini App opens");
        }
        Map<String, String> labels = normalizedTexts(command.menuButtonLabels(), MAX_MENU_BUTTON_LABEL, false);
        Map<String, String> welcomes = normalizedTexts(command.welcomeTexts(), MAX_WELCOME_TEXT, true);

        if (!store.replace(
                tenantId,
                channelId,
                command.brandId(),
                command.miniAppEnabled(),
                shortName,
                command.publicOfferLink(),
                labels,
                welcomes,
                expectedVersion,
                clock.instant())) {
            throw new TenantResourceConflictException("The channel changed since it was read");
        }

        TelegramChannelSettings after = new TelegramChannelSettings(
                tenantId,
                channelId,
                command.brandId(),
                command.miniAppEnabled(),
                shortName,
                command.publicOfferLink(),
                labels,
                welcomes,
                expectedVersion + 1);
        audit.record(AuditFact.of("channel.telegram.updated", AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("tenancy.sales-channel", channelId)
                .targetVersion((long) (expectedVersion + 1))
                .because("Telegram channel settings edited")
                .changed(ChangeDocuments.diff(snapshot(channel, before), snapshot(channel, after)))
                .correlatedBy(channelId.toString())
                .occurredAt(clock.instant())
                .build());
        return derive(after);
    }

    private View derive(TelegramChannelSettings settings) {
        if (settings.brandId() == null) {
            return new View(settings, null, "BRAND_NOT_CHOSEN", null);
        }
        StorefrontHostnames.Resolution storefront = hostnames.forBrand(settings.tenantId(), settings.brandId());
        StorefrontHostnames.Reason missing = storefront.reason();
        if (missing != null) {
            return new View(settings, null, "HOSTNAME_" + missing.name(), null);
        }
        String offer = settings.publicOfferLink() ? storefront.origin() + "/terms" : null;
        return new View(settings, storefront.origin() + "/", null, offer);
    }

    private SalesChannel requireTelegramChannel(UUID tenantId, UUID channelId) {
        SalesChannel channel = channels.require(tenantId, channelId);
        if (channel.systemType() != SalesChannelSystemType.TELEGRAM) {
            throw new IllegalArgumentException("Telegram settings belong to a TELEGRAM channel, and this one is "
                    + channel.systemType().name());
        }
        return channel;
    }

    private static @Nullable String normalizedShortName(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.strip();
        if (!SHORT_NAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "The Mini App short name is 3 to 64 letters, digits and underscores, as BotFather gave it");
        }
        return trimmed;
    }

    /**
     * One text per registered language: the key is read as a tag or an input alias ({@code uz}), stored as the tag, and a
     * language the messages tier does not speak is refused rather than quietly stored for nobody to read. A blank text
     * means "no text in this language" and is dropped.
     */
    private static Map<String, String> normalizedTexts(Map<String, String> raw, int maxLength, boolean multiline) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String tag = PlatformLocales.parseActive(entry.getKey(), Tier.MESSAGES)
                    .map(locale -> locale.tag())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "\"%s\" is not a language this platform sends messages in".formatted(entry.getKey())));
            String text = entry.getValue() == null ? "" : entry.getValue().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > maxLength) {
                throw new IllegalArgumentException(
                        "A text in %s is longer than %d characters".formatted(tag, maxLength));
            }
            boolean hasControl = text.codePoints()
                    .anyMatch(codePoint -> Character.isISOControl(codePoint)
                            && !(multiline && (codePoint == '\n' || codePoint == '\r')));
            if (hasControl) {
                throw new IllegalArgumentException("A text in %s holds a control character".formatted(tag));
            }
            if (normalized.putIfAbsent(tag, text) != null) {
                throw new IllegalArgumentException("Two texts were given for %s".formatted(tag));
            }
        }
        return normalized;
    }

    private static Map<String, Object> snapshot(SalesChannel channel, TelegramChannelSettings settings) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", channel.code());
        snapshot.put(
                "brandId",
                settings.brandId() == null ? null : settings.brandId().toString());
        snapshot.put("miniAppEnabled", settings.miniAppEnabled());
        snapshot.put("miniAppShortName", settings.miniAppShortName());
        snapshot.put("publicOfferLink", settings.publicOfferLink());
        snapshot.put("menuButtonLabels", new java.util.TreeMap<>(settings.menuButtonLabels()));
        snapshot.put("welcomeTexts", new java.util.TreeMap<>(settings.welcomeTexts()));
        return snapshot;
    }
}
