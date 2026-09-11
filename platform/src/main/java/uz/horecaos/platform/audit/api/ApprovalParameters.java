package uz.horecaos.platform.audit.api;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The parameters hash an ADR 0027 approval is bound to, derived from the command
 * rather than transcribed from it.
 *
 * <p><strong>Why this exists rather than a {@code String.join} at each call
 * site.</strong> Five call sites each hand-listed the fields they believed
 * mattered, and five of them were wrong in the same direction — the direction a
 * hand-written list always fails in. A refund's hash covered
 * {@code order|type|amount|reasonCode} and not the attestation, so one signature
 * covered both "500 000 returned through CLICK, reference CLICK-88213, by the
 * gateway" and "500 000 handed over in cash by me, no reference": two
 * irreconcilable claims about where the money went, indistinguishable on the
 * checker's console. A future discount's hash covered the product
 * {@code perUse × uses} and neither factor, so a one-use fixed 500 000 and a
 * ten-use capped percentage over everything for a year shared a hash. A courier
 * penalty's hash omitted the location whose P&amp;L bears it. A payout's hash
 * omitted the method, which is the rail the money leaves on.
 *
 * <p>Every one of those is the same defect: a field was added to a command and
 * nobody remembered the hash. So the hash is no longer written by hand. It is
 * taken from the command record's own components, in declaration order, and a
 * component that is <em>not</em> to be covered has to be named — which makes the
 * safe direction the default. Add a component and it enters the hash by itself;
 * add one that must not be covered and you write it down where the next reader
 * can tell your decision from your omission.
 *
 * <p>A component whose type this class cannot canonicalise is rejected outright
 * rather than hashed through {@code toString}. That is the second half of the
 * guard: adding a {@code List<Line>} to a command fails loudly at the first call
 * instead of hashing an identity string that differs on every submission and
 * quietly makes the approval unusable.
 *
 * <p>Floating point is not supported and never will be: money here is integer
 * minor units (ADR 0018), and a hash over a {@code double} is a hash that depends
 * on how a number was parsed.
 *
 * <p>ADR 0029 is not at risk here. The digest is one-way and the material never
 * leaves this class; what reaches the database is 64 hex characters.
 */
public final class ApprovalParameters {

    private ApprovalParameters() {}

    /**
     * Hashes the components of a command record.
     *
     * <p>{@link Builder#excluding(String...)} must be called before
     * {@link Builder#hash()}, with no arguments when nothing is excluded. An
     * empty call is a statement that every component is covered; forgetting to
     * call it is not the same statement, and the two must not look alike.
     */
    public static Builder of(Record command) {
        return new Builder(Objects.requireNonNull(command, "A command is required"));
    }

    /** Hashes only explicitly supplied segments, for a call site with no command record. */
    public static Builder none() {
        return new Builder(null);
    }

    /**
     * The component names a hash over this record type would cover.
     *
     * <p>For the drift test: a test asserts this against a written-down list, so
     * a command that gains a component fails a test that names the component,
     * rather than silently changing a hash nobody re-derived. The component is
     * already covered by then — this exists to make the change visible, not to
     * be the thing that makes it safe.
     */
    public static List<String> coveredComponents(Class<? extends Record> type, String... excluded) {
        Set<String> skip = validatedExclusions(type, excluded);
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(name -> !skip.contains(name))
                .toList();
    }

    private static Set<String> validatedExclusions(Class<? extends Record> type, String... excluded) {
        Set<String> declared = Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> skip = new LinkedHashSet<>();
        for (String name : excluded) {
            if (!declared.contains(name)) {
                // A rename that left the exclusion behind would otherwise widen
                // the hash silently, which is the harmless direction, or leave a
                // renamed field excluded under its old name, which is not.
                throw new IllegalArgumentException("%s has no component named %s; the exclusions are %s"
                        .formatted(type.getSimpleName(), name, declared));
            }
            skip.add(name);
        }
        return skip;
    }

    /**
     * A hash and the projection of it a checker is shown, taken from one pass
     * over the same components.
     *
     * <p>The two are returned together rather than separately because that is
     * the whole guarantee: what the approvals console renders is derived from
     * the components the signature covers, so a maker cannot get one thing
     * signed and execute another.
     *
     * <p>Two things the signature covers are deliberately not in the subject: a
     * component named to {@link Builder#withholding(String...)}, which is prose
     * ADR 0029 keeps off a console, and a covered component whose value is null,
     * whose absence from the subject reads as the absence it is. Everything else
     * covered is shown, and a name that would be hashed twice and shown once is
     * refused outright.
     */
    public record Signed(String hash, ApprovalSubject subject) {}

    /** Accumulates the material and hashes it. */
    public static final class Builder {

        private final @Nullable Record command;
        private final Map<String, Object> extra = new LinkedHashMap<>();
        private @Nullable Set<String> excluded;
        private @Nullable Set<String> withheld;
        private @Nullable String subjectTenantComponent;

        private Builder(@Nullable Record command) {
            this.command = command;
        }

        /**
         * Names the components deliberately left out of the hash.
         *
         * <p>Each name must be a real component of the command, so a rename
         * fails here rather than widening or narrowing the hash in silence.
         */
        public Builder excluding(String... componentNames) {
            if (command == null) {
                throw new IllegalStateException("There is no command to exclude components from");
            }
            excluded = validatedExclusions(recordType(command), componentNames);
            return this;
        }

        /**
         * Adds a segment that is not a component of the command.
         *
         * <p>For the part of the intended action the command does not carry — the
         * remedy type, which is the entry point rather than a field.
         *
         * <p>Refuses a name the command already declares. {@link #walk} appends
         * the command's components and then the extras, so a collision hashes two
         * segments while {@link #show} writes both under one key and keeps the
         * later one: the signature would cover two values and the console would
         * render one, which is the single divergence this class's contract does
         * not otherwise close. The call sites sit one field away from it —
         * {@code proposeRefund} and {@code proposeBonusGrant} both add a {@code
         * moneyKind} their sibling {@code AdjustmentCommand} declares as a
         * component.
         *
         * <p>Refuses a re-supply on {@code containsKey} rather than on the old
         * {@code put(...) != null}, which could not see a first value of null: a
         * nullable segment supplied twice collapsed into one, silently, and
         * {@code PlatformGrantService} pushes a nullable role code and validity
         * through here.
         */
        public Builder and(String name, @Nullable Object value) {
            Objects.requireNonNull(name, "A segment name is required");
            if (extra.containsKey(name)) {
                throw new IllegalArgumentException("Segment " + name + " was supplied twice");
            }
            if (command != null && declares(recordType(command), name)) {
                throw new IllegalArgumentException(
                        ("%s already has a component named %s; an added segment that shadows one is hashed "
                                        + "twice and shown once. The components are %s")
                                .formatted(
                                        recordType(command).getSimpleName(),
                                        name,
                                        coveredComponents(recordType(command))));
            }
            extra.put(name, value);
            return this;
        }

        /**
         * Names the covered components a checker must <em>not</em> be shown.
         *
         * <p>The mirror image of {@link #excluding(String...)}, one step later:
         * these stay inside the hash and stay off every console. What belongs
         * here is prose — the maker's own {@code reason}, which is a sentence a
         * person typed about a named customer and which ADR 0029 keeps out of
         * logs, metrics and consoles alike.
         *
         * <p>Required before {@link #sign()}, with no arguments when every
         * covered component may be shown, for the reason
         * {@link #excluding(String...)} is required before {@link #hash()}:
         * silence about what is withheld is how prose reaches a screen that was
         * never allowed to hold it.
         */
        public Builder withholding(String... names) {
            withheld = new LinkedHashSet<>(Arrays.asList(names));
            return this;
        }

        /**
         * Names the covered component holding the tenant whose account this
         * decision concerns.
         *
         * <p>It has to be a component the hash covers: a subject the signature
         * does not bind is a label, not evidence.
         */
        public Builder about(String componentName) {
            subjectTenantComponent = Objects.requireNonNull(componentName, "A subject component name is required");
            return this;
        }

        /** The lower-case SHA-256 hex an {@link ApprovalRequestCommand} takes. */
        public String hash() {
            return walk(null);
        }

        /**
         * The hash and what the checker is shown, from one pass over the same
         * components.
         *
         * <p>Requires {@link #excluding(String...)} and
         * {@link #withholding(String...)} to have been called, and
         * {@link #about(String)} where the decision concerns one tenant.
         */
        public Signed sign() {
            if (withheld == null) {
                throw new IllegalStateException("Call withholding(...) before sign(), with no arguments when every "
                        + "covered component may be shown to the checker. Silence about what is "
                        + "withheld is how a maker's prose reaches a console ADR 0029 keeps it off.");
            }
            Map<String, String> display = new LinkedHashMap<>();
            String hash = walk(display);
            for (String name : withheld) {
                if (!isKnownSegment(name)) {
                    throw new IllegalArgumentException(
                            "Nothing named %s is hashed here, so withholding it says nothing; the segments are %s"
                                    .formatted(name, knownSegments()));
                }
            }
            return new Signed(hash, new ApprovalSubject(subjectTenantId(), display));
        }

        private @Nullable UUID subjectTenantId() {
            if (subjectTenantComponent == null) {
                return null;
            }
            Object value = segmentValue(subjectTenantComponent);
            if (value == null) {
                return null;
            }
            if (!(value instanceof UUID tenantId)) {
                throw new IllegalArgumentException("The subject component %s is a %s; a subject tenant is a UUID"
                        .formatted(subjectTenantComponent, value.getClass().getName()));
            }
            return tenantId;
        }

        /**
         * One pass over the material, appending the hash segments and — when
         * {@code display} is given — the projection of them a checker is shown.
         *
         * <p>One loop on purpose. Two would be two places for a component to be
         * covered by the hash and absent from the console, or the other way
         * round, which is the exact failure both halves exist to prevent.
         */
        private String walk(@Nullable Map<String, String> display) {
            StringBuilder material = new StringBuilder();
            if (command != null) {
                if (excluded == null) {
                    throw new IllegalStateException("Call excluding(...) before hash(), with no arguments when every "
                            + "component of " + recordType(command).getSimpleName()
                            + " is covered. Silence about the exclusions is how a hash "
                            + "loses a field.");
                }
                material.append(recordType(command).getName());
                for (RecordComponent component : recordType(command).getRecordComponents()) {
                    if (excluded.contains(component.getName())) {
                        continue;
                    }
                    Object value = read(component, command);
                    append(material, component.getName(), value);
                    show(display, component.getName(), value);
                }
            }
            extra.forEach((name, value) -> {
                append(material, name, value);
                show(display, name, value);
            });
            return digest(material.toString());
        }

        /**
         * Projects one hashed segment into what the checker is shown.
         *
         * <p>A segment whose canonical form is null is hashed (as length
         * {@code -1}) and not shown: an absent grant id reads as absence, which
         * is the one covered-but-unshown case kept on purpose.
         *
         * <p>Two segments under one name is not that case. {@link #and} refuses
         * it at the only door that exists today — a record cannot declare a
         * component twice, and {@link #walk} has one caller — so nothing
         * reachable trips the check below, and no test can make it. It is here
         * so that a second caller of {@code walk}, or a builder that grows a
         * third source of segments, cannot reintroduce hashed-twice-shown-once
         * in silence; the cost is one comparison the map already performs.
         */
        private void show(@Nullable Map<String, String> display, String name, @Nullable Object value) {
            if (display == null || withheld == null || withheld.contains(name)) {
                return;
            }
            String rendered = canonical(name, value);
            if (rendered != null && display.put(name, rendered) != null) {
                throw new IllegalStateException(
                        "Two hashed segments are named " + name + "; the console would show one of them");
            }
        }

        private boolean isKnownSegment(String name) {
            return extra.containsKey(name) || (command != null && declares(recordType(command), name));
        }

        private static boolean declares(Class<? extends Record> type, String name) {
            return Arrays.stream(type.getRecordComponents())
                    .anyMatch(component -> component.getName().equals(name));
        }

        private List<String> knownSegments() {
            List<String> names = new java.util.ArrayList<>();
            if (command != null) {
                Arrays.stream(recordType(command).getRecordComponents())
                        .map(RecordComponent::getName)
                        .forEach(names::add);
            }
            names.addAll(extra.keySet());
            return names;
        }

        private @Nullable Object segmentValue(String name) {
            if (command != null) {
                for (RecordComponent component : recordType(command).getRecordComponents()) {
                    if (component.getName().equals(name)) {
                        if (excluded != null && excluded.contains(name)) {
                            throw new IllegalArgumentException(
                                    "%s is excluded from the hash, so it cannot be what the request is about: "
                                                    .formatted(name)
                                            + "a subject the signature does not bind is a label, not evidence");
                        }
                        return read(component, command);
                    }
                }
            }
            if (extra.containsKey(name)) {
                return extra.get(name);
            }
            throw new IllegalArgumentException(
                    "Nothing named %s is hashed here; the segments are %s".formatted(name, knownSegments()));
        }

        private static @Nullable Object read(RecordComponent component, Record command) {
            try {
                java.lang.reflect.Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                return accessor.invoke(command);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not read component " + component.getName(), failure);
            }
        }

        @SuppressWarnings("unchecked")
        private static Class<? extends Record> recordType(Record command) {
            return (Class<? extends Record>) command.getClass();
        }
    }

    /**
     * Appends one length-delimited segment.
     *
     * <p>Both the name and the value carry their own length, so no value
     * containing a separator can be split differently from how it was joined:
     * {@code ("a", "b|c")} and {@code ("a|b", "c")} must not hash alike, which is
     * the same class of defect one level down. A null is length {@code -1} rather
     * than an empty string, so an absent provider reference and an empty one stay
     * distinguishable.
     */
    private static void append(StringBuilder material, String name, @Nullable Object value) {
        String rendered = canonical(name, value);
        material.append('|').append(name.length()).append(':').append(name).append('=');
        if (rendered == null) {
            material.append("-1:");
        } else {
            material.append(rendered.length()).append(':').append(rendered);
        }
    }

    private static @Nullable String canonical(String name, @Nullable Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case UUID id -> id.toString();
            case Enum<?> constant -> constant.name();
            case Boolean flag -> flag.toString();
            case Byte number -> number.toString();
            case Short number -> number.toString();
            case Integer number -> number.toString();
            case Long number -> number.toString();
            case java.math.BigInteger number -> number.toString();
            case BigDecimal number -> number.stripTrailingZeros().toPlainString();
            case Instant instant -> instant.toString();
            // ISO-8601, and canonical because Duration normalises seconds and
            // nanos: PT24H and PT1440M are the same value and the same string.
            // Not toNanos(), which overflows a long somewhere past 292 years.
            case Duration duration -> duration.toString();
            case LocalDate date -> date.toString();
            case LocalTime time -> time.toString();
            default ->
                throw new IllegalArgumentException(
                        ("Component %s is a %s, which has no canonical form an approval hash can be "
                                        + "built from. Give it one here, or exclude it and say why.")
                                .formatted(name, value.getClass().getName()));
        };
    }

    private static String digest(String material) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException required) {
            throw new IllegalStateException("SHA-256 is required by the platform", required);
        }
    }
}
