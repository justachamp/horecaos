package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.domain.NotificationVariableCatalog;
import uz.horecaos.platform.reviews.api.RecoveryAlertSettings;
import uz.horecaos.platform.reviews.api.RecoveryCaseOpened;
import uz.horecaos.platform.reviews.api.RecoveryCaseSlaBreached;
import uz.horecaos.platform.reviews.api.ReviewSubmitted;

/**
 * The chat alert service recovery raises (ADR 0159, Decision 10, ADR 0058): off unless the brand's policy asks
 * for it, one per case per clock, and made only of ids, a clock kind and a due time.
 *
 * <p>The alert is a message into a group of people who may include contractors and ex-staff, so what it
 * carries is the test's main subject: a customer's name, a comment or a staff member's name appearing in it
 * would be the defect, and the only variables allowed are the three the template catalogue declares.
 */
class ReviewsOperationsAlertTriggerTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-5000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-5000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f10-5000-7000-8000-0000000000c1");
    private static final UUID ORDER = UUID.fromString("018f9f10-5000-7000-8000-0000000000d1");
    private static final UUID REVIEW = UUID.fromString("018f9f10-5000-7000-8000-0000000000d2");
    private static final UUID CASE = UUID.fromString("018f9f10-5000-7000-8000-0000000000e1");
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    private OperationsAlertPort alerts;
    private boolean onOpen;
    private boolean onBreach;
    private ReviewsOperationsAlertTrigger trigger;

    @BeforeEach
    void setUp() {
        alerts = mock(OperationsAlertPort.class);
        onOpen = false;
        onBreach = false;
        RecoveryAlertSettings settings = new RecoveryAlertSettings() {
            @Override
            public boolean alertOnOpen(UUID tenantId, UUID brandId) {
                return onOpen && TENANT.equals(tenantId) && BRAND.equals(brandId);
            }

            @Override
            public boolean alertOnBreach(UUID tenantId, UUID brandId) {
                return onBreach && TENANT.equals(tenantId) && BRAND.equals(brandId);
            }
        };
        trigger = new ReviewsOperationsAlertTrigger(
                alerts,
                settings,
                Duration.ofDays(3),
                Duration.ofDays(1),
                AfterCommitAlertTransaction.withoutTransaction());
    }

    private RecoveryCaseOpened opened() {
        return new RecoveryCaseOpened(
                UUID.randomUUID(),
                TENANT,
                CASE,
                REVIEW,
                ORDER,
                BRAND,
                LOCATION,
                "AUTO",
                1,
                NOW.plus(Duration.ofHours(4)),
                NOW.plus(Duration.ofHours(72)),
                NOW);
    }

    private RecoveryCaseSlaBreached breached(String kind) {
        return new RecoveryCaseSlaBreached(
                UUID.randomUUID(), TENANT, CASE, ORDER, BRAND, LOCATION, kind, NOW, NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("nothing is announced unless the policy asks: a chat can subscribe and still hear nothing")
    void offByDefault() {
        trigger.onCaseOpened(opened());
        trigger.onSlaBreached(breached("FIRST_RESPONSE"));

        verify(alerts, never())
                .fanOut(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    @DisplayName(
            "a case opening is announced with its id and the time the first answer is due, and nothing about the customer")
    void anOpeningIsAnnounced() {
        onOpen = true;
        RecoveryCaseOpened event = opened();

        trigger.onCaseOpened(event);

        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.captor();
        verify(alerts)
                .fanOut(
                        org.mockito.ArgumentMatchers.eq(TENANT),
                        org.mockito.ArgumentMatchers.eq(BRAND),
                        org.mockito.ArgumentMatchers.eq(LOCATION),
                        org.mockito.ArgumentMatchers.eq("RECOVERY_CASE_OPENED"),
                        org.mockito.ArgumentMatchers.eq("RECOVERY_CASE_OPENED"),
                        org.mockito.ArgumentMatchers.eq("RecoveryCase"),
                        org.mockito.ArgumentMatchers.eq(CASE),
                        org.mockito.ArgumentMatchers.eq(event.eventId()),
                        org.mockito.ArgumentMatchers.eq("RECOVERY_CASE_OPENED:RecoveryCase:" + CASE),
                        variables.capture(),
                        org.mockito.ArgumentMatchers.eq(Duration.ofDays(3)));
        assertThat(variables.getValue())
                .containsOnlyKeys("caseId", "dueAt")
                .containsEntry("caseId", CASE.toString())
                .containsEntry("dueAt", NOW.plus(Duration.ofHours(4)).toString());
    }

    @Test
    @DisplayName(
            "a passed clock is announced once per case per clock: the two clocks have different keys, a replay the same one")
    void aBreachIsKeyedByTheClock() {
        onBreach = true;

        trigger.onSlaBreached(breached("FIRST_RESPONSE"));
        trigger.onSlaBreached(breached("RESOLUTION"));
        trigger.onSlaBreached(breached("RESOLUTION"));

        ArgumentCaptor<String> keys = ArgumentCaptor.captor();
        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.captor();
        verify(alerts, org.mockito.Mockito.times(3))
                .fanOut(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        keys.capture(),
                        variables.capture(),
                        any());
        assertThat(keys.getAllValues())
                .containsExactly(
                        "RECOVERY_CASE_SLA_BREACHED:RecoveryCase:" + CASE + ":FIRST_RESPONSE",
                        "RECOVERY_CASE_SLA_BREACHED:RecoveryCase:" + CASE + ":RESOLUTION",
                        "RECOVERY_CASE_SLA_BREACHED:RecoveryCase:" + CASE + ":RESOLUTION");
        assertThat(variables.getAllValues().getFirst())
                .containsOnlyKeys("caseId", "clock", "dueAt")
                .containsEntry("clock", "FIRST_RESPONSE");
    }

    @Test
    @DisplayName("the two switches are independent: asking about openings says nothing about breaches")
    void theSwitchesAreIndependent() {
        onOpen = true;
        trigger.onSlaBreached(breached("FIRST_RESPONSE"));
        verify(alerts, never())
                .fanOut(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any());

        onOpen = false;
        onBreach = true;
        trigger.onCaseOpened(opened());
        verify(alerts, never())
                .fanOut(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any());
    }

    private ReviewSubmitted review(int rating) {
        return new ReviewSubmitted(UUID.randomUUID(), TENANT, REVIEW, ORDER, BRAND, LOCATION, rating, true, 2, NOW);
    }

    @ParameterizedTest(name = "{0} stars is told as {1}")
    @CsvSource({"5,REVIEW_POSITIVE", "4,REVIEW_POSITIVE", "3,REVIEW_NEGATIVE", "2,REVIEW_NEGATIVE", "1,REVIEW_NEGATIVE"
    })
    @DisplayName(
            "a review is told as positive from four stars and as negative below, by its id and its stars alone (ADR 0187)")
    void aReviewIsToldByItsRating(int rating, String expectedClass) {
        ReviewSubmitted event = review(rating);

        trigger.onReviewSubmitted(event);

        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.captor();
        verify(alerts)
                .fanOut(
                        org.mockito.ArgumentMatchers.eq(TENANT),
                        org.mockito.ArgumentMatchers.eq(BRAND),
                        org.mockito.ArgumentMatchers.eq(LOCATION),
                        org.mockito.ArgumentMatchers.eq(expectedClass),
                        org.mockito.ArgumentMatchers.eq(expectedClass),
                        org.mockito.ArgumentMatchers.eq("Review"),
                        org.mockito.ArgumentMatchers.eq(REVIEW),
                        org.mockito.ArgumentMatchers.eq(event.eventId()),
                        org.mockito.ArgumentMatchers.eq(expectedClass + ":Review:" + REVIEW),
                        variables.capture(),
                        org.mockito.ArgumentMatchers.eq(Duration.ofDays(1)));
        assertThat(variables.getValue())
                .as("a review alert is made of two things; a customer, a comment and a tag are none of them")
                .containsOnlyKeys("reviewId", "rating")
                .containsEntry("reviewId", REVIEW.toString())
                .containsEntry("rating", Integer.toString(rating));
    }

    @Test
    @DisplayName("a fault in telling a chat never fails the customer's review")
    void aFailureToTellDoesNotFailTheSubmission() {
        org.mockito.Mockito.doThrow(new IllegalStateException("the subscription lookup is down"))
                .when(alerts)
                .fanOut(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any());

        org.assertj.core.api.Assertions.assertThatCode(() -> trigger.onReviewSubmitted(review(1)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a review alert needs no policy switch: it is told with both recovery switches off")
    void aReviewAlertIsNotGatedByTheRecoverySwitches() {
        assertThat(onOpen).isFalse();
        assertThat(onBreach).isFalse();

        trigger.onReviewSubmitted(review(1));

        verify(alerts)
                .fanOut(
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq("REVIEW_NEGATIVE"),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any());
    }

    @Test
    @DisplayName("the review alert's variables are declared by the OPERATIONS_ALERT catalogue and every class exists")
    void theReviewVariablesAreDeclared() {
        var declared = NotificationVariableCatalog.forClass(NotificationClass.OPERATIONS_ALERT).stream()
                .map(NotificationVariableCatalog.Variable::name)
                .toList();
        assertThat(declared).contains("reviewId", "rating");
        assertThat(uz.horecaos.platform.integration.provider.telegram.TelegramEventClass.valueOf(
                        ReviewsOperationsAlertTrigger.REVIEW_POSITIVE))
                .isEqualTo(uz.horecaos.platform.integration.provider.telegram.TelegramEventClass.REVIEW_POSITIVE);
        assertThat(uz.horecaos.platform.integration.provider.telegram.TelegramEventClass.valueOf(
                        ReviewsOperationsAlertTrigger.REVIEW_NEGATIVE))
                .isEqualTo(uz.horecaos.platform.integration.provider.telegram.TelegramEventClass.REVIEW_NEGATIVE);
    }

    @Test
    @DisplayName(
            "every variable the alerts use is one the OPERATIONS_ALERT catalogue declares, so a template can be written against it")
    void everyVariableIsDeclared() {
        onOpen = true;
        onBreach = true;
        trigger.onCaseOpened(opened());
        trigger.onSlaBreached(breached("RESOLUTION"));

        ArgumentCaptor<Map<String, String>> variables = ArgumentCaptor.captor();
        verify(alerts, org.mockito.Mockito.times(2))
                .fanOut(
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        variables.capture(),
                        any());
        var declared = NotificationVariableCatalog.forClass(NotificationClass.OPERATIONS_ALERT).stream()
                .map(NotificationVariableCatalog.Variable::name)
                .toList();
        assertThat(variables.getAllValues())
                .allSatisfy(used -> assertThat(declared).containsAll(used.keySet()));
    }
}
