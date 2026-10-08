package uz.horecaos.platform.iam.application.staff;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The schema of every surface's document, and the only thing that decides what may be stored (ADR 0160,
 * «Surface schemas»).
 *
 * <p><strong>A closed vocabulary, validated before anything is protected.</strong> A key, a value or a
 * surface this class does not know is refused, never stored: a preference store that accepted free text
 * would be exactly the untyped table ADR 0030 rejected for configuration, and the only thing a person
 * types into a document -- a saved view's label -- is the one field the platform cannot prove is not
 * about someone, which is why the whole document is protected and why the label is held to a shape.
 * What is never allowed in a saved view is a search term, a customer, an order, a courier, a marketplace
 * binding or an absolute date.
 *
 * <p>The output is a canonical document -- keys in a fixed order, false booleans and empty lists left
 * out -- so the stored form is the change from the default and two equal choices are equal text. A
 * refusal names the field that was wrong and never echoes the value, because a value here can be the
 * text a person typed.
 *
 * <p>Each surface carries its schema version and the steps that upgrade an older document one version at
 * a time; the reader runs them in memory ({@link #upgrade}), so a release that changes a shape (the
 * board gains {@code courierEta} and {@code source} when its read does) leaves every stored document
 * valid. No upgrade step exists yet, because every surface is at version 1.
 */
@Component
public class StaffPreferenceSchemas {

    /** The most one document may hold, counted on its canonical text. */
    public static final int MAX_DOCUMENT_BYTES = 8 * 1024;

    /** The most surfaces one person may hold a document for. */
    public static final int MAX_SURFACES_PER_PERSON = 12;

    /** The most saved views one board document may hold. */
    public static final int MAX_SAVED_VIEWS = 20;

    /** The longest saved-view label, counted in characters after the ends are stripped. */
    public static final int MAX_LABEL_CHARACTERS = 40;

    /**
     * A label, or a code, that holds this many digits in a row (a space, a dot, a bracket or a hyphen
     * between two of them does not break the row) reads as a phone number and is refused. Nine is a
     * national Uzbek number; an ISO date has eight and passes.
     */
    static final int PHONE_LIKE_DIGITS = 9;

    private static final Pattern PHONE_LIKE =
            Pattern.compile("\\+?\\(?\\d(?:[\\s().\\-]{0,2}\\d){" + (PHONE_LIKE_DIGITS - 1) + ",}");

    /** The shape of a tenant's own sales-channel codes (V0020 {@code ck_sales_channel_code}). */
    private static final Pattern CHANNEL_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,31}");

    /** The shape of a tenant's own payment-method codes (V0020, V0027). */
    private static final Pattern PAYMENT_METHOD_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_]{0,31}");

    private static final Set<String> ORIGINS = Set.of("HORECAOS", "MARKETPLACE");
    private static final Set<String> FULFILLMENT_MODES = Set.of("DELIVERY", "PICKUP", "DINE_IN");

    /** {@code ordering.orders.payment_status_projection}'s own seven values (V0022). */
    private static final Set<String> PAYMENT_STATUSES =
            Set.of("NOT_REQUIRED", "PENDING", "AUTHORIZED", "CAPTURED", "FAILED", "VOIDED", "REFUNDED");

    /** {@code fiscal.fiscal_documents.status}'s six values, and the board's «needs attention» pseudo-value. */
    private static final Set<String> FISCAL_STATUSES =
            Set.of("ATTENTION", "NOT_APPLICABLE", "PENDING", "SUBMITTED", "ISSUED", "FAILED", "BLOCKED");

    /** The booleans a saved view may switch on, in the order they are written. */
    private static final List<String> VIEW_BOOLEANS =
            List.of("allBranches", "lateOnly", "problemOnly", "callbackRequested", "mineOnly");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** One surface: its current schema version, its validator, and the steps that upgrade an older document. */
    public record SurfaceDefinition(int currentSchema, Validator validator, List<UnaryOperator<JsonNode>> upgrades) {

        public SurfaceDefinition {
            if (currentSchema < 1 || upgrades.size() != currentSchema - 1) {
                throw new IllegalArgumentException(
                        "A surface at schema %d needs %d upgrade steps".formatted(currentSchema, currentSchema - 1));
            }
            upgrades = List.copyOf(upgrades);
        }
    }

    /** Turns whatever the caller sent into the canonical document, or refuses it naming the field. */
    @FunctionalInterface
    public interface Validator {
        ObjectNode validate(JsonNode document);
    }

    private final Map<StaffPreferenceSurface, SurfaceDefinition> definitions;

    @Autowired
    public StaffPreferenceSchemas() {
        this(defaults());
    }

    /** For a test that needs a surface at a later schema than any real one is. */
    StaffPreferenceSchemas(Map<StaffPreferenceSurface, SurfaceDefinition> definitions) {
        this.definitions = new EnumMap<>(definitions);
        for (StaffPreferenceSurface surface : StaffPreferenceSurface.values()) {
            if (!this.definitions.containsKey(surface)) {
                throw new IllegalArgumentException("Surface " + surface + " has no schema");
            }
        }
    }

    private static Map<StaffPreferenceSurface, SurfaceDefinition> defaults() {
        Map<StaffPreferenceSurface, SurfaceDefinition> byName = new EnumMap<>(StaffPreferenceSurface.class);
        byName.put(
                StaffPreferenceSurface.SHELL,
                new SurfaceDefinition(1, StaffPreferenceSchemas::validateShell, List.of()));
        byName.put(
                StaffPreferenceSurface.ORDER_BOARD,
                new SurfaceDefinition(1, StaffPreferenceSchemas::validateOrderBoard, List.of()));
        return byName;
    }

    private SurfaceDefinition definition(StaffPreferenceSurface surface) {
        return Objects.requireNonNull(definitions.get(surface), "Every surface has a schema");
    }

    // ============================================================== the public face

    /** The schema version a document written now is stamped with. */
    public int currentSchema(StaffPreferenceSurface surface) {
        return definition(surface).currentSchema();
    }

    /**
     * Validates {@code document} against the surface's current schema and returns its canonical form.
     *
     * @throws ApiException {@code VALIDATION_FAILED} naming the field, when the document is refused
     */
    public ObjectNode validate(StaffPreferenceSurface surface, @Nullable JsonNode document) {
        if (document == null || document.isMissingNode() || document.isNull()) {
            throw invalid("document", "A document is required");
        }
        ObjectNode canonical = definition(surface).validator().validate(document);
        if (serialize(canonical).getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw invalid("document", "A document is at most %d bytes".formatted(MAX_DOCUMENT_BYTES));
        }
        return canonical;
    }

    /** {@code stored}, written under {@code storedSchema}, brought to the surface's current schema in memory. */
    public JsonNode upgrade(StaffPreferenceSurface surface, JsonNode stored, int storedSchema) {
        SurfaceDefinition definition = definition(surface);
        JsonNode document = stored;
        for (int version = Math.max(storedSchema, 1); version < definition.currentSchema(); version++) {
            document = definition.upgrades().get(version - 1).apply(document);
        }
        return document;
    }

    /** The canonical text a document is stored as (before it is protected). */
    public String serialize(JsonNode document) {
        return MAPPER.writeValueAsString(document);
    }

    public JsonNode parse(String text) {
        return MAPPER.readTree(text);
    }

    /** The mapper that builds the canonical documents, for a caller that must build one (tests). */
    static JsonMapper mapper() {
        return MAPPER;
    }

    // ===================================================================== SHELL

    private static ObjectNode validateShell(JsonNode document) {
        ObjectNode in = requireObject(document, "document");
        rejectUnknown(in, "document", Set.of("defaultLocationId"));
        ObjectNode out = MAPPER.createObjectNode();
        JsonNode location = in.get("defaultLocationId");
        if (location != null && !location.isNull()) {
            out.put(
                    "defaultLocationId",
                    requireUuid(location, "document.defaultLocationId").toString());
        }
        return out;
    }

    // ============================================================== ORDER_BOARD

    private static ObjectNode validateOrderBoard(JsonNode document) {
        ObjectNode in = requireObject(document, "document");
        rejectUnknown(in, "document", Set.of("columns", "savedViews"));
        ObjectNode out = MAPPER.createObjectNode();

        JsonNode columns = in.get("columns");
        if (columns != null && !columns.isNull()) {
            ObjectNode canonical = validateColumns(requireObject(columns, "document.columns"));
            if (!canonical.isEmpty()) {
                out.set("columns", canonical);
            }
        }
        JsonNode views = in.get("savedViews");
        if (views != null && !views.isNull()) {
            ArrayNode canonical = validateSavedViews(requireArray(views, "document.savedViews"));
            if (!canonical.isEmpty()) {
                out.set("savedViews", canonical);
            }
        }
        return out;
    }

    private static ObjectNode validateColumns(ObjectNode columns) {
        rejectUnknown(columns, "document.columns", Set.of("show", "hide"));
        List<OrderBoardColumn> show = columnKeys(columns.get("show"), "document.columns.show", true);
        List<OrderBoardColumn> hide = columnKeys(columns.get("hide"), "document.columns.hide", false);

        Set<OrderBoardColumn> seen = new HashSet<>();
        for (OrderBoardColumn column : show) {
            if (!seen.add(column)) {
                throw invalid("document.columns.show", "A column is named once");
            }
        }
        Set<OrderBoardColumn> hidden = new HashSet<>();
        for (OrderBoardColumn column : hide) {
            if (seen.contains(column)) {
                throw invalid("document.columns.hide", "A column cannot be both shown and hidden");
            }
            if (!hidden.add(column)) {
                throw invalid("document.columns.hide", "A column is named once");
            }
        }
        // The rule that keeps a stored document a change from the default: a column is turned on only if it is
        // off by default, and off only if it is on by default.
        for (OrderBoardColumn column : show) {
            if (column.shownByDefault()) {
                throw invalid("document.columns.show", "A column the board shows by default is not turned on");
            }
        }
        for (OrderBoardColumn column : hide) {
            if (!column.shownByDefault()) {
                throw invalid("document.columns.hide", "A column the board does not show by default is not turned off");
            }
        }

        ObjectNode out = MAPPER.createObjectNode();
        if (!show.isEmpty()) {
            out.set("show", keys(show));
        }
        if (!hide.isEmpty()) {
            out.set("hide", keys(hide));
        }
        return out;
    }

    private static List<OrderBoardColumn> columnKeys(@Nullable JsonNode list, String field, boolean show) {
        if (list == null || list.isNull()) {
            return List.of();
        }
        ArrayNode array = requireArray(list, field);
        List<OrderBoardColumn> columns = new ArrayList<>();
        for (JsonNode entry : array) {
            if (!entry.isString()) {
                throw invalid(field, "A column is named by its key");
            }
            String key = entry.asString();
            OrderBoardColumn column = OrderBoardColumn.ofKey(key).orElse(null);
            if (column == null) {
                if (OrderBoardColumn.ALWAYS_SHOWN.contains(key)) {
                    throw invalid(
                            field,
                            show
                                    ? "A column the board always shows is not turned on"
                                    : "A column the board always shows cannot be hidden");
                }
                throw invalid(field, "Unknown column");
            }
            columns.add(column);
        }
        return columns;
    }

    private static ArrayNode keys(List<OrderBoardColumn> columns) {
        ArrayNode out = MAPPER.createArrayNode();
        columns.forEach(column -> out.add(column.key()));
        return out;
    }

    private static ArrayNode validateSavedViews(ArrayNode views) {
        if (views.size() > MAX_SAVED_VIEWS) {
            throw invalid("document.savedViews", "At most %d saved views".formatted(MAX_SAVED_VIEWS));
        }
        ArrayNode out = MAPPER.createArrayNode();
        Set<UUID> ids = new HashSet<>();
        for (int index = 0; index < views.size(); index++) {
            String where = "document.savedViews[" + index + "]";
            ObjectNode view = requireObject(views.get(index), where);
            rejectUnknown(view, where, Set.of("id", "label", "tab", "filters"));

            UUID id = requireUuid(required(view, "id", where), where + ".id");
            if (!ids.add(id)) {
                throw invalid(where + ".id", "A saved view id is used once");
            }
            String label = validateLabel(required(view, "label", where), where + ".label");
            String tabId = requireString(required(view, "tab", where), where + ".tab");
            OrderBoardTab tab = OrderBoardTab.ofId(tabId).orElseThrow(() -> invalid(where + ".tab", "Unknown tab"));

            ObjectNode canonical = MAPPER.createObjectNode();
            canonical.put("id", id.toString());
            canonical.put("label", label);
            canonical.put("tab", tab.id());
            canonical.set(
                    "filters",
                    validateFilters(
                            requireObject(required(view, "filters", where), where + ".filters"), where + ".filters"));
            out.add(canonical);
        }
        return out;
    }

    private static ObjectNode validateFilters(ObjectNode filters, String where) {
        Set<String> allowed = new HashSet<>(VIEW_BOOLEANS);
        allowed.addAll(Set.of(
                "channelCode",
                "origin",
                "fulfillmentMode",
                "paymentMethodCode",
                "paymentStatus",
                "fiscalStatus",
                "dateRange"));
        rejectUnknown(filters, where, allowed);

        ObjectNode out = MAPPER.createObjectNode();
        for (String name : VIEW_BOOLEANS) {
            JsonNode value = filters.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            if (!value.isBoolean()) {
                throw invalid(where + "." + name, "A yes or no");
            }
            if (value.asBoolean()) {
                out.put(name, true);
            }
        }
        putCode(filters, out, "channelCode", where, CHANNEL_CODE);
        putOneOf(filters, out, "origin", where, ORIGINS);
        putOneOf(filters, out, "fulfillmentMode", where, FULFILLMENT_MODES);
        putCode(filters, out, "paymentMethodCode", where, PAYMENT_METHOD_CODE);
        putOneOf(filters, out, "paymentStatus", where, PAYMENT_STATUSES);
        putOneOf(filters, out, "fiscalStatus", where, FISCAL_STATUSES);

        JsonNode range = filters.get("dateRange");
        if (range != null && !range.isNull()) {
            String token = requireString(range, where + ".dateRange");
            try {
                out.put("dateRange", SavedViewDateRange.valueOf(token).name());
            } catch (IllegalArgumentException unknown) {
                throw invalid(where + ".dateRange", "Unknown period");
            }
        }
        return out;
    }

    /** A code a tenant defined: held to the shape of such codes, since the tenant's own list is not this class's to know. */
    private static void putCode(JsonNode in, ObjectNode out, String name, String where, Pattern shape) {
        JsonNode value = in.get(name);
        if (value == null || value.isNull()) {
            return;
        }
        String field = where + "." + name;
        String code = requireString(value, field);
        if (!shape.matcher(code).matches() || PHONE_LIKE.matcher(code).find()) {
            throw invalid(field, "Not the shape of a code");
        }
        out.put(name, code);
    }

    private static void putOneOf(JsonNode in, ObjectNode out, String name, String where, Set<String> values) {
        JsonNode value = in.get(name);
        if (value == null || value.isNull()) {
            return;
        }
        String field = where + "." + name;
        String chosen = requireString(value, field);
        if (!values.contains(chosen)) {
            throw invalid(field, "Not one of the values this filter takes");
        }
        out.put(name, chosen);
    }

    /**
     * A saved view's name: the one thing a person types into a document. One to forty characters once the
     * ends are stripped, no control or invisible-format characters, and never a run of digits long enough to
     * be a phone number. A name that looks like a person's name is allowed -- the platform cannot tell, which
     * is why the document is protected.
     */
    private static String validateLabel(JsonNode value, String field) {
        String label = requireString(value, field).strip();
        if (label.isEmpty()) {
            throw invalid(field, "A name has at least one visible character");
        }
        if (label.codePointCount(0, label.length()) > MAX_LABEL_CHARACTERS) {
            throw invalid(field, "A name is at most %d characters".formatted(MAX_LABEL_CHARACTERS));
        }
        boolean visible = false;
        for (int index = 0; index < label.length(); ) {
            int point = label.codePointAt(index);
            index += Character.charCount(point);
            int type = Character.getType(point);
            if (Character.isISOControl(point)
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw invalid(field, "A name has no control characters");
            }
            visible |= !Character.isWhitespace(point);
        }
        if (!visible) {
            throw invalid(field, "A name has at least one visible character");
        }
        if (PHONE_LIKE.matcher(label).find()) {
            throw invalid(field, "A name is not a number");
        }
        return label;
    }

    // ============================================================== small helpers

    private static JsonNode required(ObjectNode object, String name, String where) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            throw invalid(where + "." + name, "Required");
        }
        return value;
    }

    private static ObjectNode requireObject(@Nullable JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw invalid(field, "An object");
        }
        return (ObjectNode) node;
    }

    private static ArrayNode requireArray(JsonNode node, String field) {
        if (!node.isArray()) {
            throw invalid(field, "A list");
        }
        return (ArrayNode) node;
    }

    private static String requireString(JsonNode node, String field) {
        if (!node.isString()) {
            throw invalid(field, "Text");
        }
        return node.asString();
    }

    private static UUID requireUuid(JsonNode node, String field) {
        String text = requireString(node, field);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException malformed) {
            throw invalid(field, "An identifier");
        }
    }

    private static void rejectUnknown(ObjectNode object, String where, Set<String> allowed) {
        for (String name : object.propertyNames()) {
            if (!allowed.contains(name)) {
                throw invalid(where + "." + printable(name), "Unknown field");
            }
        }
    }

    /** A key the caller invented is echoed only if it is the shape of a field name; text is never reflected. */
    private static String printable(String name) {
        return name.matches("[A-Za-z][A-Za-z0-9_]{0,39}") ? name : "(unrecognised)";
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, Map.of("field", field));
    }
}
