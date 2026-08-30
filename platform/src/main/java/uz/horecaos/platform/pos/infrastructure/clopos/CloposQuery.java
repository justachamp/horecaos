package uz.horecaos.platform.pos.infrastructure.clopos;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a Clopos query string.
 *
 * <p>Clopos binds list parameters through PHP bracket notation —
 * {@code filters[0][0]=type&filters[0][1][0]=GOODS} — which is not a shape any
 * general query builder produces, so it is built here rather than assembled at
 * each call site where one missing bracket returns a silently unfiltered list.
 *
 * <p><strong>The brackets are percent-encoded, and that is a choice rather than a
 * constraint.</strong> This JDK's {@code java.net.URI} does accept a literal
 * {@code [} inside a query component and will put it on the wire unchanged, so
 * both forms are available. Encoded is chosen because RFC 3986 reserves square
 * brackets for an address literal in the authority and does not permit them
 * elsewhere unescaped — which means a raw bracket is at the mercy of every proxy
 * and gateway between us and Clopos — while PHP's parameter parsing decodes a
 * key before it splits it, so {@code filters%5B0%5D%5B0%5D} binds to
 * {@code filters[0][0]} exactly as the raw form would. Clopos's own curl examples
 * pass {@code --globoff}, which stops <em>curl</em> expanding brackets and says
 * nothing about what reaches the server.
 *
 * <p>That is an inference about somebody else's framework, and it is worth being
 * explicit that it is one. {@code CloposQueryTests} pins the encoded form so a
 * change to it is visible, and the pilot's first filtered catalog read is the
 * empirical check. The failure mode if the inference is wrong is a request that
 * returns the <em>unfiltered</em> list rather than an error, so the check is a
 * row count and not an exception.
 */
public final class CloposQuery {

    private final List<String> parts = new ArrayList<>();

    public static CloposQuery create() {
        return new CloposQuery();
    }

    public CloposQuery page(int page, int limit) {
        return param("page", Integer.toString(page)).param("limit", Integer.toString(limit));
    }

    /** An inclusive {@code YYYY-MM-DD} range on the resource's creation date. */
    public CloposQuery dateRange(String from, String to) {
        return param("date[0]", from).param("date[1]", to);
    }

    /** {@code filters[n][0]=field}, {@code filters[n][1][m]=value}. */
    public CloposQuery filterIn(int index, String field, List<String> values) {
        param("filters[%d][0]".formatted(index), field);
        for (int position = 0; position < values.size(); position++) {
            param("filters[%d][1][%d]".formatted(index, position), values.get(position));
        }
        return this;
    }

    /** {@code with[n]=relation}. Loads a relation Clopos otherwise omits. */
    public CloposQuery with(int index, String relation) {
        return param("with[%d]".formatted(index), relation);
    }

    public CloposQuery param(String name, String value) {
        parts.add(encode(name) + "=" + encode(value));
        return this;
    }

    /** The query string with its leading {@code ?}, or empty when nothing was added. */
    public String render() {
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    private static String encode(String value) {
        // URLEncoder is form encoding, which differs from URI encoding in exactly
        // one way that matters here: it renders a space as '+'. Clopos filter
        // values are identifiers and enum names, so no space reaches this, and
        // the bracket handling is identical.
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
