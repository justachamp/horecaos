package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.assistant.application.AssistantErasureParticipant;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.conversations.application.ConversationsErasureParticipant;
import uz.horecaos.platform.customers.application.CustomerErasureService;
import uz.horecaos.platform.customers.application.CustomerErasureService.ErasureBlockedException;
import uz.horecaos.platform.customers.application.CustomerErasureService.RequestedVia;
import uz.horecaos.platform.customers.application.CustomerRecordsErasureParticipant;
import uz.horecaos.platform.customers.application.LeadErasureParticipant;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerErasureStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerSessionStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.ErasureRequestRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLegalHoldStore;
import uz.horecaos.platform.customers.spi.CustomerErasureGuard;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.dinein.application.DineInErasureParticipant;
import uz.horecaos.platform.dinein.application.TableSessionService;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.integration.provider.telegram.TelegramErasureParticipant;
import uz.horecaos.platform.loyalty.application.LoyaltyErasureParticipant;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore;
import uz.horecaos.platform.marketing.application.CustomerMetricProjectionService;
import uz.horecaos.platform.marketing.application.MarketingErasureParticipant;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.notifications.application.NotificationsErasureParticipant;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.ordering.application.OrderingErasureGuard;
import uz.horecaos.platform.ordering.application.OrderingErasureParticipant;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderErasureStore;
import uz.horecaos.platform.pricing.application.PricingErasureParticipant;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcBenefitGrantStore;
import uz.horecaos.platform.referral.application.ReferralErasureParticipant;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore;
import uz.horecaos.platform.reviews.application.ReviewsErasureGuard;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;
import uz.horecaos.platform.voice.application.VoiceErasureParticipant;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore;

/**
 * An erasure reaches every module's copy of a customer, as the application role, and leaves a bystander alone (ADR 0029).
 *
 * <p>Three properties, and a green run needs all of them.
 *
 * <ul>
 *   <li><strong>It runs as {@code horecaos_application}.</strong> Every statement the participants issue goes through a
 *       login role that holds nothing but that group, so a participant that needs a DELETE or an UPDATE grant the migrations
 *       forgot fails here rather than in production. A superuser connection would pass them all.
 *   <li><strong>Each module's rows for the erased customer are overwritten or removed, and the bystander's are byte for
 *       byte as they were.</strong> The bystander has the same footprint in every table, so a participant that matched on
 *       tenant alone, or forgot the account predicate, erases her too and fails the "untouched" half.
 *   <li><strong>The production participants are all here.</strong> The scan in {@code ErasureInventoryTests} finds every class
 *       implementing the seam; this suite fails if one of them is not wired below, so a new participant cannot ship without a
 *       test that runs it as the application role.
 * </ul>
 *
 * <p>Rows are seeded through a superuser connection with {@code session_replication_role = replica}, which switches foreign
 * keys and triggers off for the seeding only: the erasure never runs on that connection, and the migrations' CHECK
 * constraints still apply to every seeded row.
 */
class CustomerErasureParticipantsTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-6000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f10-6000-7000-8000-0000000000c1");
    private static final UUID BYSTANDER = UUID.fromString("018f9f10-6000-7000-8000-0000000000d2");
    private static final String APP_ROLE = "erasure_probe_app";
    private static final String APP_PASSWORD = "erasure-probe-app";
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final ActorRef STAFF = ActorRef.user("owner-1", null);
    private static final ActorRef SELF = ActorRef.service("storefront-erasure-request");

    private static TestDatabase.Handle db;
    private static DataSource asApplication;
    private static JdbcClient owner;
    private static JdbcClient seed;
    private static SingleConnectionDataSource seedConnection;

    private final FieldProtection protection = TestProtection.envelope();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private JdbcClient app;
    private TransactionTemplate appTx;
    private CustomerErasureService erasure;
    private List<CustomerErasureParticipant> wired;
    private UUID customer;
    private OrderBoardFixtures fixtures;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the participant tests");
        db = TestDatabase.migrated();
        owner = JdbcClient.create(db.dataSource());
        owner.sql("DROP ROLE IF EXISTS " + APP_ROLE).update();
        owner.sql("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'")
                .update();
        owner.sql("ALTER ROLE " + APP_ROLE + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT")
                .update();
        owner.sql("GRANT " + TestDatabase.APPLICATION_ROLE + " TO " + APP_ROLE).update();
        asApplication = db.dataSourceAs(APP_ROLE, APP_PASSWORD);

        seedConnection = new SingleConnectionDataSource(db.jdbcUrl(), db.username(), db.password(), true);
        seed = JdbcClient.create(seedConnection);
        seed.sql("SET session_replication_role = replica").update();
    }

    @AfterAll
    static void stopDatabase() {
        if (seedConnection != null) {
            seedConnection.destroy();
        }
        if (db == null) {
            return;
        }
        db.close();
        try {
            TestDatabase.onCluster("DROP ROLE IF EXISTS " + APP_ROLE);
        } catch (RuntimeException leftover) {
            System.err.println("CustomerErasureParticipantsTests: " + APP_ROLE + " outlived the suite");
        }
    }

    @BeforeEach
    void setUp() {
        fixtures = new OrderBoardFixtures(owner);
        fixtures.clean();
        // Several of the tables seeded below carry no foreign key a truncate of the tenants would follow, so a previous
        // test's rows would outlive the clean and collide on a unique key.
        owner.sql(String.join(
                        ", ",
                        "TRUNCATE TABLE customer.contact_points",
                        "customer.blacklist_entries",
                        "customer.favourites",
                        "customer.customer_sessions",
                        "customer.legal_holds",
                        "customer.erasure_requests",
                        "marketing.audience_snapshots",
                        "marketing.audience_snapshot_members",
                        "marketing.customer_metrics",
                        "marketing.metric_drift_observations",
                        "loyalty.accounts",
                        "loyalty.pos_balance_sync_state",
                        "referral.codes",
                        "pricing.benefit_grants",
                        "notifications.notification_preferences",
                        "notifications.recipient_endpoints",
                        "ordering.order_customer_snapshots",
                        "ordering.order_lines",
                        "ordering.order_outcomes",
                        "ordering.order_amendments",
                        "ordering.order_amendment_commands",
                        "ordering.cart_lines",
                        "ordering.cart_fulfillment",
                        "ordering.carts",
                        "dinein.reservations",
                        "conversations.conversations",
                        "conversations.conversation_messages",
                        "conversations.flow_runs",
                        "voice.call_events",
                        "assistant.turns",
                        "integration.telegram_pending_links",
                        "integration.sendpulse_import_runs",
                        "integration.sendpulse_import_run_rows",
                        "reviews.recovery_cases CASCADE"))
                .update();
        owner.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        fixtures.tenant(TENANT, "privacy-" + UUID.randomUUID().toString().substring(0, 8), BRAND, LOCATION);
        customer = OrderBoardFixtures.customerOf(TENANT);
        seed.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, identity_policy_version,
                    version)
                VALUES (:id, :t, 'ACTIVE', 'Bystander', 1, 1)
                """).param("id", BYSTANDER).param("t", TENANT).update();

        app = JdbcClient.create(asApplication);
        appTx = new TransactionTemplate(new DataSourceTransactionManager(asApplication));
        wired = participants();
        erasure = new CustomerErasureService(
                new JdbcCustomerStore(app),
                new JdbcCustomerErasureStore(app),
                new JdbcLegalHoldStore(app),
                protection,
                clock,
                new JdbcAuditRecorder(app, objectMapper),
                objectMapper,
                wired,
                List.of(
                        new OrderingErasureGuard(new JdbcOrderErasureStore(app)),
                        new ReviewsErasureGuard(new JdbcRecoveryCaseStore(app))));
    }

    private List<CustomerErasureParticipant> participants() {
        return List.of(
                new LeadErasureParticipant(new JdbcLeadStore(app), protection, clock),
                new CustomerRecordsErasureParticipant(
                        new JdbcCustomerErasureStore(app), new JdbcCustomerSessionStore(app), protection, clock),
                new MarketingErasureParticipant(
                        new CustomerMetricProjectionService(new JdbcCustomerMetricStore(app), clock),
                        new JdbcAudienceStore(app, objectMapper)),
                new LoyaltyErasureParticipant(new JdbcLoyaltyStore(app), new JdbcPosBalanceSyncStore(app), clock),
                new ReferralErasureParticipant(new JdbcReferralStore(app), clock),
                new PricingErasureParticipant(new JdbcBenefitGrantStore(app), clock),
                new NotificationsErasureParticipant(new JdbcNotificationStore(app), clock),
                new OrderingErasureParticipant(new JdbcOrderErasureStore(app), protection, clock),
                new DineInErasureParticipant(
                        new JdbcDineInStore(app), Mockito.mock(TableSessionService.class), protection),
                new ConversationsErasureParticipant(app),
                new VoiceErasureParticipant(new JdbcVoiceStore(app, objectMapper), protection),
                new AssistantErasureParticipant(app, protection),
                new TelegramErasureParticipant(app));
    }

    // ------------------------------------------------------------------------------------------ the tests

    @Test
    @DisplayName("every production participant is wired into this suite")
    void everyProductionParticipantIsExercised() {
        Set<String> wiredNames = wired.stream()
                .map(participant -> participant.getClass().getName())
                .collect(Collectors.toSet());
        // Reviews is exercised by its own suite (it needs the review cache and event publisher).
        Set<String> exercisedElsewhere = Set.of("uz.horecaos.platform.reviews.application.ReviewsErasureParticipant");
        for (Class<?> productionClass : ErasureInventoryTests.participantClasses()) {
            String production = productionClass.getName();
            if (!exercisedElsewhere.contains(production)) {
                assertThat(wiredNames)
                        .as(
                                "%s is a production participant, so this suite must run it as the application role",
                                production)
                        .contains(production);
            }
        }
    }

    @Test
    @DisplayName("executing the erasure overwrites or removes the customer's rows in every module, and only hers")
    void anExecutionReachesEveryModuleAndOnlyThatCustomer() {
        Footprint hers = seedFootprint(customer, "a");
        Footprint theirs = seedFootprint(BYSTANDER, "b");
        Map<String, String> bystanderBefore = snapshotOf(theirs);

        ErasureRequestRow request = erasure.request(TENANT, customer, RequestedVia.OPERATIONS, STAFF);
        appTx.execute(status -> erasure.execute(TENANT, customer, request.id(), STAFF));

        // customer-records
        assertThat(reveal("customer.blacklist_entries", "reason_encrypted", hers.blacklist))
                .isEqualTo("ERASED");
        assertThat(count("customer.favourites", "account_id", customer)).isZero();
        assertThat(count("customer.customer_sessions", "customer_account_id", customer, "revoked_at IS NULL"))
                .as("her live session is revoked, not left to the sweeper's grace")
                .isZero();
        // marketing
        assertThat(count("marketing.customer_metrics", "customer_account_id", customer))
                .isZero();
        assertThat(count("marketing.audience_snapshot_members", "customer_account_id", customer))
                .isZero();
        assertThat(count("marketing.metric_drift_observations", "customer_account_id", customer))
                .isZero();
        // loyalty
        assertThat(text("SELECT status FROM loyalty.accounts WHERE id = :id", hers.loyaltyAccount))
                .isEqualTo("SUSPENDED");
        assertThat(text(
                        "SELECT status FROM loyalty.pos_balance_sync_state WHERE account_id = :id",
                        hers.loyaltyAccount))
                .isEqualTo("RETIRED");
        // referral, pricing
        assertThat(text("SELECT status FROM referral.codes WHERE customer_account_id = :id", customer))
                .isEqualTo("DISABLED");
        assertThat(text("SELECT status FROM pricing.benefit_grants WHERE customer_account_id = :id", customer))
                .isEqualTo("REVOKED");
        // notifications
        assertThat(count("notifications.notification_preferences", "customer_account_id", customer))
                .isZero();
        assertThat(text(
                        "SELECT status FROM notifications.recipient_endpoints WHERE customer_account_id = :id",
                        customer))
                .isEqualTo("RETIRED");
        assertThat(text(
                        "SELECT normalized_hash FROM notifications.recipient_endpoints WHERE customer_account_id = :id",
                        customer))
                .as("an endpoint that kept the old hash would still answer a search for her number")
                .isNotEqualTo(hers.phoneHash);
        // ordering
        assertThat(text(
                        "SELECT anonymized_at::text FROM ordering.order_customer_snapshots WHERE order_id = :id",
                        hers.order))
                .isNotNull();
        assertThat(owner.sql("""
                        SELECT display_name_encrypted IS NULL AND contact_encrypted IS NULL
                           AND address_encrypted IS NULL AND delivery_instructions_encrypted IS NULL
                          FROM ordering.order_customer_snapshots WHERE order_id = :id
                        """).param("id", hers.order).query(Boolean.class).single())
                .as("the name, contact, address and delivery note of her order are gone")
                .isTrue();
        assertThat(owner.sql("SELECT note_encrypted FROM ordering.order_lines WHERE order_id = :id")
                        .param("id", hers.order)
                        .query(String.class)
                        .optional())
                .isEmpty();
        assertThat(owner.sql("SELECT note_encrypted FROM ordering.order_outcomes WHERE order_id = :id")
                        .param("id", hers.order)
                        .query(String.class)
                        .optional())
                .isEmpty();
        assertThat(text(
                        "SELECT payload_json::text FROM ordering.order_amendment_commands WHERE amendment_id = :id "
                                + "AND command_type = 'CHANGE_CONTACT'",
                        hers.amendment))
                .as("the recipient name and phone an amendment typed in")
                .contains("erased")
                .doesNotContain("998");
        assertThat(text(
                        "SELECT payload_json::text FROM ordering.order_amendment_commands WHERE amendment_id = :id "
                                + "AND command_type = 'SET_KITCHEN_NOTE'",
                        hers.amendment))
                .as("a kitchen note is an operational instruction, not the customer's words, and stays")
                .contains("less salt");
        assertThat(count("ordering.carts", "id", hers.openCart))
                .as("her open cart is deleted")
                .isZero();
        assertThat(count("ordering.carts", "id", hers.orderedCart))
                .as("the cart her order came from is that order's history")
                .isEqualTo(1);
        assertThat(owner.sql("SELECT customer_note_encrypted FROM ordering.cart_lines WHERE cart_id = :id")
                        .param("id", hers.orderedCart)
                        .query(String.class)
                        .optional())
                .isEmpty();
        assertThat(reveal("ordering.cart_fulfillment", "recipient_phone_encrypted", hers.orderedCart))
                .isEqualTo("ERASED");
        assertThat(owner.sql("SELECT latitude FROM ordering.cart_fulfillment WHERE cart_id = :id")
                        .param("id", hers.orderedCart)
                        .query(Double.class)
                        .single())
                .as("a clear coordinate would still point at the building")
                .isZero();
        // dinein: the booking she made with her account, and the one a host typed for her number
        assertThat(reveal("dinein.reservations", "guest_name_encrypted", hers.reservationByAccount))
                .isEqualTo("ERASED");
        assertThat(reveal("dinein.reservations", "guest_phone_encrypted", hers.reservationByNumber))
                .isEqualTo("ERASED");
        assertThat(text(
                        "SELECT guest_phone_lookup_hash FROM dinein.reservations WHERE id = :id",
                        hers.reservationByNumber))
                .isNotEqualTo(hers.phoneHash);
        // conversations, voice, assistant, telegram
        assertThat(count("conversations.conversations", "customer_account_id", customer))
                .isZero();
        assertThat(count("conversations.conversation_messages", "conversation_id", hers.conversation))
                .isZero();
        assertThat(count("conversations.flow_runs", "conversation_id", hers.conversation))
                .isZero();
        assertThat(text("SELECT caller_number_masked FROM voice.call_events WHERE id = :id", hers.call))
                .isEqualTo("[erased]");
        assertThat(reveal("voice.call_events", "caller_number_encrypted", hers.call))
                .isEqualTo("ERASED");
        assertThat(owner.sql("SELECT resolved_customer_account_id FROM voice.call_events WHERE id = :id")
                        .param("id", hers.call)
                        .query(UUID.class)
                        .optional())
                .isEmpty();
        assertThat(owner.sql("SELECT customer_pseudonym FROM assistant.turns WHERE id = :id")
                        .param("id", hers.turn)
                        .query(String.class)
                        .optional())
                .isEmpty();
        assertThat(count("integration.telegram_pending_links", "customer_account_id", customer))
                .isZero();
        assertThat(owner.sql("SELECT chat_id FROM integration.sendpulse_import_run_rows WHERE id = :id")
                        .param("id", hers.importRow)
                        .query(Long.class)
                        .optional())
                .isEmpty();

        // the bystander is byte for byte as she was
        assertThat(snapshotOf(theirs)).isEqualTo(bystanderBefore);
    }

    @Test
    @DisplayName("the proof names every participant and the rows each one changed, and no value")
    void theProofSaysWhatWasChanged() {
        seedFootprint(customer, "a");
        ErasureRequestRow request = erasure.request(TENANT, customer, RequestedVia.OPERATIONS, STAFF);
        appTx.execute(status -> erasure.execute(TENANT, customer, request.id(), STAFF));

        var proof = erasure.proof(TENANT, customer, request.id());

        List<String> names = proof.participants().stream().map(p -> p.name()).toList();
        assertThat(names)
                .contains(
                        "customers",
                        "leads",
                        "customer-records",
                        "marketing",
                        "loyalty",
                        "referral",
                        "pricing",
                        "notifications",
                        "ordering",
                        "dinein",
                        "conversations",
                        "voice",
                        "assistant",
                        "telegram");
        var ordering = proof.participants().stream()
                .filter(p -> p.name().equals("ordering"))
                .findFirst()
                .orElseThrow();
        assertThat(ordering.tables())
                .as("a table looked at and not changed is listed with 0, not left out")
                .extracting(t -> t.table())
                .contains("ordering.order_customer_snapshots", "ordering.cart_fulfillment");
        assertThat(ordering.tables().stream()
                        .filter(t -> t.table().equals("ordering.order_customer_snapshots"))
                        .findFirst()
                        .orElseThrow()
                        .rows())
                .isEqualTo(1);
        String stored = owner.sql("SELECT proof::text FROM customer.erasure_requests WHERE id = :id")
                .param("id", request.id())
                .query(String.class)
                .single();
        assertThat(stored)
                .as("a proof is counts and table names: nothing that was erased may be read back from it")
                .doesNotContain("Karim")
                .doesNotContain("998");
    }

    @Test
    @DisplayName("an order still on its way, or a recovery case still open, refuses the execution and changes nothing")
    void aPendingDeliveryOrAnOpenCaseRefusesTheExecution() {
        Footprint hers = seedFootprint(customer, "a");
        seed.sql("UPDATE ordering.orders SET status = 'PREPARING', confirmed_at = now() WHERE id = :id")
                .param("id", hers.order)
                .update();
        insertRecoveryCase(customer, hers.order, "IN_PROGRESS");
        ErasureRequestRow request = erasure.request(TENANT, customer, RequestedVia.OPERATIONS, STAFF);

        assertThatThrownBy(() -> appTx.execute(status -> erasure.execute(TENANT, customer, request.id(), STAFF)))
                .isInstanceOfSatisfying(
                        ErasureBlockedException.class,
                        blocked -> assertThat(blocked.blocks())
                                .extracting(CustomerErasureGuard.Block::code)
                                .containsExactlyInAnyOrder("OPEN_ORDER", "OPEN_RECOVERY_CASE"));

        assertThat(text("SELECT status FROM customer.erasure_requests WHERE id = :id", request.id()))
                .as("a refusal leaves the request PENDING so it can be executed once the order is done")
                .isEqualTo("PENDING");
        assertThat(text("SELECT status FROM customer.customer_accounts WHERE id = :id", customer))
                .isNotEqualTo("ANONYMIZED");
        assertThat(owner.sql("SELECT display_name_encrypted IS NOT NULL FROM ordering.order_customer_snapshots "
                                + "WHERE order_id = :id")
                        .param("id", hers.order)
                        .query(Boolean.class)
                        .single())
                .as("the kitchen still has the name and address the order cannot be delivered without")
                .isTrue();

        // once the order is delivered and the case closed, the same request executes
        seed.sql("UPDATE ordering.orders SET status = 'COMPLETED' WHERE id = :id")
                .param("id", hers.order)
                .update();
        seed.sql("DELETE FROM reviews.recovery_cases WHERE customer_account_id = :id")
                .param("id", customer)
                .update();
        ErasureRequestRow done = appTx.execute(status -> erasure.execute(TENANT, customer, request.id(), STAFF));
        assertThat(done.status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a legal hold refuses the execution, and lifting it lets the same request through")
    void aLegalHoldRefusesTheExecution() {
        seedFootprint(customer, "a");
        ErasureRequestRow request = erasure.request(TENANT, customer, RequestedVia.OPERATIONS, STAFF);
        UUID holdId = UUID.randomUUID();
        JdbcLegalHoldStore holds = new JdbcLegalHoldStore(app);
        assertThat(holds.insertIfNoneActive(holdId, TENANT, customer, "LITIGATION", "USER", "owner-1", null, NOW))
                .isTrue();

        assertThatThrownBy(() -> appTx.execute(status -> erasure.execute(TENANT, customer, request.id(), STAFF)))
                .isInstanceOfSatisfying(
                        ErasureBlockedException.class,
                        blocked -> assertThat(blocked.blocks())
                                .extracting(CustomerErasureGuard.Block::code)
                                .containsExactly(CustomerErasureService.LEGAL_HOLD_BLOCK));
        assertThat(erasure.blocksFor(TENANT, customer)).hasSize(1);

        assertThat(holds.lift(TENANT, holdId, "USER", "owner-2", null, NOW)).isEqualTo(1);
        assertThat(erasure.blocksFor(TENANT, customer)).isEmpty();
        assertThat(appTx.execute(status -> erasure.execute(TENANT, customer, request.id(), SELF))
                        .status())
                .isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------------------------------ seeding

    /** The ids a footprint is read back by. */
    private record Footprint(
            UUID account,
            String tag,
            String phoneHash,
            UUID blacklist,
            UUID loyaltyAccount,
            UUID order,
            UUID amendment,
            UUID orderedCart,
            UUID openCart,
            UUID reservationByAccount,
            UUID reservationByNumber,
            UUID conversation,
            UUID call,
            UUID turn,
            UUID importRow) {}

    private Footprint seedFootprint(UUID account, String tag) {
        String phoneHash = "phone-hash-" + tag;
        UUID contactPoint = UUID.randomUUID();
        insert(
                "customer.contact_points",
                "id",
                contactPoint,
                "tenant_id",
                TENANT,
                "customer_account_id",
                account,
                "type",
                "PHONE",
                "normalized_hash",
                phoneHash,
                "encrypted_value",
                protect("customer.contact_points", "encrypted_value", contactPoint, "+998901112233"));

        UUID blacklist = UUID.randomUUID();
        insert(
                "customer.blacklist_entries",
                "id",
                blacklist,
                "tenant_id",
                TENANT,
                "customer_account_id",
                account,
                "reason_encrypted",
                protect("customer.blacklist_entries", "reason_encrypted", blacklist, "rude to the courier"),
                "actor_type",
                "USER",
                "actor_id",
                "owner-1");
        insert(
                "customer.favourites",
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "account_id",
                account,
                "product_id",
                UUID.randomUUID());
        insert(
                "customer.customer_sessions",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "customer_account_id",
                account,
                "token_hash",
                tag.equals("a") ? "a".repeat(64) : "b".repeat(64),
                "issued_at",
                NOW.minusSeconds(60),
                "expires_at",
                NOW.plusSeconds(3600));

        // marketing
        UUID snapshot = UUID.randomUUID();
        insert(
                "marketing.audience_snapshots",
                "id",
                snapshot,
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "audience_id",
                UUID.randomUUID(),
                "definition_version",
                1,
                "channel",
                "SMS",
                "consent_purpose",
                "MARKETING_PROMOTIONS",
                "metric_definition_version",
                1,
                "built_by",
                UUID.randomUUID(),
                "status",
                "READY",
                "completed_at",
                NOW,
                "candidate_count",
                2,
                "member_count",
                2);
        insert(
                "marketing.audience_snapshot_members",
                "snapshot_id",
                snapshot,
                "tenant_id",
                TENANT,
                "customer_account_id",
                account,
                "inclusion_status",
                "INCLUDED");
        insert(
                "marketing.customer_metrics",
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "customer_account_id",
                account,
                "registered_at",
                NOW.minusSeconds(86_400),
                "metric_definition_version",
                1);
        insert(
                "marketing.metric_drift_observations",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "customer_account_id",
                account,
                "metric_name",
                "order_count",
                "metric_definition_version",
                1,
                "projected_value",
                "3",
                "recomputed_value",
                "4");

        // loyalty
        UUID loyaltyAccount = UUID.randomUUID();
        insert(
                "loyalty.accounts",
                "id",
                loyaltyAccount,
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "customer_account_id",
                account,
                "currency",
                "UZS");
        insert(
                "loyalty.pos_balance_sync_state",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "binding_id",
                UUID.randomUUID(),
                "account_id",
                loyaltyAccount,
                "mapping_id",
                UUID.randomUUID(),
                "external_customer_id",
                "till-" + tag);

        // referral, pricing
        insert(
                "referral.codes",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "customer_account_id",
                account,
                "code",
                "REF" + tag.toUpperCase(java.util.Locale.ROOT) + "12345");
        insert(
                "pricing.benefit_grants",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "customer_account_id",
                account,
                "benefit_type",
                "FREE_DELIVERY",
                "value",
                1,
                "code_hash",
                (tag.equals("a") ? "c" : "d").repeat(64),
                "code_hint",
                "XX" + tag,
                "source_type",
                "OPERATOR_MANUAL",
                "valid_from",
                NOW);

        // notifications
        insert(
                "notifications.notification_preferences",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                TENANT,
                "customer_account_id",
                account,
                "notification_class",
                "TRANSACTIONAL_OPTIONAL",
                "channel",
                "SMS",
                "enabled",
                true);
        insert(
                "notifications.recipient_endpoints",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                TENANT,
                "customer_account_id",
                account,
                "endpoint_type",
                "PHONE",
                "contact_point_id",
                contactPoint,
                "normalized_hash",
                phoneHash);

        // ordering: a delivered order with its copies, an amendment, the cart it came from, and a cart left open
        UUID order = fixtures.insertOrder(OrderBoardFixtures.order("ORD-" + tag)
                .at(TENANT, BRAND, LOCATION)
                .status("COMPLETED"));
        UUID orderedCart = owner.sql("SELECT cart_id FROM ordering.orders WHERE id = :id")
                .param("id", order)
                .query(UUID.class)
                .single();
        if (!account.equals(customer)) {
            seed.sql("UPDATE ordering.orders SET customer_account_id = :a WHERE id = :id")
                    .param("a", account)
                    .param("id", order)
                    .update();
            seed.sql("UPDATE ordering.carts SET customer_account_id = :a WHERE id = :id")
                    .param("a", account)
                    .param("id", orderedCart)
                    .update();
        }
        insert(
                "ordering.order_customer_snapshots",
                "order_id",
                order,
                "tenant_id",
                TENANT,
                "display_name_encrypted",
                protect("ordering.order_customer_snapshots", "display_name_encrypted", order, "Karim Yoldoshev"),
                "contact_encrypted",
                protect("ordering.order_customer_snapshots", "contact_encrypted", order, "+998901112233"),
                "address_encrypted",
                protect("ordering.order_customer_snapshots", "address_encrypted", order, "Chilonzor 9, kv 12"),
                "delivery_instructions_encrypted",
                protect("ordering.order_customer_snapshots", "delivery_instructions_encrypted", order, "ring twice"));
        UUID line = UUID.randomUUID();
        insert(
                "ordering.order_lines",
                "id",
                line,
                "tenant_id",
                TENANT,
                "order_id",
                order,
                "line_number",
                1,
                "product_name_snapshot",
                "Osh",
                "quantity",
                1,
                "unit_amount_minor",
                50_000,
                "base_amount_minor",
                50_000,
                "final_amount_minor",
                50_000,
                "tax_amount_minor",
                0,
                "external_mapping_status",
                "UNMAPPED",
                "note_encrypted",
                protect("ordering.order_lines", "note_encrypted", line, "no nuts, my daughter is allergic"));
        insert(
                "ordering.order_outcomes",
                "order_id",
                order,
                "tenant_id",
                TENANT,
                "kind",
                "COMPLETED",
                "system_category",
                "OTHER",
                "actor_type",
                "USER",
                "stock_disposition",
                "NO_EFFECT",
                "reservation_committed",
                true,
                "occurred_at",
                NOW,
                "note_encrypted",
                protect("ordering.order_outcomes", "note_encrypted", order, "customer shouted at me"));
        UUID amendment = UUID.randomUUID();
        insert(
                "ordering.order_amendments",
                "id",
                amendment,
                "tenant_id",
                TENANT,
                "order_id",
                order,
                "status",
                "PRICED",
                "base_revision",
                1,
                "idempotency_key",
                "idem-" + amendment,
                "expires_at",
                NOW.plusSeconds(600),
                "created_by_actor_type",
                "USER");
        insert(
                "ordering.order_amendment_commands",
                "amendment_id",
                amendment,
                "sequence",
                1,
                "tenant_id",
                TENANT,
                "command_type",
                "CHANGE_CONTACT",
                "payload_json",
                new Json("{\"recipientName\":\"Karim\",\"recipientPhone\":\"+998901112233\"}"));
        insert(
                "ordering.order_amendment_commands",
                "amendment_id",
                amendment,
                "sequence",
                2,
                "tenant_id",
                TENANT,
                "command_type",
                "SET_KITCHEN_NOTE",
                "payload_json",
                new Json("{\"note\":\"less salt\"}"));
        UUID cartLine = UUID.randomUUID();
        insert(
                "ordering.cart_lines",
                "id",
                cartLine,
                "tenant_id",
                TENANT,
                "cart_id",
                orderedCart,
                "line_key",
                "k1",
                "variant_id",
                UUID.randomUUID(),
                "quantity",
                1,
                "customer_note_encrypted",
                protect("ordering.cart_lines", "customer_note_encrypted", cartLine, "extra spicy"));
        insert(
                "ordering.cart_fulfillment",
                "cart_id",
                orderedCart,
                "tenant_id",
                TENANT,
                "fulfillment_mode",
                "DELIVERY",
                "address_encrypted",
                protect("ordering.cart_fulfillment", "address_encrypted", orderedCart, "Chilonzor 9"),
                "recipient_name_encrypted",
                protect("ordering.cart_fulfillment", "recipient_name_encrypted", orderedCart, "Karim"),
                "recipient_phone_encrypted",
                protect("ordering.cart_fulfillment", "recipient_phone_encrypted", orderedCart, "+998901112233"),
                "latitude",
                41.3,
                "longitude",
                69.2);
        UUID openCart = UUID.randomUUID();
        insert(
                "ordering.carts",
                "id",
                openCart,
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "location_id",
                LOCATION,
                "channel_id",
                OrderBoardFixtures.channelOf(TENANT),
                "customer_account_id",
                account,
                "fulfillment_mode",
                "PICKUP",
                "currency",
                "UZS",
                "status",
                "ACTIVE",
                "expires_at",
                NOW.plusSeconds(3600));

        // dinein: a booking with her account, and one a host typed for her number (no account)
        UUID byAccount = UUID.randomUUID();
        insertReservation(byAccount, account, "hash-other-" + tag, "Guest " + tag);
        UUID byNumber = UUID.randomUUID();
        insertReservation(byNumber, null, phoneHash, "Guest " + tag);

        // conversations
        UUID conversation = UUID.randomUUID();
        insert(
                "conversations.conversations",
                "id",
                conversation,
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "installation_id",
                UUID.randomUUID(),
                "channel",
                "TELEGRAM",
                "channel_chat_id",
                tag.equals("a") ? 7001L : 7002L,
                "customer_account_id",
                account);
        UUID message = UUID.randomUUID();
        insert(
                "conversations.conversation_messages",
                "id",
                message,
                "tenant_id",
                TENANT,
                "conversation_id",
                conversation,
                "direction",
                "INBOUND",
                "body_protected",
                protect("conversations.conversation_messages", "body_protected", message, "my address is Chilonzor 9"));
        insert(
                "conversations.flow_runs",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                TENANT,
                "conversation_id",
                conversation,
                "flow_document_id",
                UUID.randomUUID(),
                "flow_version",
                1,
                "current_state_id",
                "ask-address");

        // voice, assistant
        UUID call = UUID.randomUUID();
        insert(
                "voice.call_events",
                "id",
                call,
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "location_id",
                LOCATION,
                "installation_id",
                UUID.randomUUID(),
                "provider_call_id",
                "call-" + tag,
                "event_type",
                "ENDED",
                "direction",
                "INBOUND",
                "occurred_at",
                NOW,
                "caller_number_encrypted",
                protect("voice.call_events", "caller_number_encrypted", call, "+998901112233"),
                "caller_number_masked",
                "+998 ** *** 22 33",
                "resolved_customer_account_id",
                account);
        UUID turn = UUID.randomUUID();
        insert(
                "assistant.turns",
                "id",
                turn,
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "conversation_id",
                conversation,
                "occurred_at",
                NOW,
                "locale",
                "ru",
                "question_kinds",
                "HOURS",
                "outcome",
                "ANSWERED",
                "customer_pseudonym",
                protection.lookupHash(TENANT, "assistant.customer", account.toString()));

        // telegram
        insert(
                "integration.telegram_pending_links",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                TENANT,
                "code",
                "CODE-" + tag,
                "expires_at",
                NOW.plusSeconds(600),
                "audience",
                "CUSTOMER",
                "customer_account_id",
                account);
        UUID run = UUID.randomUUID();
        insert(
                "integration.sendpulse_import_runs",
                "id",
                run,
                "tenant_id",
                TENANT,
                "installation_id",
                UUID.randomUUID(),
                "brand_id",
                BRAND,
                "dry_run",
                false,
                "status",
                "COMPLETE",
                "source_format",
                "CSV",
                "source_file_name",
                "subscribers-" + tag + ".csv",
                "imported_by_principal_id",
                "owner-1",
                "started_at",
                NOW,
                "completed_at",
                NOW);
        UUID importRow = UUID.randomUUID();
        insert(
                "integration.sendpulse_import_run_rows",
                "id",
                importRow,
                "tenant_id",
                TENANT,
                "run_id",
                run,
                "row_number",
                1,
                "chat_id",
                9000L + tag.charAt(0),
                "subscribed",
                true,
                "outcome",
                "MATCHED_CUSTOMER",
                "customer_account_id",
                account);

        return new Footprint(
                account,
                tag,
                phoneHash,
                blacklist,
                loyaltyAccount,
                order,
                amendment,
                orderedCart,
                openCart,
                byAccount,
                byNumber,
                conversation,
                call,
                turn,
                importRow);
    }

    private void insertReservation(UUID id, @Nullable UUID account, String phoneHash, String guestName) {
        insert(
                "dinein.reservations",
                "id",
                id,
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "location_id",
                LOCATION,
                "customer_account_id",
                account,
                "guest_name_encrypted",
                protect("dinein.reservations", "guest_name_encrypted", id, guestName),
                "guest_phone_encrypted",
                protect("dinein.reservations", "guest_phone_encrypted", id, "+998901112233"),
                "guest_phone_lookup_hash",
                phoneHash,
                "note_encrypted",
                protect("dinein.reservations", "note_encrypted", id, "window seat"),
                "party_size",
                4,
                "requested_from",
                NOW.plusSeconds(86_400),
                "requested_to",
                NOW.plusSeconds(93_600),
                "turnaround_minutes_snapshot",
                15,
                "source_channel_id",
                OrderBoardFixtures.channelOf(TENANT),
                "created_by",
                "host-1");
    }

    private void insertRecoveryCase(UUID account, UUID order, String state) {
        insert(
                "reviews.recovery_cases",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                TENANT,
                "brand_id",
                BRAND,
                "location_id",
                LOCATION,
                "order_id",
                order,
                "review_id",
                UUID.randomUUID(),
                "customer_account_id",
                account,
                "source",
                "AUTO",
                "state",
                state,
                "owner_staff_member_id",
                UUID.randomUUID(),
                "policy_version",
                1,
                "first_response_due_at",
                NOW,
                "resolution_due_at",
                NOW.plusSeconds(3600));
    }

    // ------------------------------------------------------------------------------------------ reading back

    /** Everything about the bystander's rows that an erasure could change, as comparable text. */
    private Map<String, String> snapshotOf(Footprint f) {
        Map<String, String> rows = new java.util.TreeMap<>();
        String account = f.account.toString();
        String[][] probes = {
            {"blacklist", "SELECT reason_encrypted FROM customer.blacklist_entries WHERE customer_account_id = :a"},
            {"favourites", "SELECT count(*)::text FROM customer.favourites WHERE account_id = :a"},
            {"sessions", "SELECT revoked_at::text FROM customer.customer_sessions WHERE customer_account_id = :a"},
            {"metrics", "SELECT count(*)::text FROM marketing.customer_metrics WHERE customer_account_id = :a"},
            {"members", "SELECT count(*)::text FROM marketing.audience_snapshot_members WHERE customer_account_id = :a"
            },
            {"drift", "SELECT count(*)::text FROM marketing.metric_drift_observations WHERE customer_account_id = :a"},
            {"loyalty", "SELECT status FROM loyalty.accounts WHERE customer_account_id = :a"},
            {
                "till",
                "SELECT s.status FROM loyalty.pos_balance_sync_state s JOIN loyalty.accounts l ON l.id = s.account_id "
                        + "WHERE l.customer_account_id = :a"
            },
            {"referral", "SELECT status FROM referral.codes WHERE customer_account_id = :a"},
            {"grants", "SELECT status FROM pricing.benefit_grants WHERE customer_account_id = :a"},
            {"prefs", "SELECT count(*)::text FROM notifications.notification_preferences WHERE customer_account_id = :a"
            },
            {
                "endpoints",
                "SELECT status || normalized_hash FROM notifications.recipient_endpoints WHERE customer_account_id = :a"
            },
            {
                "snapshot",
                "SELECT coalesce(display_name_encrypted, 'NULL') || coalesce(address_encrypted, 'NULL') "
                        + "|| coalesce(anonymized_at::text, 'live') FROM ordering.order_customer_snapshots s "
                        + "JOIN ordering.orders o ON o.id = s.order_id WHERE o.customer_account_id = :a"
            },
            {
                "lines",
                "SELECT note_encrypted FROM ordering.order_lines l JOIN ordering.orders o ON o.id = l.order_id "
                        + "WHERE o.customer_account_id = :a"
            },
            {
                "outcomes",
                "SELECT note_encrypted FROM ordering.order_outcomes l JOIN ordering.orders o ON o.id = l.order_id "
                        + "WHERE o.customer_account_id = :a"
            },
            {
                "amendments",
                "SELECT string_agg(payload_json::text, '|' ORDER BY sequence) FROM ordering.order_amendment_commands c "
                        + "JOIN ordering.order_amendments m ON m.id = c.amendment_id JOIN ordering.orders o ON o.id = m.order_id "
                        + "WHERE o.customer_account_id = :a"
            },
            {"carts", "SELECT count(*)::text FROM ordering.carts WHERE customer_account_id = :a"},
            {
                "cartLines",
                "SELECT customer_note_encrypted FROM ordering.cart_lines l JOIN ordering.carts c ON c.id = l.cart_id "
                        + "WHERE c.customer_account_id = :a"
            },
            {
                "destination",
                "SELECT recipient_phone_encrypted || latitude::text FROM ordering.cart_fulfillment f "
                        + "JOIN ordering.carts c ON c.id = f.cart_id WHERE c.customer_account_id = :a"
            },
            {
                "reservations",
                "SELECT string_agg(guest_name_encrypted || guest_phone_lookup_hash || coalesce(customer_account_id::text, '-'), "
                        + "'|' ORDER BY id) FROM dinein.reservations WHERE customer_account_id = :a OR guest_phone_lookup_hash = :h"
            },
            {"conversations", "SELECT count(*)::text FROM conversations.conversations WHERE customer_account_id = :a"},
            {
                "calls",
                "SELECT caller_number_encrypted || caller_number_masked FROM voice.call_events "
                        + "WHERE resolved_customer_account_id = :a"
            },
            {"turns", "SELECT customer_pseudonym FROM assistant.turns WHERE customer_pseudonym = :p"},
            {"links", "SELECT count(*)::text FROM integration.telegram_pending_links WHERE customer_account_id = :a"},
            {"imports", "SELECT chat_id::text FROM integration.sendpulse_import_run_rows WHERE customer_account_id = :a"
            },
        };
        for (String[] probe : probes) {
            rows.put(
                    probe[0],
                    String.valueOf(owner.sql(probe[1])
                            .param("a", f.account)
                            .param("h", f.phoneHash)
                            .param("p", protection.lookupHash(TENANT, "assistant.customer", account))
                            .query(String.class)
                            .list()));
        }
        return rows;
    }

    private String reveal(String table, String column, UUID rowId) {
        String ciphertext = owner.sql("SELECT " + column + " FROM " + table + " WHERE "
                        + (table.equals("ordering.cart_fulfillment") ? "cart_id" : "id") + " = :id")
                .param("id", rowId)
                .query(String.class)
                .single();
        return protection.reveal(
                TENANT, ProtectedValue.deserialize(ciphertext), new RecordRef(table, column, rowId), "erasure test");
    }

    private String protect(String table, String column, UUID rowId, String plaintext) {
        return protection
                .protect(TENANT, DataClass.PERSONAL, new RecordRef(table, column, rowId), plaintext)
                .serialize();
    }

    private long count(String table, String column, UUID value) {
        return count(table, column, value, "true");
    }

    private long count(String table, String column, UUID value, String extra) {
        return owner.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :v AND " + extra)
                .param("v", value)
                .query(Long.class)
                .single();
    }

    private @Nullable String text(String sql, UUID id) {
        return owner.sql(sql).param("id", id).query(String.class).optional().orElse(null);
    }

    /** A jsonb literal for {@link #insert}. */
    private record Json(String value) {}

    private void insert(String table, @Nullable Object... columnsAndValues) {
        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        for (int i = 0; i < columnsAndValues.length; i += 2) {
            columns.add((String) columnsAndValues[i]);
            Object value = columnsAndValues[i + 1];
            placeholders.add(value instanceof Json ? "CAST(:p" + (i / 2) + " AS jsonb)" : ":p" + (i / 2));
        }
        JdbcClient.StatementSpec spec = seed.sql("INSERT INTO " + table + " (" + String.join(", ", columns)
                + ") VALUES (" + String.join(", ", placeholders) + ")");
        for (int i = 0; i < columnsAndValues.length; i += 2) {
            Object value = columnsAndValues[i + 1];
            if (value instanceof Json json) {
                value = json.value();
            } else if (value instanceof Instant instant) {
                value = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
            }
            spec = spec.param("p" + (i / 2), value);
        }
        spec.update();
    }
}
