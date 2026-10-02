package uz.horecaos.platform.iam.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.protection.ClassificationScanner;
import uz.horecaos.platform.tenancy.web.LocationContactPersonController;

/**
 * ADR 0029 over ADR 0031, at the seam {@link ClassificationScanner} cannot see through.
 *
 * <p>An {@code @Idempotent} response is stored for a day, encrypted only when the scanner
 * finds classified data in its declared type. The scanner reads record components, and a
 * {@code List<Record>} component is not a record, so a response that holds a third party's
 * name in a list scanned as clean and was stored in plaintext. The HTTP log test caught the
 * emergency contacts doing exactly that; this holds the same seam shut for every response
 * record these controllers declare.
 */
class StaffResponseClassificationTests {

    @Test
    @DisplayName("a response that lists records carrying personal data is itself classified as personal")
    void aListOfPersonalRecordsIsPersonal() {
        List<String> unclassified = new ArrayList<>();
        for (Class<?> controller : List.of(StaffMemberController.class, LocationContactPersonController.class)) {
            for (Class<?> type : controller.getDeclaredClasses()) {
                if (!type.isRecord() || !type.getSimpleName().endsWith("Response")) {
                    continue;
                }
                for (RecordComponent component : type.getRecordComponents()) {
                    Class<?> element = listElement(component.getGenericType());
                    if (element != null
                            && !ClassificationScanner.scan(element, "e").isEmpty()
                            && ClassificationScanner.scan(type, "r").isEmpty()) {
                        unclassified.add(type.getSimpleName() + "." + component.getName());
                    }
                }
            }
        }

        assertThat(unclassified)
                .as("the response is stored in the idempotency table; unclassified it is stored in clear")
                .isEmpty();
    }

    @Test
    @DisplayName("the two list responses of this wave are classified, so their stored replies are encrypted")
    void theNamedListResponsesAreClassified() {
        assertThat(ClassificationScanner.scan(StaffMemberController.EmergencyContactsResponse.class, "r"))
                .isNotEmpty();
        assertThat(ClassificationScanner.scan(LocationContactPersonController.ContactPersonsResponse.class, "r"))
                .isNotEmpty();
    }

    private static @Nullable Class<?> listElement(Type type) {
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == List.class
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> element
                && element.isRecord()) {
            return element;
        }
        return null;
    }
}
