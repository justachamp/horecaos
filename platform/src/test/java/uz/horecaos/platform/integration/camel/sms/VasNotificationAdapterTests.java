package uz.horecaos.platform.integration.camel.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.camel.notification.NotificationChannelAdapter;
import uz.horecaos.platform.integration.camel.notification.NotificationGateway;
import uz.horecaos.platform.integration.camel.notification.ReceiptEvent;
import uz.horecaos.platform.integration.camel.notification.ResolveRequest;
import uz.horecaos.platform.integration.camel.notification.SmsPurposes;
import uz.horecaos.platform.integration.provider.BindingConfigurationLookup;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationTransport.Readiness;
import uz.horecaos.platform.notifications.domain.ContentHashes;

/**
 * ADR 0146: the VAS adapter as a notification adapter, through the real
 * {@link NotificationGateway}, against a fake that remembers every byte it was sent.
 *
 * <p>The cases are the ones the record's Testing section names: a VAS binding sends
 * an order confirmation (the case that answered {@code PROVIDER_ADAPTER_MISMATCH}
 * before), an accepted-then-lost reply is resolved without a second send, a code
 * outside the taxonomy is uncertain, and {@code 13 wrong key} refreshes the secret
 * once and does not loop.
 */
class VasNotificationAdapterTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final UUID BINDING = UUID.randomUUID();
    private static final String NUMBER = "998901112233";
    private static final String TEXT = "Your order A-17 is on its way";

    private final ProviderHttpClient http =
            new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier());
    private final VasSmsGatewayAdapter vas = new VasSmsGatewayAdapter(http);

    private final AtomicInteger freshReads = new AtomicInteger();
    private final AtomicInteger cachedReads = new AtomicInteger();

    @Test
    @DisplayName("a VAS binding sends an order confirmation, and the body is the documented one")
    void aVasBindingSendsATransactionalMessage() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.reply("/send", """
                    {"status":{"code":0,"description":"success"},"id":"5981980","parts":2}""");

            ProviderOutcome outcome =
                    gateway(fake, account("horecaos", "16888")).send(dispatch("TRANSACTIONAL"));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
            assertThat(outcome.externalReference()).isEqualTo("5981980");
            assertThat(outcome.normalized())
                    .containsEntry(NotificationChannelAdapter.SEGMENTS_KEY, "2")
                    .containsEntry(NotificationChannelAdapter.NORMALIZED_STATUS_KEY, "ACCEPTED")
                    .containsEntry(NotificationChannelAdapter.PROVIDER_STATUS_KEY, "CREATED");
            assertThat(fake.callTo("/send").body())
                    .containsEntry("login", "horecaos")
                    .containsEntry("key", "the-key")
                    .containsEntry("sender", "16888")
                    .containsEntry("phone", NUMBER)
                    .containsEntry("text", TEXT)
                    .doesNotContainKey("weight")
                    .doesNotContainKey("seq");
        }
    }

    @Test
    @DisplayName("marketing is refused by default with a stable code, and nothing leaves")
    void marketingIsRefusedUntilTheAccountSaysOtherwise() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            ProviderOutcome outcome =
                    gateway(fake, account("horecaos", "16888")).send(dispatch("MARKETING"));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
            assertThat(outcome.errorCode()).isEqualTo("SMS_PURPOSE_NOT_PERMITTED");
            assertThat(fake.calls())
                    .as("a refused purpose is refused before any request")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("naming the purpose on the installation is the written answer that unblocks it")
    void anInstallationThatNamesMarketingCarriesIt() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.reply("/send", """
                    {"status":{"code":0,"description":"success"},"id":"700","parts":1}""");
            Map<String, String> config = new java.util.LinkedHashMap<>(account("horecaos", "16888"));
            config.put(SmsPurposes.CONFIGURATION_KEY, "TRANSACTIONAL, MARKETING");

            ProviderOutcome outcome = gateway(fake, config).send(dispatch("MARKETING"));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
            assertThat(fake.callsTo("/send")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a misspelt purpose never widens what an account carries")
    void aTypoIsNotAPermission() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            Map<String, String> config = new java.util.LinkedHashMap<>(account("horecaos", "16888"));
            config.put(SmsPurposes.CONFIGURATION_KEY, "TRANSACTIONAL, MARKETNG");

            ProviderOutcome outcome = gateway(fake, config).send(dispatch("MARKETING"));

            assertThat(outcome.errorCode()).isEqualTo("SMS_PURPOSE_NOT_PERMITTED");
            assertThat(fake.calls()).isEmpty();
        }
    }

    @Test
    @DisplayName("a missing sender is a configuration finding, found before the credential is put on a wire")
    void anIncompleteAccountIsRefusedWithoutCalling() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            NotificationGateway gateway = gateway(fake, Map.of("login", "horecaos"));

            ProviderOutcome outcome = gateway.send(dispatch("TRANSACTIONAL"));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
            assertThat(outcome.errorCode()).isEqualTo("SMS_ACCOUNT_MISCONFIGURED");
            assertThat(outcome.detail()).contains("sender").doesNotContain("horecaos");
            assertThat(fake.calls()).isEmpty();
            assertThat(vas.describeAccount(new uz.horecaos.platform.integration.camel.notification.AccountContext(
                                    Map.of("login", "horecaos")))
                            .missingFields())
                    .containsExactly("sender");
        }
    }

    @Test
    @DisplayName("readiness says the same thing a send would, without calling anyone")
    void readinessIsTheSameAnswerAsASend() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            NotificationGateway complete = gateway(fake, account("horecaos", "16888"));
            assertThat(complete.readiness(TENANT, BRAND, "SMS", "TRANSACTIONAL"))
                    .isEqualTo(Readiness.ok());
            assertThat(complete.readiness(TENANT, BRAND, "SMS", "MARKETING").reason())
                    .isEqualTo("SMS_PURPOSE_NOT_PERMITTED");
            assertThat(complete.readiness(TENANT, BRAND, "SMS", "COURIER").reason())
                    .isEqualTo("SMS_PURPOSE_NOT_PERMITTED");
            assertThat(gateway(fake, Map.of("login", "x"))
                            .readiness(TENANT, BRAND, "SMS", "TRANSACTIONAL")
                            .reason())
                    .isEqualTo("SMS_ACCOUNT_MISCONFIGURED");
            assertThat(complete.readiness(TENANT, BRAND, "EMAIL", "TRANSACTIONAL")
                            .reason())
                    .isEqualTo("NO_ADAPTER");
            assertThat(fake.calls()).isEmpty();
        }
    }

    @Test
    @DisplayName("an accepted-then-lost reply is resolved by asking, and the message is never sent twice")
    void aLostReplyIsResolvedWithoutASecondSend() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            // The search finds the message by the hash of what was sent, since the
            // send's reply (and so its id) never arrived.
            fake.reply("/search", """
                    {"status":{"code":0,"description":"success"},
                     "data":[{"id":111,"msg":"someone else's","send_dt":1,"status":4},
                             {"id":5981981,"msg":"%s","send_dt":2,"status":3}]}""".formatted(TEXT));
            NotificationGateway gateway = gateway(fake, account("horecaos", "16888"));

            ProviderOutcome resolved = gateway.resolve(
                    TENANT,
                    BRAND,
                    null,
                    "SMS",
                    new ResolveRequest("attempt-1", null, NUMBER, ContentHashes.of(TEXT), java.time.Instant.now()));

            assertThat(resolved.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
            assertThat(resolved.externalReference()).isEqualTo("5981981");
            assertThat(resolved.normalized())
                    .containsEntry(NotificationChannelAdapter.NORMALIZED_STATUS_KEY, "DISPATCHED");
            assertThat(fake.callsTo("/send")).as("resolving never sends").isZero();
            assertThat(fake.callsTo("/search")).isEqualTo(1);
            assertThat(fake.callTo("/search").body())
                    .containsEntry("phone", NUMBER)
                    .doesNotContainKey("text");
        }
    }

    @Test
    @DisplayName("when the provider's own id is known, that id decides, not the text")
    void aKnownMessageIdIsMatchedByIdentity() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.reply("/search", """
                    {"status":{"code":0,"description":"success"},
                     "data":[{"id":42,"msg":"%s","send_dt":1,"status":3},
                             {"id":43,"msg":"another","send_dt":2,"status":4}]}""".formatted(TEXT));

            ProviderOutcome resolved = gateway(fake, account("horecaos", "16888"))
                    .resolve(
                            TENANT,
                            BRAND,
                            null,
                            "SMS",
                            new ResolveRequest(
                                    "attempt-1", "43", NUMBER, ContentHashes.of(TEXT), java.time.Instant.now()));

            assertThat(resolved.externalReference()).isEqualTo("43");
            assertThat(resolved.normalized())
                    .containsEntry(NotificationChannelAdapter.NORMALIZED_STATUS_KEY, "DELIVERED");
        }
    }

    @Test
    @DisplayName("not finding the message is unknown, never 'not sent' — that answer would license a resend")
    void absenceIsNeverNoRecord() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.reply("/search", """
                    {"status":{"code":0,"description":"success"},"data":[]}""");

            ProviderOutcome resolved = gateway(fake, account("horecaos", "16888"))
                    .resolve(
                            TENANT,
                            BRAND,
                            null,
                            "SMS",
                            new ResolveRequest(
                                    "attempt-1", null, NUMBER, ContentHashes.of(TEXT), java.time.Instant.now()));

            assertThat(resolved.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
            assertThat(resolved.errorCode())
                    .isEqualTo("SMS_SEND_UNCONFIRMED")
                    .isNotEqualTo(NotificationChannelAdapter.NO_RECORD);
        }
    }

    @Test
    @DisplayName("a message found failed or blacklisted is a fact about that message, with the hard bounce flagged")
    void aFoundBlacklistedMessageCarriesTheHardBounce() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.reply("/search", """
                    {"status":{"code":0,"description":"success"},
                     "data":[{"id":9,"msg":"%s","send_dt":1,"status":7}]}""".formatted(TEXT));

            ProviderOutcome resolved = gateway(fake, account("horecaos", "16888"))
                    .resolve(
                            TENANT,
                            BRAND,
                            null,
                            "SMS",
                            new ResolveRequest("attempt-1", "9", NUMBER, null, java.time.Instant.now()));

            assertThat(resolved.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
            assertThat(resolved.normalized())
                    .containsEntry(NotificationChannelAdapter.NORMALIZED_STATUS_KEY, "FAILED")
                    .containsEntry(NotificationChannelAdapter.HARD_BOUNCE_KEY, "true");
            assertThat(fake.callsTo("/send")).isZero();
        }
    }

    @Test
    @DisplayName("a resolve without a destination is unknown, since this provider can only be searched by number")
    void resolvingWithoutANumberIsUnknown() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            ProviderOutcome resolved = gateway(fake, account("horecaos", "16888"))
                    .resolve(
                            TENANT,
                            BRAND,
                            null,
                            "SMS",
                            new ResolveRequest("attempt-1", "9", null, null, java.time.Instant.now()));

            assertThat(resolved.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
            assertThat(fake.calls()).isEmpty();
        }
    }

    @Test
    @DisplayName("a code outside the taxonomy is uncertain on the notification path too, never a success")
    void anUndocumentedCodeIsUncertain() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.reply("/send", """
                    {"status":{"code":99,"description":"???"}}""");

            ProviderOutcome outcome =
                    gateway(fake, account("horecaos", "16888")).send(dispatch("TRANSACTIONAL"));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
            assertThat(outcome.errorCode()).isEqualTo("SMS_RESPONSE_UNREADABLE");
        }
    }

    @Test
    @DisplayName("13 wrong key refreshes the secret once and does not loop")
    void aWrongKeyIsRefreshedOnceThenRefused() throws Exception {
        try (RecordingSmsGateway fake = RecordingSmsGateway.start()) {
            fake.reply("/send", """
                    {"status":{"code":13,"description":"wrong key"}}""");

            ProviderOutcome outcome =
                    gateway(fake, account("horecaos", "16888")).send(dispatch("TRANSACTIONAL"));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
            assertThat(outcome.errorCode()).isEqualTo("PROVIDER_AUTHENTICATION");
            assertThat(freshReads.get()).as("one read past the secret cache").isEqualTo(1);
            assertThat(fake.callsTo("/send"))
                    .as("the refusal came instead of sending, so repeating it once is not a duplicate")
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("every documented receipt code maps onto ADR 0146's table, and 7 raises the hard bounce")
    void theReceiptTableIsTheRecordsTable() {
        assertThat(receipt(0))
                .hasValueSatisfying(e -> assertThat(e.normalizedStatus()).isEqualTo("ACCEPTED"));
        assertThat(receipt(1))
                .hasValueSatisfying(e -> assertThat(e.normalizedStatus()).isEqualTo("DISPATCHED"));
        assertThat(receipt(3))
                .hasValueSatisfying(e -> assertThat(e.normalizedStatus()).isEqualTo("DISPATCHED"));
        assertThat(receipt(4))
                .hasValueSatisfying(e -> assertThat(e.normalizedStatus()).isEqualTo("DELIVERED"));
        assertThat(receipt(2))
                .hasValueSatisfying(e -> assertThat(e.normalizedStatus()).isEqualTo("FAILED"));
        assertThat(receipt(5))
                .hasValueSatisfying(e -> assertThat(e.normalizedStatus()).isEqualTo("FAILED"));
        assertThat(receipt(6))
                .hasValueSatisfying(e -> assertThat(e.normalizedStatus()).isEqualTo("UNKNOWN"));
        assertThat(receipt(7)).hasValueSatisfying(e -> {
            assertThat(e.normalizedStatus()).isEqualTo("FAILED");
            assertThat(e.hardBounce()).isTrue();
        });
        assertThat(receipt(4))
                .hasValueSatisfying(e -> assertThat(e.hardBounce()).isFalse());
        assertThat(receipt(2))
                .hasValueSatisfying(e -> assertThat(e.hardBounce()).isFalse());
    }

    @Test
    @DisplayName("a receipt keeps the provider's own word and reads nothing it cannot place")
    void aReceiptKeepsTheProvidersWordAndIgnoresWhatItCannotRead() {
        assertThat(vas.normalise(Map.of("login", "admin", "key", "", "id", 555555, "code", 4, "description", "DLVRD")))
                .hasValueSatisfying(event -> {
                    assertThat(event.providerMessageId()).isEqualTo("555555");
                    assertThat(event.providerStatus()).isEqualTo("DLVRD");
                });
        assertThat(vas.normalise(Map.of("id", 1, "code", 99)))
                .as("an undocumented code")
                .isEmpty();
        assertThat(vas.normalise(Map.of("id", 0, "code", 4)))
                .as("id 0 names nothing")
                .isEmpty();
        assertThat(vas.normalise(Map.of("code", 4))).as("no id").isEmpty();
        assertThat(vas.normalise(Map.of("id", 5, "code", 4, "description", "x".repeat(200))))
                .hasValueSatisfying(event -> assertThat(event.providerStatus()).hasSize(64));
    }

    @Test
    @DisplayName("the hash the adapter compares is the very hash the notification row keeps")
    void theContentHashMatchesTheNotificationsModule() {
        for (String text : List.of("", "Order A-17", "Заказ готов — 12 500 сўм", "ё".repeat(300))) {
            assertThat(VasSmsGatewayAdapter.sha256Hex(text)).isEqualTo(ContentHashes.of(text));
        }
    }

    @Test
    @DisplayName("two adapters for one gateway on one channel is a named startup failure; two gateways is not")
    void theRegistryAcceptsTwoGatewaysButNotTwoOfTheSame() {
        VasSmsGatewayAdapter second = new VasSmsGatewayAdapter(http);
        ProviderInstallationLookup lookup = lookup("http://127.0.0.1:1");
        SecretResolver secrets = secrets();

        assertThatThrownBy(() -> new NotificationGateway(List.of(vas, second), lookup, secrets))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMSGW_VAS")
                .hasMessageContaining("SMS");

        NotificationChannelAdapter other = new OtherSms();
        NotificationGateway both = new NotificationGateway(List.of(vas, other), lookup, secrets);
        assertThat(both.supports("SMS", "SMSGW_VAS")).isTrue();
        assertThat(both.supports("SMS", "OTHER_SMS")).isTrue();
        assertThat(both.supports("SMS", "ESKIZ")).isFalse();
    }

    @Test
    @DisplayName("a binding for a gateway nothing speaks is a mismatch, not a guess at another gateway's shape")
    void aBindingForAnUnwiredGatewayIsAMismatch() {
        NotificationGateway gateway = new NotificationGateway(
                List.of(new OtherSms()), lookup("http://127.0.0.1:1"), secrets(), binding -> account("l", "s"));

        ProviderOutcome outcome = gateway.send(dispatch("TRANSACTIONAL"));

        assertThat(outcome.errorCode()).isEqualTo("PROVIDER_ADAPTER_MISMATCH");
    }

    // ------------------------------------------------------------------ fixtures

    private static Optional<ReceiptEvent> receiptOf(VasSmsGatewayAdapter adapter, int code) {
        return adapter.normalise(Map.of("id", 100 + code, "code", code));
    }

    private Optional<ReceiptEvent> receipt(int code) {
        return receiptOf(vas, code);
    }

    private static Map<String, String> account(String login, String sender) {
        return Map.of("login", login, "sender", sender);
    }

    private NotificationGateway gateway(RecordingSmsGateway fake, Map<String, String> configuration) {
        BindingConfigurationLookup configurations = binding -> configuration;
        return new NotificationGateway(List.of(vas), lookup(fake.baseUrl()), secrets(), configurations);
    }

    private SecretResolver secrets() {
        return new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                cachedReads.incrementAndGet();
                return SecretValue.of("the-key");
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                freshReads.incrementAndGet();
                return SecretValue.of("the-key");
            }
        };
    }

    private static NotificationDispatch dispatch(String purpose) {
        return new NotificationDispatch(
                UUID.randomUUID(),
                UUID.randomUUID(),
                TENANT,
                BRAND,
                null,
                "SMS",
                NUMBER,
                null,
                TEXT,
                "attempt-key",
                "corr-1",
                "Order",
                UUID.randomUUID(),
                "ORDER_CONFIRMED",
                purpose);
    }

    private static ProviderInstallationLookup lookup(String baseUrl) {
        SecretReference reference =
                new SecretReference("local", SecretCategory.PROVIDER_NOTIFICATION, "tenant", "gateway");
        return new ProviderInstallationLookup() {
            @Override
            public Optional<BindingRef> primaryBinding(
                    UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
                return Optional.of(new BindingRef(
                        BINDING,
                        INSTALLATION,
                        tenantId,
                        ProviderCategory.NOTIFICATION,
                        providerTypeOf(),
                        brandId,
                        null));
            }

            @Override
            public List<BindingRef> candidateBindings(
                    UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
                return List.of();
            }

            @Override
            public Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId) {
                return Optional.of(new InstallationSnapshot(
                        INSTALLATION,
                        ProviderCategory.NOTIFICATION,
                        providerTypeOf(),
                        "local",
                        baseUrl,
                        "ACTIVE",
                        reference.toString(),
                        "v1"));
            }

            private String providerTypeOf() {
                return "SMSGW_VAS";
            }
        };
    }

    /** A second SMS gateway, to show the registry holds two and the binding picks between them. */
    private static final class OtherSms implements NotificationChannelAdapter {
        @Override
        public String providerType() {
            return "OTHER_SMS";
        }

        @Override
        public String channel() {
            return "SMS";
        }

        @Override
        public ProviderOutcome send(
                NotificationDispatch dispatch,
                uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall call) {
            return ProviderOutcome.success(Map.of(), "other-1");
        }

        @Override
        public ProviderOutcome queryStatus(
                String providerIdempotencyKey,
                uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall call) {
            return ProviderOutcome.uncertain("NONE", "none");
        }
    }
}
