package uz.horecaos.platform.migration.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a transformation made of one legacy row (ADR 0024).
 *
 * <p>Three outcomes and no fourth. A row becomes a command, or it is quarantined
 * with a reason code, or it is deliberately not migrated — and that last one is
 * separate from quarantine on purpose. A legacy customer's {@code archive_} twin
 * is not a person and not a broken row; skipping it silently would leave it
 * indistinguishable from a row nobody saw, and quarantining it would fill the
 * backlog that gates cutover with rows there is nothing to decide about.
 *
 * @param <T> the target command
 */
public sealed interface TransformationOutcome<T> {

    /** Ready to import. */
    record Transformed<T>(T command) implements TransformationOutcome<T> {
        public Transformed {
            Objects.requireNonNull(command, "A transformed row carries its command");
        }
    }

    /**
     * Not migratable, with a reason from the approved vocabulary.
     *
     * @param reasonCode  upper-case code, never free text, for the reason
     *                    {@code QuarantineCommand} gives: a sentence somebody
     *                    typed is untranslatable and eventually contains the row
     *                    that failed
     * @param evidenceReference a pointer into the protected evidence store, or
     *                    null while the diagnosis is only a reason code. Never the
     *                    evidence itself (ADR 0029)
     */
    record Quarantined<T>(String reasonCode, String evidenceReference)
            implements TransformationOutcome<T> {

        private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

        public Quarantined {
            if (reasonCode == null || !CODE.matcher(reasonCode).matches()) {
                throw new IllegalArgumentException(
                        "A quarantine reason is an upper-case code from the approved vocabulary");
            }
        }
    }

    /**
     * Seen, understood, and deliberately not imported.
     *
     * @param reasonCode why, so a count of skips is readable months later
     */
    record NotMigrated<T>(String reasonCode) implements TransformationOutcome<T> {

        private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

        public NotMigrated {
            if (reasonCode == null || !CODE.matcher(reasonCode).matches()) {
                throw new IllegalArgumentException("A skip reason is an upper-case code");
            }
        }
    }

    static <T> TransformationOutcome<T> of(T command) {
        return new Transformed<>(command);
    }

    static <T> TransformationOutcome<T> quarantine(String reasonCode, String evidenceReference) {
        return new Quarantined<>(reasonCode, evidenceReference);
    }

    static <T> TransformationOutcome<T> notMigrated(String reasonCode) {
        return new NotMigrated<>(reasonCode);
    }
}
