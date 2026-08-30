package uz.horecaos.platform.migration.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;

/**
 * One legacy row turned into a target command, at a declared version (ADR 0024).
 *
 * <p>ADR 0024: "Transformation version is recorded so a changed mapping creates
 * an explicit remediation run rather than silently mixing semantics." A version
 * number on its own does not do that — it does it when somebody remembers to
 * increment it, which is the release nobody remembers. So the version is paired
 * with {@link #rules()}, a declaration of what this transformation actually does,
 * and {@link #digest()} hashes it. The registry holds the digest of the current
 * version; a transformation whose rules changed without its version changing has
 * a different digest, and {@link TransformationRegistry} refuses to start the
 * run.
 *
 * <p>That means {@link #rules()} has to name every decision that changes an
 * output. A rule text is not documentation with a hash attached; it is the thing
 * being versioned, and a mapping change that leaves it untouched defeats the
 * mechanism. In practice this reads as one line per column or decision — "vendor
 * work_time.non_working_days becomes a schedule exception per entry",
 * "cancelled orders resolve their terminal reason from cancelled_by_type" — and
 * a diff of that list is what a remediation's approver is looking at.
 *
 * @param <T> the target command this produces
 */
public interface Transformation<T> {

    /** The crosswalk's name for the family this transforms, upper case. */
    String entityType();

    /**
     * The declared version, which every crosswalk row and every run stamps.
     *
     * <p>Positive and monotonic within an entity type. Incrementing it is how a
     * mapping change is declared; the digest is how an undeclared one is caught.
     */
    int version();

    /**
     * The mapping decisions this version makes, one per line, in a stable order.
     *
     * <p>Stable because the digest is over the joined list: reordering the same
     * rules would otherwise read as a changed mapping and force a remediation over
     * rows nothing had happened to.
     */
    List<String> rules();

    /**
     * Turns one legacy row into a target command, or explains why it cannot.
     *
     * <p>Returning {@link TransformationOutcome#quarantine} rather than throwing
     * is the contract, because "this row is not migratable" is an ordinary outcome
     * of a migration and an exception would take the whole page down with it. ADR
     * 0024 quarantines a row that cannot be mapped; it never assigns it to a
     * convenient default.
     *
     * @param sourceZone the zone the legacy server's naive timestamps are in,
     *                   which the program carries and this must never default. A
     *                   transformation reading a naive timestamp as UTC shifts
     *                   every order across the business-date boundary
     */
    TransformationOutcome<T> transform(LegacyRecord record, ZoneId sourceZone);

    /**
     * The hex sha-256 over the version and the declared rules.
     *
     * <p>The version is inside the hash on purpose: two versions with identical
     * rules are a mistake worth catching, and the registry's unique constraint on
     * {@code (program, entity type, digest)} is what catches it.
     */
    default String digest() {
        StringBuilder canonical = new StringBuilder()
                .append(entityType()).append('\n')
                .append(version()).append('\n');
        rules().forEach(rule -> canonical.append(rule.strip()).append('\n'));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
