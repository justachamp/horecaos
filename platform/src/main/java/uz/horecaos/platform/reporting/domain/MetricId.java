package uz.horecaos.platform.reporting.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A metric's stable name and its version, together (ADR 0043).
 *
 * <p>The version is part of the identity rather than a field beside it. A
 * definition change is a new version, and the old one stays queryable while facts
 * computed under it are retained, so a dashboard somebody screenshotted last
 * quarter still reproduces. That property only holds if every surface names the
 * version it used, which is much harder to forget when there is no way to write
 * the name without it.
 *
 * @param name    the stable name, {@code revenue.gross}
 * @param version the definition version, 1-based
 */
public record MetricId(String name, int version) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Pattern CODE = Pattern.compile("(.+)\\.v(\\d+)");

    public MetricId {
        Objects.requireNonNull(name, "A metric needs a name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "A metric name is lower-case dotted segments, was \"" + name + "\"");
        }
        if (version < 1) {
            throw new IllegalArgumentException("A metric version starts at 1, was " + version);
        }
    }

    /** The wire form every surface names: {@code revenue.gross.v1}. */
    public String code() {
        return name + ".v" + version;
    }

    /**
     * Parses the wire form.
     *
     * <p>Throws rather than returning an empty optional for a malformed code. An
     * unparseable metric id is a client bug, and ADR 0043 requires unknown ids to
     * be rejected rather than ignored — the moment one is quietly dropped, a
     * report renders with a column missing and nobody notices.
     */
    public static MetricId parse(String code) {
        Objects.requireNonNull(code, "A metric id is required");
        var matched = CODE.matcher(code);
        if (!matched.matches()) {
            throw new IllegalArgumentException(
                    "A metric id looks like \"revenue.gross.v1\", was \"" + code + "\"");
        }
        return new MetricId(matched.group(1), Integer.parseInt(matched.group(2)));
    }

    @Override
    public String toString() {
        return code();
    }
}
