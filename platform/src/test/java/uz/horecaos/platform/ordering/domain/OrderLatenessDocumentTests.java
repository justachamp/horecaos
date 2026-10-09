package uz.horecaos.platform.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.ordering.domain.OrderLatenessDocument.ModeThresholds;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy.LatenessLevel;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * The authored {@code ordering.lateness} document (gap map rows {@code X.39}/{@code 10.3b}): a mode's
 * at-risk window is optional and falls back to a default, the rest is per mode, and the bounds an
 * author may use are one place.
 */
class OrderLatenessDocumentTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    @Test
    void theUnauthoredDocumentResolvesToExactlyThePlatformDefaultWhenTheDefaultWindowIsThePlatforms() {
        int platformAtRisk = OrderLatenessPolicy.platformDefault().delivery().atRiskBeforeSeconds();

        assertThat(OrderLatenessDocument.platformDefault().effective(platformAtRisk, 2700))
                .isEqualTo(OrderLatenessPolicy.platformDefault());
    }

    @Test
    void aModeWithNoWindowOfItsOwnTakesTheDefaultAndAModeWithOneKeepsIt() {
        OrderLatenessDocument document = new OrderLatenessDocument(
                new ModeThresholds(null, 60, 2700), new ModeThresholds(0, 0, 1800), new ModeThresholds(90, 5, 1200));

        OrderLatenessPolicy effective = document.effective(600, 2700);

        assertThat(effective.delivery().atRiskBeforeSeconds())
                .as("unset takes the default")
                .isEqualTo(600);
        assertThat(effective.pickup().atRiskBeforeSeconds())
                .as("zero is a window of its own, not 'unset'")
                .isZero();
        assertThat(effective.dineIn().atRiskBeforeSeconds()).isEqualTo(90);
        assertThat(effective.delivery().lateAfterSeconds()).isEqualTo(60);
        assertThat(effective.pickup().noPromiseFallbackSeconds()).isEqualTo(1800);
        assertThat(effective.dineIn().lateAfterSeconds()).isEqualTo(5);
    }

    @Test
    void theEffectivePolicyEvaluatesEachModeAgainstItsOwnWindow() {
        OrderLatenessDocument document = new OrderLatenessDocument(
                new ModeThresholds(600, 0, 2700), new ModeThresholds(120, 0, 2700), new ModeThresholds(null, 0, 2700));
        OrderLatenessPolicy policy = document.effective(300, 2700);
        Instant promisedAt = NOW.plus(Duration.ofMinutes(8));
        OrderPromise promise = new OrderPromise(promisedAt, PromiseBasis.PREPARATION_BAND, 25, null);
        Instant createdAt = NOW.minus(Duration.ofMinutes(17));

        assertThat(policy.evaluate(FulfillmentMode.DELIVERY, promise, OrderStatus.PREPARING, createdAt, NOW))
                .isEqualTo(LatenessLevel.AT_RISK);
        assertThat(policy.evaluate(FulfillmentMode.PICKUP, promise, OrderStatus.PREPARING, createdAt, NOW))
                .isEqualTo(LatenessLevel.NORMAL);
        assertThat(policy.evaluate(FulfillmentMode.DINE_IN, promise, OrderStatus.PREPARING, createdAt, NOW))
                .as("dine-in sets none: the 300s default, and 8 minutes out is outside it")
                .isEqualTo(LatenessLevel.NORMAL);
    }

    @Test
    void anUnsetWindowSurvivesTheStoredJsonAsNullNotAsZero() throws Exception {
        OrderLatenessDocument document = new OrderLatenessDocument(
                new ModeThresholds(null, 30, 2700), new ModeThresholds(0, 0, 1800), new ModeThresholds(300, 0, 1200));

        String stored = JSON.writeValueAsString(document);
        OrderLatenessDocument read = JSON.readValue(stored, OrderLatenessDocument.class);

        assertThat(read).isEqualTo(document);
        assertThat(read.delivery().atRiskBeforeSeconds()).isNull();
        assertThat(read.pickup().atRiskBeforeSeconds()).isZero();
    }

    @Test
    void aDocumentWrittenBeforeTheWindowWasOptionalStillReadsAndItsWindowsAreItsOwn() throws Exception {
        String legacy = """
                {"delivery":{"atRiskBeforeSeconds":300,"lateAfterSeconds":60,"noPromiseFallbackSeconds":2700},
                 "pickup":{"atRiskBeforeSeconds":180,"lateAfterSeconds":0,"noPromiseFallbackSeconds":1800},
                 "dineIn":{"atRiskBeforeSeconds":120,"lateAfterSeconds":0,"noPromiseFallbackSeconds":1200}}""";

        OrderLatenessDocument read = JSON.readValue(legacy, OrderLatenessDocument.class);

        assertThat(read.effective(999, 2700).pickup().atRiskBeforeSeconds())
                .as("a stored number is a window the mode carries, whatever the default is")
                .isEqualTo(180);
        assertThat(read.violations()).isEmpty();
    }

    // ------------------------------------------------ ADR 0150: a blank fallback

    @Test
    void aModeWithNoFallbackOfItsOwnTakesTheDefaultAndAModeWithOneKeepsIt() {
        OrderLatenessDocument document = new OrderLatenessDocument(
                new ModeThresholds(null, 0, null),
                new ModeThresholds(null, 0, 1800),
                new ModeThresholds(null, 0, null));

        OrderLatenessPolicy effective = document.effective(300, 1200);

        assertThat(effective.delivery().noPromiseFallbackSeconds())
                .as("blank means none of its own: the tenant-wide default applies")
                .isEqualTo(1200);
        assertThat(effective.pickup().noPromiseFallbackSeconds())
                .as("a mode's own number wins over the default")
                .isEqualTo(1800);
        assertThat(effective.dineIn().noPromiseFallbackSeconds()).isEqualTo(1200);
    }

    @Test
    void theUnauthoredDocumentLeavesTheFallbackBlankSoTheScalarCanApply() {
        assertThat(OrderLatenessDocument.platformDefault().delivery().noPromiseFallbackSeconds())
                .as("a platformDefault() that carried 2700 would shadow the tenant's scalar for every tenant")
                .isNull();
        assertThat(OrderLatenessDocument.platformDefault()
                        .effective(300, 1200)
                        .pickup()
                        .noPromiseFallbackSeconds())
                .isEqualTo(1200);
    }

    @Test
    void aBlankFallbackSurvivesTheStoredJsonAsNullAndAMissingFieldReadsTheSame() throws Exception {
        OrderLatenessDocument document = new OrderLatenessDocument(
                new ModeThresholds(null, 30, null), new ModeThresholds(0, 0, 1800), new ModeThresholds(300, 0, null));

        OrderLatenessDocument read = JSON.readValue(JSON.writeValueAsString(document), OrderLatenessDocument.class);
        assertThat(read).isEqualTo(document);
        assertThat(read.delivery().noPromiseFallbackSeconds()).isNull();

        // Both generations read through the one record: a document an older writer produced has the
        // number, one a newer writer produced may have null or leave the field out.
        OrderLatenessDocument mixed = JSON.readValue("""
                {"delivery":{"atRiskBeforeSeconds":300,"lateAfterSeconds":60,"noPromiseFallbackSeconds":2700},
                 "pickup":{"atRiskBeforeSeconds":null,"lateAfterSeconds":0,"noPromiseFallbackSeconds":null},
                 "dineIn":{"lateAfterSeconds":0}}""", OrderLatenessDocument.class);
        assertThat(mixed.delivery().noPromiseFallbackSeconds()).isEqualTo(2700);
        assertThat(mixed.pickup().noPromiseFallbackSeconds()).isNull();
        assertThat(mixed.dineIn().noPromiseFallbackSeconds()).isNull();
        assertThat(mixed.violations()).isEmpty();
    }

    @Test
    void theOneMinuteFloorAppliesOnlyToAFallbackThatIsPresent() {
        assertThat(new OrderLatenessDocument(
                                new ModeThresholds(null, 0, null),
                                new ModeThresholds(null, 0, null),
                                new ModeThresholds(null, 0, 30))
                        .violations())
                .singleElement()
                .satisfies(v -> assertThat(v).contains("DINE_IN noPromiseFallbackSeconds"));
    }

    // ---------------------------------------------------------------- bounds

    @Test
    void aWellFormedDocumentHasNoViolations() {
        assertThat(new OrderLatenessDocument(
                                new ModeThresholds(null, 0, 60),
                                new ModeThresholds(86_400, 86_400, 86_400),
                                new ModeThresholds(0, 30, 2700))
                        .violations())
                .isEmpty();
    }

    @Test
    void everyBoundIsNamedWithItsModeSoTheEditorCanSayWhichBoxIsWrong() {
        OrderLatenessDocument document = new OrderLatenessDocument(
                new ModeThresholds(-60, 0, 2700), // negative window
                new ModeThresholds(90, 86_401, 2700), // 90s is not whole minutes; grace over a day
                new ModeThresholds(300, 0, 30)); // fallback under a minute

        assertThat(document.violations())
                .hasSize(4)
                .anySatisfy(v -> assertThat(v).contains("DELIVERY atRiskBeforeSeconds"))
                .anySatisfy(v -> assertThat(v).contains("PICKUP atRiskBeforeSeconds"))
                .anySatisfy(v -> assertThat(v).contains("PICKUP lateAfterSeconds"))
                .anySatisfy(v -> assertThat(v).contains("DINE_IN noPromiseFallbackSeconds"));
    }

    @Test
    void windowsAreWholeMinutesButTheGraceIsAnyNumberOfSeconds() {
        assertThat(new OrderLatenessDocument(
                                new ModeThresholds(300, 45, 2700),
                                new ModeThresholds(300, 45, 2700),
                                new ModeThresholds(300, 45, 2700))
                        .violations())
                .as("45 seconds of grace is fine")
                .isEmpty();
        assertThat(new OrderLatenessDocument(
                                new ModeThresholds(null, 0, 2730),
                                new ModeThresholds(null, 0, 2700),
                                new ModeThresholds(null, 0, 2700))
                        .violations())
                .as("a 45.5-minute fallback is not a whole number of minutes")
                .hasSize(1);
    }
}
