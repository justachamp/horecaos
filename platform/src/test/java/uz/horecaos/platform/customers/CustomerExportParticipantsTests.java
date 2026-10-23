package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.assistant.application.AssistantExportParticipant;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.conversations.application.ConversationsExportParticipant;
import uz.horecaos.platform.customers.CustomerErasureParticipantsTests.Footprint;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.application.CustomerRecordsExportParticipant;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerExportReads;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcExportRequestStore;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant.ExportSubject;
import uz.horecaos.platform.customers.spi.ExportSection;
import uz.horecaos.platform.customers.spi.ExportTable;
import uz.horecaos.platform.customers.spi.ExportsCustomerData;
import uz.horecaos.platform.dinein.application.DineInExportParticipant;
import uz.horecaos.platform.integration.provider.telegram.TelegramExportParticipant;
import uz.horecaos.platform.loyalty.application.LoyaltyExportParticipant;
import uz.horecaos.platform.marketing.application.MarketingExportParticipant;
import uz.horecaos.platform.notifications.application.NotificationsExportParticipant;
import uz.horecaos.platform.ordering.application.OrderingExportParticipant;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderExportStore;
import uz.horecaos.platform.pricing.application.PricingExportParticipant;
import uz.horecaos.platform.referral.application.ReferralExportParticipant;
import uz.horecaos.platform.reviews.application.ReviewsExportParticipant;
import uz.horecaos.platform.voice.application.VoiceExportParticipant;

/**
 * Every export participant, run as the application role over real rows in every module (ADR 0029): the grants it needs, the
 * SQL it runs, the values it decrypts, and that it hands over only the subject's own data.
 *
 * <p>The seed is {@link CustomerErasureParticipantsTests}'s: the same two customers, each with a footprint in every module,
 * so "what the export carries" and "what the erasure removes" are measured against the same rows. What this would still pass
 * on if the code were broken: nothing about the bystander is asserted by absence of a column but by the absence of every id
 * the bystander's rows carry, so a participant that ignored the account in a WHERE clause fails; a table that came back empty
 * because its SQL joined the wrong key fails the "has rows" check; and a participant that forgot to decrypt shows the
 * ciphertext, which the plaintext assertions would not find.
 */
class CustomerExportParticipantsTests {

    private final CustomerErasureParticipantsTests base = new CustomerErasureParticipantsTests();
    private final ObjectMapper mapper =
            tools.jackson.databind.json.JsonMapper.builder().build();

    @BeforeAll
    static void startDatabase() {
        CustomerErasureParticipantsTests.startDatabase();
    }

    @AfterAll
    static void stopDatabase() {
        CustomerErasureParticipantsTests.stopDatabase();
    }

    @BeforeEach
    void setUp() {
        base.setUp();
        base.owner()
                .sql("TRUNCATE TABLE reviews.order_reviews, reviews.review_publications, "
                        + "reviews.recovery_case_events, customer.addresses, customer.consent_decisions, "
                        + "customer.brand_profiles, customer.leads, loyalty.entries, marketing.marketing_sends, "
                        + "marketing.suppressions, notifications.notifications, referral.redemptions, "
                        + "dinein.table_sessions CASCADE")
                .update();
    }

    private List<CustomerExportParticipant> participants() {
        var app = base.app;
        var store = new JdbcCustomerStore(app);
        var profiles = new CustomerProfileService(
                store, base.protection, base.objectMapper, base.clock, new JdbcAuditRecorder(app, base.objectMapper));
        return List.of(
                new CustomerRecordsExportParticipant(
                        store,
                        new JdbcCustomerExportReads(app),
                        new JdbcExportRequestStore(app),
                        profiles,
                        base.protection,
                        base.objectMapper),
                new OrderingExportParticipant(new JdbcOrderExportStore(app), base.protection),
                new LoyaltyExportParticipant(app),
                new MarketingExportParticipant(app),
                new ReviewsExportParticipant(app, base.protection),
                new DineInExportParticipant(app, base.protection),
                new NotificationsExportParticipant(app),
                new ConversationsExportParticipant(app, base.protection),
                new ReferralExportParticipant(app),
                new PricingExportParticipant(app),
                new VoiceExportParticipant(app, base.protection),
                new AssistantExportParticipant(app, base.protection),
                new TelegramExportParticipant(app));
    }

    @Test
    @DisplayName("every production export participant is wired into this suite")
    void everyProductionParticipantIsExercised() {
        Set<String> wired =
                participants().stream().map(p -> p.getClass().getName()).collect(Collectors.toSet());
        for (Class<?> production : ExportInventoryTests.participantClasses()) {
            assertThat(wired)
                    .as(
                            "%s is a production participant, so this suite must run it as the application role",
                            production.getName())
                    .contains(production.getName());
        }
    }

    @Test
    @DisplayName("an export carries the customer's rows from every module, decrypted, and nobody else's")
    void anExportCarriesHerDataAndOnlyHers() throws Exception {
        Footprint hers = base.seedFootprint(base.customer, "a");
        Footprint theirs = base.seedFootprint(CustomerErasureParticipantsTests.BYSTANDER, "b");
        Extras herExtras = seedExtras(base.customer, "a", hers);
        Extras theirExtras = seedExtras(CustomerErasureParticipantsTests.BYSTANDER, "b", theirs);

        ExportSubject subject = new ExportSubject(
                CustomerErasureParticipantsTests.TENANT,
                base.customer,
                Set.of(hers.phoneHash()),
                "export test purpose");
        Map<String, ExportSection> sections = new LinkedHashMap<>();
        for (CustomerExportParticipant participant : participants()) {
            String name = participant
                    .getClass()
                    .getAnnotation(ExportsCustomerData.class)
                    .name();
            sections.put(name, base.appTx.execute(status -> participant.export(subject)));
        }

        // Every participant answered for every table it declared: "looked, found 0" is an empty table, never a missing
        // one.
        for (CustomerExportParticipant participant : participants()) {
            ExportsCustomerData declaration = participant.getClass().getAnnotation(ExportsCustomerData.class);
            Set<String> returned = section(sections, declaration.name()).tables().stream()
                    .map(ExportTable::table)
                    .collect(Collectors.toSet());
            assertThat(returned)
                    .as("the %s section returns every table it declares", declaration.name())
                    .containsAll(List.of(declaration.tables()));
        }

        // Every table that was seeded for her has rows, so a join on the wrong key cannot pass as an empty answer.
        Set<String> mayBeEmpty = Set.of("customer.erasure_requests");
        List<String> empty = new ArrayList<>();
        sections.values()
                .forEach(section -> section.tables().forEach(table -> {
                    if (table.rows().isEmpty() && !mayBeEmpty.contains(table.table())) {
                        empty.add(table.table());
                    }
                }));
        assertThat(empty).as("tables seeded for her that came back empty").isEmpty();

        String bundle = mapper.writeValueAsString(sections);

        // Decrypted: the values she gave, in clear.
        assertThat(bundle)
                .contains("+998901112233")
                .contains("Karim Yoldoshev")
                .contains("Chilonzor 9, kv 12")
                .contains("ring twice")
                .contains("no nuts, my daughter is allergic")
                .contains("extra spicy")
                .contains("my address is Chilonzor 9")
                .contains("window seat")
                .contains("Great pizza a")
                .contains("Amir Temur 1 a")
                .contains("1990-05-17")
                .contains("Lead Name a")
                .contains("Old a");
        // Her own rows, by id, in modules that do not decrypt anything.
        assertThat(bundle)
                .contains(hers.order().toString())
                .contains(hers.conversation().toString())
                .contains(hers.call().toString())
                .contains(hers.loyaltyAccount().toString())
                .contains(hers.reservationByAccount().toString())
                .contains(hers.reservationByNumber().toString())
                .contains(hers.turn().toString())
                .contains(herExtras.review.toString());

        // Withheld on purpose, and said so in the section.
        assertThat(bundle)
                .doesNotContain("rude to the courier")
                .doesNotContain("internal note a")
                .doesNotContain("a".repeat(64));
        assertThat(section(sections, "customers").withheld()).contains("blacklist_entries.reasons_are_staff_notes");
        assertThat(section(sections, "reviews").withheld()).contains("recovery_case_events.staff_notes");

        // Nobody else's.
        for (UUID other : List.of(
                theirs.order(),
                theirs.conversation(),
                theirs.call(),
                theirs.loyaltyAccount(),
                theirs.reservationByAccount(),
                theirs.turn(),
                theirs.account(),
                theirExtras.review,
                theirExtras.lead)) {
            assertThat(bundle)
                    .as("the bystander's id %s must not be in her bundle", other)
                    .doesNotContain(other.toString());
        }
        assertThat(bundle)
                .doesNotContain("Amir Temur 1 b")
                .doesNotContain("Lead Name b")
                .doesNotContain("Great pizza b");
    }

    @Test
    @DisplayName(
            "a booking that holds her number but was never linked to her account is hers; one holding another number is not")
    void aBookingFoundByHerNumberIsHers() {
        Footprint hers = base.seedFootprint(base.customer, "a");
        ExportSubject withNumber = new ExportSubject(
                CustomerErasureParticipantsTests.TENANT,
                base.customer,
                Set.of(hers.phoneHash()),
                "export test purpose");
        ExportSubject withoutNumber = new ExportSubject(
                CustomerErasureParticipantsTests.TENANT, base.customer, Set.of(), "export test purpose");
        DineInExportParticipant dineIn = new DineInExportParticipant(base.app, base.protection);

        List<UUID> found = reservationIds(base.appTx.execute(status -> dineIn.export(withNumber)));
        List<UUID> notFound = reservationIds(base.appTx.execute(status -> dineIn.export(withoutNumber)));

        assertThat(found).contains(hers.reservationByAccount(), hers.reservationByNumber());
        assertThat(notFound)
                .as(
                        "without her number the booking a host typed is not reachable, which is why the hashes are passed in")
                .contains(hers.reservationByAccount())
                .doesNotContain(hers.reservationByNumber());
    }

    private static ExportSection section(Map<String, ExportSection> sections, String name) {
        return java.util.Objects.requireNonNull(sections.get(name), name);
    }

    private static List<UUID> reservationIds(ExportSection section) {
        return section.tables().stream()
                .filter(table -> table.table().equals("dinein.reservations"))
                .flatMap(table -> table.rows().stream())
                .map(row -> UUID.fromString((String) row.get("id")))
                .toList();
    }

    // ------------------------------------------------------------------------------------------- the rest of the seed

    private record Extras(UUID review, UUID lead) {}

    /** The rows the shared footprint does not seed: what only an export reads. */
    private Extras seedExtras(UUID account, String tag, Footprint footprint) {
        UUID tenant = CustomerErasureParticipantsTests.TENANT;
        UUID brand = UUID.fromString("018f9f10-6000-7000-8000-0000000000b1");
        UUID location = UUID.fromString("018f9f10-6000-7000-8000-0000000000c1");
        Instant now = CustomerErasureParticipantsTests.NOW;

        UUID address = UUID.randomUUID();
        base.insert(
                "customer.addresses",
                "id",
                address,
                "tenant_id",
                tenant,
                "customer_account_id",
                account,
                "label",
                "Home",
                "encrypted_fields",
                base.protect(
                        "customer.addresses",
                        "encrypted_fields",
                        address,
                        "{\"line1\":\"Amir Temur 1 " + tag + "\",\"city\":\"Tashkent\",\"district\":\"Yunusabad\"}"),
                "coordinate_source",
                "NOT_GEOCODED");
        base.insert(
                "customer.consent_decisions",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                tenant,
                "customer_account_id",
                account,
                "purpose",
                "MARKETING_PROMOTIONS",
                "decision",
                "GRANTED",
                "policy_version",
                "v1",
                "source",
                "STOREFRONT",
                "decided_at",
                now.minusSeconds(500));
        base.insert(
                "customer.brand_profiles",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                tenant,
                "brand_id",
                brand,
                "customer_account_id",
                account);
        UUID lead = UUID.randomUUID();
        base.insert(
                "customer.leads",
                "id",
                lead,
                "tenant_id",
                tenant,
                "brand_id",
                brand,
                "source",
                "STOREFRONT",
                "customer_account_id",
                account,
                "phone_lookup_hash",
                "lead-hash-" + tag,
                "phone_encrypted",
                base.protect("customer.leads", "phone_encrypted", lead, "+998901112233"),
                "phone_masked",
                "+998 ** *** 33",
                "display_name_encrypted",
                base.protect("customer.leads", "display_name_encrypted", lead, "Lead Name " + tag),
                "created_by",
                "system");
        base.owner()
                .sql("UPDATE customer.customer_accounts SET date_of_birth_encrypted = :dob WHERE id = :id")
                .param(
                        "dob",
                        base.protect("customer.customer_accounts", "date_of_birth_encrypted", account, "1990-05-17"))
                .param("id", account)
                .update();

        base.insert(
                "loyalty.entries",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                tenant,
                "account_id",
                footprint.loyaltyAccount(),
                "entry_type",
                "ADJUSTMENT",
                "amount_minor",
                100L,
                "balance_after_minor",
                100L,
                "reason_code",
                "GOODWILL",
                "actor",
                "staff-" + tag,
                "idempotency_key",
                "export-test-" + tag,
                "occurred_at",
                now.minusSeconds(300));
        base.insert(
                "marketing.marketing_sends",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                tenant,
                "brand_id",
                brand,
                "customer_account_id",
                account,
                "channel",
                "SMS",
                "source_type",
                "CAMPAIGN",
                "source_id",
                UUID.randomUUID(),
                "sent_at",
                now.minusSeconds(200));
        base.insert(
                "marketing.suppressions",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                tenant,
                "brand_id",
                brand,
                "customer_account_id",
                account,
                "channel",
                "SMS",
                "reason",
                "UNSUBSCRIBE",
                "applied_by_type",
                "CUSTOMER");
        base.insert(
                "notifications.notifications",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                tenant,
                "notification_class",
                "MARKETING",
                "channel",
                "SMS",
                "template_key",
                "promo",
                "subject_type",
                "ORDER",
                "subject_id",
                footprint.order(),
                "idempotency_key",
                "export-notification-" + tag,
                "recipient_account_id",
                account);
        base.insert(
                "referral.redemptions",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                tenant,
                "brand_id",
                brand,
                "code_id",
                UUID.randomUUID(),
                "program_id",
                UUID.randomUUID(),
                "program_version",
                1,
                "referrer_customer_account_id",
                account,
                "referee_customer_account_id",
                UUID.randomUUID(),
                "status",
                "PENDING",
                "redeemed_at",
                now.minusSeconds(100),
                "expires_at",
                now.plusSeconds(86_400),
                "referrer_reward_minor",
                100L,
                "referee_reward_minor",
                0L,
                "idempotency_key",
                "export-referral-" + tag);

        UUID review = UUID.randomUUID();
        base.insert(
                "reviews.order_reviews",
                "id",
                review,
                "tenant_id",
                tenant,
                "brand_id",
                brand,
                "location_id",
                location,
                "order_id",
                footprint.order(),
                "customer_account_id",
                account,
                "rating",
                (short) 5,
                "comment_protected",
                base.protect("reviews.order_reviews", "comment_protected", review, "Great pizza " + tag));
        base.insert(
                "reviews.review_publications",
                "tenant_id",
                tenant,
                "review_id",
                review,
                "brand_id",
                brand,
                "location_id",
                location,
                "public_consent",
                true,
                "comment_state",
                "PENDING");
        UUID reviewCase = UUID.randomUUID();
        base.insert(
                "reviews.recovery_cases",
                "id",
                reviewCase,
                "tenant_id",
                tenant,
                "brand_id",
                brand,
                "location_id",
                location,
                "order_id",
                footprint.order(),
                "review_id",
                review,
                "customer_account_id",
                account,
                "source",
                "AUTO",
                "policy_version",
                1,
                "first_response_due_at",
                now.plusSeconds(3600),
                "resolution_due_at",
                now.plusSeconds(7200));
        UUID caseEvent = UUID.randomUUID();
        base.insert(
                "reviews.recovery_case_events",
                "id",
                caseEvent,
                "tenant_id",
                tenant,
                "case_id",
                reviewCase,
                "kind",
                "NOTE",
                "actor_type",
                "STAFF",
                "note_protected",
                base.protect("reviews.recovery_case_events", "note_protected", caseEvent, "internal note " + tag),
                "occurred_at",
                now.minusSeconds(50));

        base.insert(
                "dinein.table_sessions",
                "id",
                UUID.randomUUID(),
                "tenant_id",
                tenant,
                "brand_id",
                brand,
                "location_id",
                location,
                "business_date",
                java.sql.Date.valueOf("2026-10-14"),
                "opened_by",
                "guest",
                "opened_at",
                now.minusSeconds(900),
                "currency",
                "UZS",
                "origin",
                "GUEST_QR",
                "opened_by_account_id",
                account,
                "claim_expires_at",
                now.plusSeconds(600));
        return new Extras(review, lead);
    }
}
