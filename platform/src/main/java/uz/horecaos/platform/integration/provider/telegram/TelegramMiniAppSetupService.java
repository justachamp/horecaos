package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Tier;
import uz.horecaos.platform.tenancy.api.PlatformLocales;
import uz.horecaos.platform.tenancy.api.StorefrontHostnames;
import uz.horecaos.platform.tenancy.api.TelegramChannelSettings;
import uz.horecaos.platform.tenancy.api.TelegramChannelSettingsDirectory;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The guided Mini App and BotFather setup (ADR 0183, row 6.6a; IA 6.6 «Mini-App / BotFather setup as a guided resumable
 * flow»).
 *
 * <p><strong>Resumable because nothing is remembered.</strong> There is no progress table. Every step's state is derived on
 * each read from a fact that exists anyway: the bot installation and its username, the webhook secret, the Telegram channel
 * and its settings, the brand's verified storefront hostname, and, for the menu button, what Telegram itself says the bot's
 * button is. Closing the page and coming back, or changing the hostname in another screen, cannot leave the flow believing
 * something that is no longer true.
 *
 * <p><strong>Only a human holding @BotFather can do two steps.</strong> {@code /newbot} and {@code /newapp} have no API, so
 * the flow can show the exact text to send and record the short name BotFather answers with, and cannot check it: that step
 * reads «recorded», never «verified», and the console says so. Everything after it is proved against Telegram: the menu button
 * is read back with {@code getChatMenuButton} and compared with what the platform means it to be.
 *
 * <p>Applying the menu button is an external call and so runs in no transaction (the same rule as
 * {@code TelegramWebhookRegistrationService}); the audit fact is written afterwards, in its own, and only when the read-back
 * agrees.
 */
@Service
public class TelegramMiniAppSetupService {

    private static final Logger log = LoggerFactory.getLogger(TelegramMiniAppSetupService.class);

    /** The state of a step, derived and never stored. */
    public enum State {
        DONE,
        TODO,
        /** A fact that lives at Telegram could not be read just now, so the step is neither done nor known to be undone. */
        UNKNOWN
    }

    public record Step(String code, State state, @Nullable String detail) {}

    /**
     * Everything the console needs to draw the flow: the steps and the facts it prints into the BotFather instructions.
     *
     * @param menuButtonLabel the label the platform would set (the channel's label in the brand's default language, else
     *                        the platform's word); the button actually shown may differ until it is applied
     * @param testLink {@code https://t.me/<bot>/<short name>}, Telegram's direct link to the Mini App, once both are known
     */
    public record Status(
            List<Step> steps,
            @Nullable String botUsername,
            @Nullable String miniAppUrl,
            @Nullable String miniAppShortName,
            @Nullable UUID channelId,
            @Nullable Integer channelVersion,
            @Nullable String menuButtonLabel,
            @Nullable String menuButtonUrl,
            @Nullable String testLink) {}

    private final TelegramBotIdentityResolver identities;
    private final TelegramChannelSettingsDirectory settings;
    private final StorefrontHostnames hostnames;
    private final BrandLocaleLookup brandLocales;
    private final TelegramBotApiClient bots;
    private final SecretResolver secrets;
    private final JdbcClient jdbc;
    private final AuditRecorder audit;
    private final TransactionTemplate unitOfWork;
    private final Clock clock;

    public TelegramMiniAppSetupService(
            TelegramBotIdentityResolver identities,
            TelegramChannelSettingsDirectory settings,
            StorefrontHostnames hostnames,
            BrandLocaleLookup brandLocales,
            TelegramBotApiClient bots,
            SecretResolver secrets,
            JdbcClient jdbc,
            AuditRecorder audit,
            TransactionTemplate unitOfWork,
            Clock clock) {
        this.identities = identities;
        this.settings = settings;
        this.hostnames = hostnames;
        this.brandLocales = brandLocales;
        this.bots = bots;
        this.secrets = secrets;
        this.jdbc = jdbc;
        this.audit = audit;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    /** The flow as it stands now, derived from the facts. */
    public Status status(UUID tenantId, UUID brandId) {
        return new Derivation(tenantId, brandId).derive();
    }

    /**
     * Sets the bot's menu button to open the brand's storefront, then reads it back from Telegram. The returned status says
     * whether Telegram now shows what was set; a Telegram that accepts the call and shows something else leaves the step
     * undone rather than claiming it.
     *
     * @throws ApiException {@code UNPROCESSABLE_STATE} naming what is missing when a precondition is not met, or when
     *                      Telegram refuses or cannot be reached
     */
    public Status applyMenuButton(UUID tenantId, UUID brandId, ActorRef actor) {
        Derivation facts = new Derivation(tenantId, brandId);
        facts.requireReadyToApply();

        TelegramMenuButton.WebApp wanted = facts.wantedButton();
        TelegramCallResult set = bots.setChatMenuButton(facts.call(), null, wanted);
        if (!(set instanceof TelegramCallResult.Success)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Telegram did not accept the menu button (" + describe(set) + "); nothing was changed here");
        }

        Status after = facts.derive();
        boolean readBackAgrees = after.steps().stream()
                .anyMatch(step -> step.code().equals("MENU_BUTTON") && step.state() == State.DONE);
        if (readBackAgrees) {
            recordApplied(tenantId, brandId, actor, wanted, facts.previousButtonUrl());
        } else {
            log.warn("Telegram accepted a menu button for tenant {} and then did not show it", tenantId);
        }
        return after;
    }

    private void recordApplied(
            UUID tenantId,
            UUID brandId,
            ActorRef actor,
            TelegramMenuButton.WebApp wanted,
            @Nullable String previousUrl) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("menuButtonUrl", previousUrl);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("menuButtonUrl", wanted.url());
        after.put("menuButtonLabel", wanted.text());
        unitOfWork.executeWithoutResult(
                ignored -> audit.record(AuditFact.of("integration.telegram_menu_button_applied", AuditClass.BUSINESS)
                        .by(actor)
                        .at(ResourceScope.brand(tenantId, brandId))
                        .target("Integration", brandId)
                        .because("Telegram menu button set to open the storefront")
                        .changed(ChangeDocuments.diff(before, after))
                        .usingCapability(Capability.INTEGRATION_INSTALLATION_MANAGE.code())
                        .correlatedBy(brandId.toString())
                        .occurredAt(clock.instant())
                        .build()));
    }

    private static String describe(TelegramCallResult result) {
        return switch (result) {
            case TelegramCallResult.Success ignored -> "ok";
            case TelegramCallResult.Retryable retryable -> retryable.errorCode() + ": " + retryable.detail();
            case TelegramCallResult.Uncertain uncertain -> uncertain.errorCode() + ": " + uncertain.detail();
            case TelegramCallResult.BusinessRejected rejected -> rejected.errorCode() + ": " + rejected.detail();
            case TelegramCallResult.BindingRetirement retirement -> retirement.reason() + ": " + retirement.detail();
            case TelegramCallResult.ChatMigrated ignored -> "unexpected chat migration answer";
        };
    }

    /** One read of every fact the flow stands on. Cheap to build, and cheap to throw away. */
    private final class Derivation {

        private final UUID tenantId;
        private final UUID brandId;

        private Derivation(UUID tenantId, UUID brandId) {
            this.tenantId = tenantId;
            this.brandId = brandId;
        }

        private TelegramBotIdentityResolver.@Nullable Installation installation;
        private @Nullable String username;
        private boolean usernameUnreachable;
        private @Nullable Boolean webhookRegistered;
        private @Nullable TelegramChannelSettings channel;
        private StorefrontHostnames.@Nullable Resolution storefront;
        private @Nullable TelegramMenuButton heldButton;
        private boolean heldButtonUnreadable;

        Status derive() {
            installation = identities.activeInstallation(tenantId, brandId).orElse(null);
            username = null;
            usernameUnreachable = false;
            webhookRegistered = null;
            channel = null;
            if (installation != null) {
                try {
                    username =
                            identities.resolveUsername(tenantId, installation).orElse(null);
                } catch (RuntimeException failure) {
                    log.warn(
                            "Could not resolve a Telegram bot's username: {}",
                            failure.getClass().getSimpleName());
                }
                usernameUnreachable = username == null;
                webhookRegistered = jdbc.sql("""
                                SELECT webhook_secret_reference IS NOT NULL
                                  FROM integration.installations WHERE tenant_id = :tenantId AND id = :id
                                """)
                        .param("tenantId", tenantId)
                        .param("id", installation.id())
                        .query(Boolean.class)
                        .optional()
                        .orElse(false);
                channel = settings.forInstallation(tenantId, installation.id()).orElse(null);
            }
            storefront = hostnames.forBrand(tenantId, brandId);
            readHeldButton();

            List<Step> steps = new ArrayList<>();
            steps.add(connectBot());
            steps.add(new Step(
                    "REGISTER_WEBHOOK", Boolean.TRUE.equals(webhookRegistered) ? State.DONE : State.TODO, null));
            steps.add(new Step("TELEGRAM_CHANNEL", channel != null ? State.DONE : State.TODO, null));
            steps.add(storefrontStep());
            steps.add(new Step(
                    "BOTFATHER_APP",
                    channel != null && channel.miniAppShortName() != null ? State.DONE : State.TODO,
                    null));
            steps.add(menuButtonStep());
            String url = expectedUrl();
            String shortName = channel == null ? null : channel.miniAppShortName();
            String testLink =
                    username != null && shortName != null ? "https://t.me/" + username + "/" + shortName : null;
            // Informational: only the owner of a phone can open it, so the flow never claims this one is done.
            steps.add(new Step("TEST_LINK", State.TODO, testLink == null ? "NOT_READY" : null));
            return new Status(
                    steps,
                    username,
                    url,
                    shortName,
                    channel == null ? null : channel.channelId(),
                    channel == null ? null : channel.channelVersion(),
                    channel == null ? null : wantedLabel(),
                    heldButton == null ? null : heldButton.webAppUrl().orElse(null),
                    testLink);
        }

        private Step connectBot() {
            if (installation == null) {
                return new Step("CONNECT_BOT", State.TODO, null);
            }
            return new Step("CONNECT_BOT", usernameUnreachable ? State.UNKNOWN : State.DONE, null);
        }

        private Step storefrontStep() {
            StorefrontHostnames.Reason reason = storefront == null ? null : storefront.reason();
            return reason == null
                    ? new Step("STOREFRONT_ADDRESS", State.DONE, null)
                    : new Step("STOREFRONT_ADDRESS", State.TODO, reason.name());
        }

        private Step menuButtonStep() {
            if (installation == null || channel == null || !channel.miniAppEnabled() || expectedUrl() == null) {
                return new Step(
                        "MENU_BUTTON",
                        State.TODO,
                        channel != null && !channel.miniAppEnabled() ? "MINI_APP_OFF" : "NOT_READY");
            }
            if (!brandId.equals(channel.brandId())) {
                // The Telegram channel's Mini App opens another brand's storefront; this brand's setup does not own it.
                return new Step("MENU_BUTTON", State.TODO, "OTHER_BRAND");
            }
            if (heldButtonUnreadable) {
                return new Step("MENU_BUTTON", State.UNKNOWN, null);
            }
            TelegramMenuButton held = heldButton;
            boolean agrees = held instanceof TelegramMenuButton.WebApp webApp
                    && webApp.url().equals(expectedUrl())
                    && webApp.text().equals(wantedLabel());
            return new Step("MENU_BUTTON", agrees ? State.DONE : State.TODO, agrees ? null : "NOT_APPLIED");
        }

        private void readHeldButton() {
            heldButton = null;
            heldButtonUnreadable = false;
            if (installation == null || usernameUnreachable) {
                return;
            }
            try {
                TelegramCallResult result = bots.getChatMenuButton(call(), null);
                if (result instanceof TelegramCallResult.Success success) {
                    heldButton = TelegramMenuButton.fromApiShape(success.result());
                } else {
                    heldButtonUnreadable = true;
                }
            } catch (RuntimeException failure) {
                heldButtonUnreadable = true;
            }
        }

        @Nullable
        String previousButtonUrl() {
            return heldButton == null ? null : heldButton.webAppUrl().orElse(null);
        }

        private @Nullable String expectedUrl() {
            return storefront != null && storefront.resolved() ? storefront.origin() + "/" : null;
        }

        private String wantedLabel() {
            String brandDefault = brandLocales
                    .brandDefaultLocale(tenantId, brandId)
                    .orElse(PlatformLocales.fallback().tag());
            String resolved = PlatformLocales.resolve(brandDefault, Tier.MESSAGES);
            TelegramChannelSettings current = channel;
            return current == null
                    ? TelegramBotMessages.menuButtonDefault(resolved)
                    : current.menuButtonLabel(resolved, "ru", "en", "uz-Latn")
                            .orElseGet(() -> TelegramBotMessages.menuButtonDefault(resolved));
        }

        TelegramMenuButton.WebApp wantedButton() {
            return new TelegramMenuButton.WebApp(
                    wantedLabel(), Optional.ofNullable(expectedUrl()).orElseThrow());
        }

        ProviderCall call() {
            TelegramBotIdentityResolver.Installation current = installation;
            if (current == null) {
                throw new IllegalStateException("No installation resolved");
            }
            return new ProviderCall(
                    current.baseUrl(),
                    secrets.resolve(SecretReference.parse(current.secretReference()))
                            .reveal(),
                    null,
                    Duration.ofSeconds(15));
        }

        /** Refuses with the first missing precondition named, in the order the console lists the steps. */
        void requireReadyToApply() {
            derive();
            if (installation == null) {
                throw refusal("The brand has no connected Telegram bot");
            }
            if (usernameUnreachable) {
                throw refusal("Telegram would not answer for this bot just now; try again shortly");
            }
            if (channel == null) {
                throw refusal("No Telegram sales channel is backed by this bot yet");
            }
            if (!channel.miniAppEnabled()) {
                throw refusal("The Mini App is switched off on the Telegram channel");
            }
            if (!brandId.equals(channel.brandId())) {
                throw refusal("The Telegram channel's Mini App opens another brand's storefront");
            }
            if (expectedUrl() == null) {
                throw refusal("The brand has no verified storefront address (" + storefrontReason() + ")");
            }
        }

        private String storefrontReason() {
            StorefrontHostnames.Reason reason = storefront == null ? null : storefront.reason();
            return reason == null ? "unknown" : reason.name();
        }

        private ApiException refusal(String detail) {
            return new ApiException(ErrorCode.UNPROCESSABLE_STATE, detail);
        }
    }
}
