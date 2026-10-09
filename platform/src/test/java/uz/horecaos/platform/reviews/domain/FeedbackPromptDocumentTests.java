package uz.horecaos.platform.reviews.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.reviews.domain.FeedbackPromptDocument.PromptRow;
import uz.horecaos.platform.reviews.domain.FeedbackPromptDocument.SendWindow;

/**
 * When a customer is asked to rate an order (ADR 0159, Decision 4): the most specific row wins, the send
 * window is in the branch's own time zone, and a policy can only make a prompt later than today's
 * immediate send, never earlier.
 *
 * <p>Tashkent is UTC+5 all year, so the instants below are written in UTC and the local time is stated
 * beside them; a test that only passes in UTC would prove nothing about a window.
 */
class FeedbackPromptDocumentTests {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    @Test
    @DisplayName("nothing authored: Telegram on, SMS, email and push off, 30 minutes after, given up after 6 hours")
    void theBuiltInDefault() {
        FeedbackPromptDocument defaults = FeedbackPromptDocument.builtInDefault();

        PromptRow telegram = defaults.rowFor("DELIVERY", "TELEGRAM");
        assertThat(telegram.enabled()).isTrue();
        assertThat(telegram.delayMinutes()).isEqualTo(30);
        assertThat(telegram.giveUpAfterHours()).isEqualTo(6);
        for (String channel : List.of("SMS", "EMAIL", "PUSH")) {
            assertThat(defaults.rowFor("PICKUP", channel).enabled())
                    .as("%s is off until the tenant enables it", channel)
                    .isFalse();
        }
        assertThat(defaults.inlineWindowDays()).isEqualTo(7);
        assertThat(defaults.sendWindow()).isEqualTo(new SendWindow("09:00", "21:00"));
        assertThat(defaults.violations()).isEmpty();
    }

    @Test
    @DisplayName("an unknown channel is never prompted")
    void anUnknownChannelIsNeverPrompted() {
        assertThat(FeedbackPromptDocument.builtInDefault()
                        .rowFor("DELIVERY", "CARRIER_PIGEON")
                        .enabled())
                .isFalse();
    }

    @Test
    @DisplayName(
            "the most specific row wins: the order type outranks the channel, and a miss falls to the built-in default")
    void theMostSpecificRowWins() {
        FeedbackPromptDocument document = new FeedbackPromptDocument(
                null,
                7,
                List.of(
                        new PromptRow("*", "SMS", true, 5, 6),
                        new PromptRow("DINE_IN", "*", false, 0, 1),
                        new PromptRow("DELIVERY", "SMS", true, 60, 12),
                        new PromptRow("*", "*", true, 15, 24)));

        assertThat(document.rowFor("DELIVERY", "SMS").delayMinutes())
                .as("exact order type and channel")
                .isEqualTo(60);
        assertThat(document.rowFor("PICKUP", "SMS").delayMinutes())
                .as("any order type, exact channel")
                .isEqualTo(5);
        assertThat(document.rowFor("DINE_IN", "SMS").enabled())
                .as("a tenant that says never ask a dine-in guest means it on every channel, SMS included")
                .isFalse();
        assertThat(document.rowFor("PICKUP", "TELEGRAM").delayMinutes())
                .as("only the any-any row matches")
                .isEqualTo(15);

        FeedbackPromptDocument onlySms =
                new FeedbackPromptDocument(null, 7, List.of(new PromptRow("*", "SMS", true, 0, 6)));
        assertThat(onlySms.rowFor("DELIVERY", "TELEGRAM").delayMinutes())
                .as("no authored row for the channel: the built-in default answers")
                .isEqualTo(30);
        assertThat(onlySms.rowFor("DELIVERY", "TELEGRAM").enabled()).isTrue();
    }

    @Test
    @DisplayName("a prompt inside the window goes at completion plus the delay")
    void insideTheWindowGoesWhenDelayed() {
        FeedbackPromptDocument document = FeedbackPromptDocument.builtInDefault();
        PromptRow row = document.rowFor("DELIVERY", "TELEGRAM");
        // 14:00 in Tashkent.
        Instant completed = Instant.parse("2026-10-08T09:00:00Z");

        Instant scheduled = document.scheduledAt(row, completed, TASHKENT);

        assertThat(scheduled).isEqualTo(Instant.parse("2026-10-08T09:30:00Z"));
        assertThat(document.expiresAt(row, scheduled)).isEqualTo(Instant.parse("2026-10-08T15:30:00Z"));
    }

    @Test
    @DisplayName(
            "completed at 22:00 local, the prompt waits for 09:00 the next morning, and its give-up clock starts then")
    void aLateNightPromptWaitsForTheMorning() {
        FeedbackPromptDocument document = FeedbackPromptDocument.builtInDefault();
        PromptRow row = document.rowFor("DELIVERY", "TELEGRAM");
        // 22:00 in Tashkent.
        Instant completed = Instant.parse("2026-10-08T17:00:00Z");

        Instant scheduled = document.scheduledAt(row, completed, TASHKENT);

        assertThat(scheduled).as("09:00 on the 9th, local").isEqualTo(Instant.parse("2026-10-09T04:00:00Z"));
        assertThat(document.expiresAt(row, scheduled))
                .as("six hours from when it became sendable, not from completion, or it would expire before it opened")
                .isEqualTo(Instant.parse("2026-10-09T10:00:00Z"));
    }

    @Test
    @DisplayName("completed at 03:00 local, the prompt waits for 09:00 the same morning, not the next")
    void anEarlyMorningPromptWaitsForTheSameMorning() {
        FeedbackPromptDocument document = FeedbackPromptDocument.builtInDefault();
        PromptRow row = document.rowFor("DELIVERY", "TELEGRAM");
        // 03:00 in Tashkent.
        Instant completed = Instant.parse("2026-10-07T22:00:00Z");

        assertThat(document.scheduledAt(row, completed, TASHKENT)).isEqualTo(Instant.parse("2026-10-08T04:00:00Z"));
    }

    @Test
    @DisplayName("the delay can carry a prompt out of the window, and the window then moves it on")
    void theDelayCanLeaveTheWindow() {
        FeedbackPromptDocument document = FeedbackPromptDocument.builtInDefault();
        PromptRow row = document.rowFor("DELIVERY", "TELEGRAM");
        // 20:50 local: completion is in the window, completion + 30 minutes (21:20) is not.
        Instant completed = Instant.parse("2026-10-08T15:50:00Z");

        assertThat(document.scheduledAt(row, completed, TASHKENT)).isEqualTo(Instant.parse("2026-10-09T04:00:00Z"));
    }

    @Test
    @DisplayName("the window's closing minute is outside it, its opening minute inside")
    void theWindowIsHalfOpen() {
        FeedbackPromptDocument document = new FeedbackPromptDocument(
                new SendWindow("09:00", "21:00"), 7, List.of(new PromptRow("*", "*", true, 0, 6)));
        PromptRow row = document.rowFor("DELIVERY", "TELEGRAM");

        assertThat(document.scheduledAt(row, Instant.parse("2026-10-08T04:00:00Z"), TASHKENT))
                .as("09:00 sharp is inside")
                .isEqualTo(Instant.parse("2026-10-08T04:00:00Z"));
        assertThat(document.scheduledAt(row, Instant.parse("2026-10-08T16:00:00Z"), TASHKENT))
                .as("21:00 sharp is outside")
                .isEqualTo(Instant.parse("2026-10-09T04:00:00Z"));
    }

    @Test
    @DisplayName("no window means any hour: completion plus the delay, whatever o'clock it is")
    void noWindowMeansAnyHour() {
        FeedbackPromptDocument document =
                new FeedbackPromptDocument(null, 7, List.of(new PromptRow("*", "SMS", true, 0, 6)));
        PromptRow row = document.rowFor("DELIVERY", "SMS");
        Instant threeInTheMorning = Instant.parse("2026-10-07T22:00:00Z");

        assertThat(document.scheduledAt(row, threeInTheMorning, TASHKENT))
                .as("the preserved tenant's SMS goes at once, as it always did")
                .isEqualTo(threeInTheMorning);
        assertThat(document.expiresAt(row, threeInTheMorning)).isEqualTo(threeInTheMorning.plusSeconds(6 * 3600));
    }

    @Test
    @DisplayName("a document that breaks a bound is refused with a sentence for each break")
    void violations() {
        FeedbackPromptDocument bad = new FeedbackPromptDocument(
                new SendWindow("21:00", "09:00"),
                0,
                List.of(
                        new PromptRow("TAKEAWAY", "SMS", true, 5, 6),
                        new PromptRow("*", "FAX", true, 5, 6),
                        new PromptRow("*", "*", true, 1441, 6),
                        new PromptRow("*", "*", true, 5, 6),
                        new PromptRow("DELIVERY", "SMS", true, 5, 0),
                        new PromptRow("PICKUP", "SMS", true, 5, 73)));

        assertThat(bad.violations())
                .anySatisfy(v -> assertThat(v).contains("sendWindow must open before it closes"))
                .anySatisfy(v -> assertThat(v).contains("inlineWindowDays must be between"))
                .anySatisfy(
                        v -> assertThat(v).contains("fulfillmentMode must be").contains("TAKEAWAY"))
                .anySatisfy(v -> assertThat(v).contains("channel must be").contains("FAX"))
                .anySatisfy(v -> assertThat(v).contains("delayMinutes must be between 0 and 1440"))
                .anySatisfy(v -> assertThat(v).contains("two rows name the same order type and channel"))
                .anySatisfy(v -> assertThat(v).contains("giveUpAfterHours must be between 1 and 72"));

        assertThat(new FeedbackPromptDocument(new SendWindow("9am", "9pm"), 7, List.of()).violations())
                .anySatisfy(v -> assertThat(v).contains("HH:mm"));
    }
}
