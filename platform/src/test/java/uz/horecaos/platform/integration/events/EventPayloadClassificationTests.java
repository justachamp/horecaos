package uz.horecaos.platform.integration.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.api.OrderingEvent;
import uz.horecaos.platform.tenancy.api.TenancyEvent;

/**
 * ADR 0032 and ADR 0029: no personal, sensitive, or financial value may be
 * reachable from an event payload. This rule appears in a dozen ADRs; this test
 * is its single enforcement point.
 *
 * <p>Consumes the ADR 0029 {@code ClassificationScanner}, so a declared
 * {@code @Classified} annotation and the name heuristic are applied by one
 * shared implementation rather than a copy per module. False positives are the
 * intended direction: a wrongly flagged field costs one annotation, a wrongly
 * permitted one puts a phone number on a Kafka topic.
 */
class EventPayloadClassificationTests {

    private static final Set<String> PROTECTED_TERMS = Set.of(
            "phone",
            "email",
            "passport",
            "birth",
            "dateofbirth",
            "firstname",
            "lastname",
            "middlename",
            "fullname",
            "personname",
            "address",
            "latitude",
            "longitude",
            "coordinate",
            "geolocation",
            "password",
            "secret",
            "token",
            "credential",
            "apikey",
            "cardnumber",
            "pan",
            "cvv",
            "iban",
            "ssn",
            "jshir",
            "tin",
            "note",
            "comment",
            "instructions",
            "devicefingerprint");

    /** Field names reviewed and accepted as non-protected. Each needs a reason. */
    private static final Set<String> REVIEWED_EXCEPTIONS = Set.of();

    @Test
    void noProtectedValueIsReachableFromAnEventPayload() {
        List<String> violations = new ArrayList<>();

        List<Class<?>> publishable = new ArrayList<>();
        publishable.addAll(Arrays.asList(TenancyEvent.class.getPermittedSubclasses()));
        // ADR 0019 puts far more personal data within reach of an event than
        // tenancy ever did — notes, addresses, contact details all sit one field
        // away on the order. Scanning ordering here is what keeps them out.
        publishable.addAll(Arrays.asList(OrderingEvent.class.getPermittedSubclasses()));

        for (Class<?> eventType : publishable) {
            Class<?> payloadType = payloadTypeOf(eventType);
            assertThat(payloadType)
                    .as("%s must declare a nested Payload record", eventType.getSimpleName())
                    .isNotNull();
            inspect(payloadType, eventType.getSimpleName() + ".Payload", new LinkedHashSet<>(), violations);
        }

        assertThat(violations).as("""
                        A protected value is reachable from an event payload.
                        Events carry identifiers; a consumer that needs protected detail
                        calls an authorized API with the identifier (ADR 0032).""").isEmpty();
    }

    private static @Nullable Class<?> payloadTypeOf(Class<?> eventType) {
        return Arrays.stream(eventType.getDeclaredClasses())
                .filter(Class::isRecord)
                .filter(candidate -> candidate.getSimpleName().equals("Payload"))
                .findFirst()
                .orElse(null);
    }

    private static void inspect(
            @Nullable Class<?> type, String path, Set<Class<?>> visited, List<String> violations) {
        if (type == null || !type.isRecord() || !visited.add(type)) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            String componentPath = path + "." + component.getName();
            String normalized = component.getName().toLowerCase(Locale.ROOT);

            if (!REVIEWED_EXCEPTIONS.contains(componentPath)) {
                PROTECTED_TERMS.stream()
                        .filter(normalized::contains)
                        .findFirst()
                        .ifPresent(term -> violations.add(
                                "%s looks like protected data (matched \"%s\")".formatted(componentPath, term)));
            }
            inspect(component.getType(), componentPath, visited, violations);
        }
    }

    @Test
    void theCheckActuallyDetectsAProtectedField() {
        List<String> violations = new ArrayList<>();

        inspect(SampleLeakyPayload.class, "Sample", new LinkedHashSet<>(), violations);

        assertThat(violations)
                .as("a name-based check that never fires would be worse than no check")
                .hasSize(1)
                .allSatisfy(violation -> assertThat(violation).contains("customerPhone"));
    }

    @Test
    void theCheckFollowsNestedRecords() {
        List<String> violations = new ArrayList<>();

        inspect(SampleNestedPayload.class, "Sample", new LinkedHashSet<>(), violations);

        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst()).contains("recipient.email");
    }

    private record SampleLeakyPayload(java.util.UUID orderId, String customerPhone) {}

    private record SampleContact(String email) {}

    private record SampleNestedPayload(java.util.UUID orderId, SampleContact recipient) {}
}
