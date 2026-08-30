package uz.qoida.platform.iam.api.protection;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds classified data reachable from a type (ADR 0029).
 *
 * <p>Two sources, deliberately combined. A {@link Classified} annotation is
 * authoritative and survives renaming. The name heuristic catches fields nobody
 * remembered to annotate, which is the common case and the reason the interim
 * checks in ADR 0027 and ADR 0032 were name-based to begin with.
 *
 * <p>False positives are the intended direction. A wrongly flagged field costs
 * one annotation or one reviewed exception; a wrongly permitted one puts a phone
 * number on a Kafka topic.
 */
public final class ClassificationScanner {

    private static final Set<String> PROTECTED_TERMS = Set.of(
            "phone", "email", "passport", "birth", "dateofbirth",
            "firstname", "lastname", "middlename", "fullname", "personname",
            "address", "latitude", "longitude", "coordinate", "geolocation",
            "password", "secret", "token", "credential", "apikey",
            "cardnumber", "pan", "cvv", "iban", "ssn", "jshir", "tin",
            "note", "comment", "instructions", "devicefingerprint");

    private ClassificationScanner() {
    }

    /** Every classified path reachable from {@code type}, empty when it is clean. */
    public static List<Finding> scan(Class<?> type, String path) {
        List<Finding> findings = new ArrayList<>();
        scan(type, path, new LinkedHashSet<>(), findings);
        return findings;
    }

    public static boolean isProtectedName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return PROTECTED_TERMS.stream().anyMatch(normalized::contains);
    }

    private static void scan(Class<?> type, String path, Set<Class<?>> visited, List<Finding> findings) {
        if (type == null || !type.isRecord() || !visited.add(type)) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            String componentPath = path + "." + component.getName();

            Classified declared = declaredOn(component);
            if (declared != null) {
                if (declared.value().requiresEncryption()) {
                    findings.add(new Finding(componentPath, declared.value(), Source.DECLARED));
                }
                continue;
            }
            if (isProtectedName(component.getName())) {
                findings.add(new Finding(componentPath, DataClass.PERSONAL, Source.NAME_HEURISTIC));
            }
            scan(component.getType(), componentPath, visited, findings);
        }
    }

    private static Classified declaredOn(RecordComponent component) {
        Classified onComponent = component.getAnnotation(Classified.class);
        if (onComponent != null) {
            return onComponent;
        }
        return component.getType().getAnnotation(Classified.class);
    }

    /** Where a classification came from, so a reviewer can tell a guess from a declaration. */
    public enum Source {
        DECLARED,
        NAME_HEURISTIC
    }

    public record Finding(String path, DataClass dataClass, Source source) {

        @Override
        public String toString() {
            return "%s (%s, %s)".formatted(path, dataClass, source);
        }
    }
}
