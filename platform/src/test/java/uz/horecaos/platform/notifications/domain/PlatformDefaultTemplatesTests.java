package uz.horecaos.platform.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.notifications.application.OrderNotificationTrigger;

/**
 * The platform's own wording (ADR 0184, ADR 0020's last resolution layer), held to the rules that make
 * it safe to send to a customer nobody wrote to.
 */
class PlatformDefaultTemplatesTests {

    private static final Map<String, String> SAMPLE =
            Map.of("orderNumber", "A-17", "amount", "85000", "currency", "UZS");

    @Test
    @DisplayName("the keys are the order milestones a customer is told, and the rating prompt is not one of them")
    void theKeysAreTheMilestones() {
        assertThat(PlatformDefaultTemplates.all())
                .extracting(PlatformDefaultTemplates.Entry::templateKey)
                .containsExactly(
                        OrderNotificationTrigger.ORDER_CONFIRMED,
                        OrderNotificationTrigger.ORDER_PREPARING,
                        OrderNotificationTrigger.ORDER_READY,
                        OrderNotificationTrigger.ORDER_FULFILLING,
                        OrderNotificationTrigger.ORDER_REJECTED,
                        OrderNotificationTrigger.ORDER_CANCELLED,
                        OrderNotificationTrigger.ORDER_EXPIRED,
                        OrderNotificationTrigger.ORDER_PAYMENT_LAPSED);
        assertThat(PlatformDefaultTemplates.forKey(OrderNotificationTrigger.ORDER_COMPLETED))
                .as("ADR 0075: the rating prompt is opt-in by a tenant activating a template, and a default would"
                        + " switch it on for every tenant that never chose it")
                .isEmpty();
        assertThat(PlatformDefaultTemplates.forKey(OrderNotificationTrigger.ORDER_AWAITING_APPROVAL))
                .as("an operations alert is never a customer sentence")
                .isEmpty();
    }

    @Test
    @DisplayName("activating a language in the messages tier without writing the platform's sentences fails here")
    void everyLiveLanguageHasEverySentence() {
        for (MessageLocale locale : MessageLocale.all()) {
            for (PlatformDefaultTemplates.Entry entry : PlatformDefaultTemplates.all()) {
                assertThat(entry.body(locale.tag()))
                        .as(
                                "%s has no platform wording in %s: people write translations, a machine fallback is forbidden"
                                        + " (ADR 0149)",
                                entry.templateKey(), locale.tag())
                        .isPresent();
            }
        }
    }

    @Test
    @DisplayName("the sentences use only the order number, the total and its currency")
    void theSentencesUseOnlyWhatEveryOrderHas() {
        for (PlatformDefaultTemplates.Entry entry : PlatformDefaultTemplates.all()) {
            assertThat(entry.variables())
                    .as(entry.templateKey())
                    .isSubsetOf(Set.of("orderNumber", "amount", "currency"));
            assertThat(entry.variables()).as(entry.templateKey()).contains("orderNumber");
        }
    }

    @Test
    @DisplayName("every sentence renders in every language with those values and leaves no placeholder behind")
    void everySentenceRenders() {
        for (PlatformDefaultTemplates.Entry entry : PlatformDefaultTemplates.all()) {
            for (Map.Entry<String, String> body : entry.bodies().entrySet()) {
                String rendered = Objects.requireNonNull(TemplateRenderer.render(body.getValue(), SAMPLE));
                assertThat(rendered)
                        .as("%s/%s", entry.templateKey(), body.getKey())
                        .doesNotContain("{{");
                assertThat(rendered).contains("A-17");
            }
        }
    }

    @Test
    @DisplayName("Uzbek and English are GSM-7 clean and one SMS segment, so a default costs one message")
    void uzbekAndEnglishAreOneSegment() {
        for (PlatformDefaultTemplates.Entry entry : PlatformDefaultTemplates.all()) {
            for (String tag : List.of("uz-Latn", "en")) {
                String rendered = Objects.requireNonNull(
                        TemplateRenderer.render(entry.body(tag).orElseThrow(), SAMPLE));
                assertThat(rendered.chars().allMatch(c -> c < 128))
                        .as("%s/%s is outside ASCII, which moves the whole message to UCS-2", entry.templateKey(), tag)
                        .isTrue();
                assertThat(rendered.length())
                        .as("%s/%s", entry.templateKey(), tag)
                        .isLessThanOrEqualTo(160);
            }
        }
    }

    @Test
    @DisplayName("a required message names no consent purpose; an optional one names the platform's")
    void classesAndPurposesAgree() {
        for (PlatformDefaultTemplates.Entry entry : PlatformDefaultTemplates.all()) {
            if (entry.notificationClass() == NotificationClass.TRANSACTIONAL_OPTIONAL) {
                assertThat(entry.consentPurpose()).as(entry.templateKey()).isEqualTo("ORDER_PROGRESS_UPDATES");
            } else {
                assertThat(entry.notificationClass())
                        .as(entry.templateKey())
                        .isEqualTo(NotificationClass.TRANSACTIONAL_REQUIRED);
                assertThat(entry.consentPurpose()).as(entry.templateKey()).isNull();
            }
        }
        assertThat(PlatformDefaultTemplates.forKey(OrderNotificationTrigger.ORDER_PREPARING))
                .get()
                .extracting(PlatformDefaultTemplates.Entry::notificationClass)
                .isEqualTo(NotificationClass.TRANSACTIONAL_OPTIONAL);
        assertThat(PlatformDefaultTemplates.forKey(OrderNotificationTrigger.ORDER_CONFIRMED))
                .get()
                .extracting(PlatformDefaultTemplates.Entry::notificationClass)
                .isEqualTo(NotificationClass.TRANSACTIONAL_REQUIRED);
    }

    @Test
    @DisplayName("it is offered on the wired customer channels only")
    void offeredOnTheWiredCustomerChannels() {
        assertThat(PlatformDefaultTemplates.offeredOn(NotificationChannel.SMS)).isTrue();
        assertThat(PlatformDefaultTemplates.offeredOn(NotificationChannel.TELEGRAM))
                .isTrue();
        assertThat(PlatformDefaultTemplates.offeredOn(NotificationChannel.EMAIL))
                .isFalse();
        assertThat(PlatformDefaultTemplates.offeredOn(NotificationChannel.PUSH)).isFalse();
    }
}
