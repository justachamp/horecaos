package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uz.horecaos.platform.marketing.ScenarioHarness.BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.OTHER_BRAND;
import static uz.horecaos.platform.marketing.ScenarioHarness.OTHER_TENANT;
import static uz.horecaos.platform.marketing.ScenarioHarness.PURPOSE;
import static uz.horecaos.platform.marketing.ScenarioHarness.START;
import static uz.horecaos.platform.marketing.ScenarioHarness.TENANT;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.marketing.application.ContactPolicyService.ContactDecision;
import uz.horecaos.platform.marketing.application.ContactPolicyService.ContactRequest;
import uz.horecaos.platform.marketing.application.ContactPolicyService.OverrideRequest;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.RefusalReason;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The contact policy as an explicit decision with a written reason (ADR 0112): a tenant may
 * tighten the platform's caps and quiet hours for one channel, purpose and period and never
 * loosen them, and every refusal names the rule and the numbers.
 */
class ContactPolicyTests {

    private static TestDatabase.Handle db;

    private ScenarioHarness h;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for contact policy tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        h = new ScenarioHarness(db);
    }

    // ------------------------------------------------------------- tighten-only

    @Test
    @DisplayName(
            "a cap above the platform's, or a quiet window narrower than the platform's, is refused with the platform's number")
    void looseningIsRefusedByTheService() {
        assertRefused(override("SMS", "DAILY", 4, null, null), "never loosened", "exceeds the platform's 3");
        assertRefused(override("SMS", "WEEKLY", 4, null, null), "never loosened", "exceeds the platform's 3");
        assertRefused(override("SMS", "ROLLING_7D", 4, null, null), "never loosened", "exceeds the platform's 3");
        assertRefused(override("SMS", "ROLLING_30D", 9, null, null), "never loosened", "exceeds the platform's 8");
        assertRefused(
                override("SMS", "DAILY", null, LocalTime.of(21, 30), LocalTime.of(10, 0)),
                "never loosened",
                "later than 21:00");
        assertRefused(
                override("SMS", "DAILY", null, LocalTime.of(21, 0), LocalTime.of(9, 0)),
                "never loosened",
                "earlier than 10:00");
        // Both bounds hold for these and they still loosen: a window inside one day closes a
        // few morning hours and leaves the evening open. The shape is part of the rule.
        assertRefused(
                override("SMS", "DAILY", null, LocalTime.of(5, 0), LocalTime.of(11, 0)),
                "never loosened",
                "does not wrap midnight");
        assertRefused(
                override("SMS", "DAILY", null, LocalTime.of(10, 0), LocalTime.of(21, 0)),
                "never loosened",
                "does not wrap midnight");
        assertThat(h.contactPolicy.list(TENANT, BRAND)).isEmpty();
    }

    @Test
    @DisplayName("the platform's own numbers, and anything tighter, are accepted")
    void tighteningIsAccepted() {
        h.contactPolicy.set(TENANT, BRAND, override("SMS", "DAILY", 3, null, null), null, h.author, authorId(), "corr");
        h.contactPolicy.set(
                TENANT, BRAND, override("SMS", "ROLLING_30D", 8, null, null), null, h.author, authorId(), "corr");
        h.contactPolicy.set(
                TENANT, BRAND, override("SMS", "WEEKLY", 0, null, null), null, h.author, authorId(), "corr");
        h.contactPolicy.set(
                TENANT,
                BRAND,
                override("EMAIL", "DAILY", null, LocalTime.of(21, 0), LocalTime.of(10, 0)),
                null,
                h.author,
                authorId(),
                "corr");
        h.contactPolicy.set(
                TENANT,
                BRAND,
                override("PUSH", "DAILY", 1, LocalTime.of(18, 0), LocalTime.of(11, 0)),
                null,
                h.author,
                authorId(),
                "corr");

        assertThat(h.contactPolicy.list(TENANT, BRAND)).hasSize(5);
    }

    @Test
    @DisplayName("an override says something, says why, and says it about a channel and a period that exist")
    void anOverrideIsWellFormed() {
        assertRefused(override("SMS", "DAILY", null, null, null), "says something");
        assertRefused(override("SMS", "DAILY", null, LocalTime.of(20, 0), null), "both a start and an end");
        assertRefused(override("SMS", "DAILY", -1, null, null), "cannot be negative");
        assertRefused(override("PIGEON", "DAILY", 1, null, null), "PIGEON");
        assertRefused(override("SMS", "HOURLY", 1, null, null), "HOURLY");
        assertRefused(new OverrideRequest("SMS", PURPOSE, "DAILY", 1, null, null, " "), "reason it was set");
    }

    @Test
    @DisplayName("the table says the same: a row that loosens a bound cannot be written, whatever wrote it")
    void theDatabaseRefusesALooseningRow() {
        record Bad(String period, String cap, String start, String end, String constraint) {}
        for (Bad bad : java.util.List.of(
                new Bad("DAILY", "4", "NULL", "NULL", "ck_contact_policy_cap_tighten_only"),
                new Bad("WEEKLY", "4", "NULL", "NULL", "ck_contact_policy_cap_tighten_only"),
                new Bad("ROLLING_30D", "9", "NULL", "NULL", "ck_contact_policy_cap_tighten_only"),
                new Bad("DAILY", "NULL", "'21:30'", "'10:00'", "ck_contact_policy_quiet_tighten_only"),
                new Bad("DAILY", "NULL", "'21:00'", "'09:00'", "ck_contact_policy_quiet_tighten_only"),
                new Bad("DAILY", "NULL", "'05:00'", "'11:00'", "ck_contact_policy_quiet_wraps_midnight"),
                new Bad("WEEKLY", "NULL", "'10:00'", "'21:00'", "ck_contact_policy_quiet_wraps_midnight"),
                new Bad("DAILY", "NULL", "'21:00'", "NULL", "ck_contact_policy_quiet_pair"))) {
            assertThatThrownBy(() -> h.jdbc.sql(
                                    "INSERT INTO marketing.contact_policy_overrides (tenant_id, brand_id, channel, "
                                            + "campaign_purpose, period_kind, cap_count, quiet_hours_start, quiet_hours_end, "
                                            + "stated_reason, updated_by) VALUES (:tenant, :brand, 'SMS', 'P', '"
                                            + bad.period()
                                            + "', " + bad.cap() + ", " + bad.start() + ", " + bad.end()
                                            + ", 'Direct insert', gen_random_uuid())")
                            .param("tenant", TENANT)
                            .param("brand", BRAND)
                            .update())
                    .as(bad.toString())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining(bad.constraint());
        }
    }

    // ------------------------------------------------------------- the record

    @Test
    @DisplayName(
            "an override is created once, replaced with the version that was read, and each change is audited with its reason")
    void overridesAreVersionedAndAudited() {
        var created = h.contactPolicy.set(
                TENANT,
                BRAND,
                new OverrideRequest("SMS", PURPOSE, "DAILY", 2, null, null, "Two a day is plenty"),
                null,
                h.author,
                authorId(),
                "corr");
        assertThat(created.version()).isEqualTo(1);

        assertThatThrownBy(() -> h.contactPolicy.set(
                        TENANT, BRAND, override("SMS", "DAILY", 1, null, null), null, h.author, authorId(), "corr"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> h.contactPolicy.set(
                        TENANT, BRAND, override("SMS", "DAILY", 1, null, null), 7, h.author, authorId(), "corr"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThatThrownBy(() -> h.contactPolicy.set(
                        TENANT, BRAND, override("EMAIL", "DAILY", 1, null, null), 1, h.author, authorId(), "corr"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        var replaced = h.contactPolicy.set(
                TENANT,
                BRAND,
                new OverrideRequest("SMS", PURPOSE, "DAILY", 1, null, null, "One is plenty"),
                1,
                h.author,
                authorId(),
                "corr");
        assertThat(replaced.version()).isEqualTo(2);
        assertThat(replaced.capCount()).isEqualTo(1);

        var facts = AuditTrail.facts(h.jdbc, "MARKETING_CONTACT_POLICY_SET");
        assertThat(facts).hasSize(2);
        assertThat(facts.stream().map(AuditTrail.Fact::reason)).containsExactly("Two a day is plenty", "One is plenty");
        assertThat(facts.getLast().before("capCount").asText()).isEqualTo("2");
        assertThat(facts.getLast().after("capCount").asText()).isEqualTo("1");

        h.contactPolicy.remove(TENANT, BRAND, "SMS", PURPOSE, "DAILY", h.author, "Policy review", "corr");
        assertThat(h.contactPolicy.list(TENANT, BRAND)).isEmpty();
        assertThat(AuditTrail.only(h.jdbc, "MARKETING_CONTACT_POLICY_REMOVED").reason())
                .isEqualTo("Policy review");
    }

    @Test
    @DisplayName("another tenant, and another brand, see none of a brand's overrides")
    void overridesAreScoped() {
        h.contactPolicy.set(TENANT, BRAND, override("SMS", "DAILY", 1, null, null), null, h.author, authorId(), "corr");

        assertThat(h.contactPolicy.list(OTHER_TENANT, BRAND)).isEmpty();
        assertThat(h.contactPolicy.list(TENANT, OTHER_BRAND)).isEmpty();
        assertThat(h.contactPolicy.list(TENANT, BRAND)).hasSize(1);
        assertThatThrownBy(() -> h.contactPolicy.remove(
                        TENANT, OTHER_BRAND, "SMS", PURPOSE, "DAILY", h.author, "Not mine", "corr"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // ------------------------------------------------------------- the decision

    @Test
    @DisplayName("with no override a guest within the platform's limits may be contacted now")
    void noOverrideAllowsNow() {
        UUID guest = h.reachableGuest("+998901200001");

        ContactDecision decision = decide(guest);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.deliverAt()).isEqualTo(START);
        assertThat(decision.reason()).isNull();
    }

    @Test
    @DisplayName("a daily cap refuses at the cap, names the rule and the numbers, and defers to the next local day")
    void aDailyCapRefusesWithItsNumbers() {
        UUID guest = h.reachableGuest("+998901200002");
        h.contactPolicy.set(TENANT, BRAND, override("SMS", "DAILY", 2, null, null), null, h.author, authorId(), "corr");

        send(guest, START.minusSeconds(60));
        assertThat(decide(guest).allowed()).isTrue();
        send(guest, START.minusSeconds(30));

        ContactDecision refused = decide(guest);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.reason()).isEqualTo(RefusalReason.FREQUENCY_CAP_REACHED);
        assertThat(refused.reasonText())
                .contains("2 already sent")
                .contains("DAILY")
                .contains("allows 2")
                .contains(PURPOSE);
        // Midnight in Tashkent (UTC+5) after 14:00 on 22 August.
        assertThat(refused.deferUntil()).isEqualTo(Instant.parse("2026-08-22T19:00:00Z"));

        // And the day after, the count starts again.
        h.clock.set(Instant.parse("2026-08-22T19:00:01Z"));
        assertThat(decide(guest).allowed()).isTrue();
    }

    @Test
    @DisplayName("a cap applies to its own channel, purpose and period and to nothing else")
    void aCapIsSpecific() {
        UUID guest = h.reachableGuest("+998901200003");
        h.contactPolicy.set(TENANT, BRAND, override("SMS", "DAILY", 1, null, null), null, h.author, authorId(), "corr");
        send(guest, START.minusSeconds(60));

        assertThat(decide(guest).allowed()).isFalse();
        assertThat(decide(guest, MarketingChannel.MESSAGING_APP, PURPOSE).allowed())
                .isTrue();
        assertThat(decide(guest, MarketingChannel.SMS, "NEWS").allowed()).isTrue();
        // A send outside the period does not count: yesterday is another day.
        UUID other = h.reachableGuest("+998901200004");
        send(other, START.minus(Duration.ofDays(1)));
        assertThat(decide(other).allowed()).isTrue();
    }

    @Test
    @DisplayName("a rolling window counts back from now, and a cap of zero silences the channel for that purpose")
    void rollingWindowsAndZero() {
        UUID guest = h.reachableGuest("+998901200005");
        h.contactPolicy.set(
                TENANT, BRAND, override("SMS", "ROLLING_7D", 1, null, null), null, h.author, authorId(), "corr");
        send(guest, START.minus(Duration.ofDays(6)));
        ContactDecision inside = decide(guest);
        assertThat(inside.allowed()).isFalse();
        assertThat(inside.reasonText()).contains("ROLLING_7D");
        assertThat(inside.deferUntil()).isEqualTo(START.plus(Duration.ofDays(1)));

        h.clock.advance(Duration.ofDays(2));
        assertThat(decide(guest).allowed()).isTrue();

        UUID silenced = h.reachableGuest("+998901200006");
        h.contactPolicy.set(TENANT, BRAND, override("SMS", "DAILY", 0, null, null), null, h.author, authorId(), "corr");
        assertThat(decide(silenced).allowed()).isFalse();
    }

    @Test
    @DisplayName("quiet hours widened by a tenant hold a message to the open boundary; the platform's alone would not")
    void widenedQuietHoursHoldTheMessage() {
        UUID guest = h.reachableGuest("+998901200007");
        h.clock.set(Instant.parse("2026-08-22T15:30:00Z")); // 20:30 in Tashkent
        assertThat(decide(guest).deliverAt()).isEqualTo(h.clock.instant());

        h.contactPolicy.set(
                TENANT,
                BRAND,
                override("SMS", "DAILY", null, LocalTime.of(20, 0), LocalTime.of(11, 0)),
                null,
                h.author,
                authorId(),
                "corr");

        ContactDecision held = decide(guest);
        assertThat(held.allowed()).isTrue();
        assertThat(held.deliverAt()).isAfter(h.clock.instant());
        assertThat(held.reasonText()).startsWith("Held to").contains("20:00").contains("11:00");
        // Tashkent 11:00 the next morning.
        assertThat(held.deliverAt()).isEqualTo(Instant.parse("2026-08-23T06:00:00Z"));
    }

    // ----------------------------------------------------------------- helpers

    private ContactDecision decide(UUID guest) {
        return decide(guest, MarketingChannel.SMS, PURPOSE);
    }

    private ContactDecision decide(UUID guest, MarketingChannel channel, String purpose) {
        return h.contactPolicy.decide(new ContactRequest(TENANT, BRAND, guest, channel, purpose), h.clock.instant());
    }

    private void send(UUID guest, Instant at) {
        h.engagementStore.recordSend(TENANT, BRAND, guest, "SMS", "CAMPAIGN", UUID.randomUUID(), null, at);
    }

    private static OverrideRequest override(
            String channel, String period, @Nullable Integer cap, @Nullable LocalTime start, @Nullable LocalTime end) {
        return new OverrideRequest(channel, PURPOSE, period, cap, start, end, "Set by a test");
    }

    private UUID authorId() {
        return UUID.fromString(h.author.subject());
    }

    private void assertRefused(OverrideRequest request, String... mentions) {
        assertThatThrownBy(() -> h.contactPolicy.set(TENANT, BRAND, request, null, h.author, authorId(), "corr"))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    for (String mention : mentions) {
                        assertThat(refused.getMessage()).contains(mention);
                    }
                });
    }
}
