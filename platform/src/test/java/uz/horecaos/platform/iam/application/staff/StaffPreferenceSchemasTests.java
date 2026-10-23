package uz.horecaos.platform.iam.application.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uz.horecaos.platform.iam.application.staff.StaffPreferenceSchemas.SurfaceDefinition;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0160, «Testing / Closed vocabulary»: a table of accepted and refused documents, every refusal rule the
 * record names, a free-text filter field, a label made of digits, an oversized document and twenty-one
 * saved views -- plus the canonical form a document is stored in, which is what makes "a change from the
 * default" true, and the upgrade chain that lets a later release change a shape.
 *
 * <p>No database, no Spring: the schema is a pure function of the document, and the rule that has to hold is
 * the one that a refusal names the field and never echoes what the person typed.
 */
class StaffPreferenceSchemasTests {

    private static final String VIEW_ID = "0198a3c0-0000-7000-8000-000000000001";
    private static final String OTHER_VIEW_ID = "0198a3c0-0000-7000-8000-000000000002";

    private final StaffPreferenceSchemas schemas = new StaffPreferenceSchemas();

    private static JsonNode json(String text) {
        return StaffPreferenceSchemas.mapper().readTree(text);
    }

    private static String view(String id, String label) {
        return """
                {"id":"%s","label":"%s","tab":"attention","filters":{"lateOnly":true}}
                """.formatted(id, label);
    }

    // ============================================================ accepted documents

    static List<Arguments> accepted() {
        UUID branch = UUID.fromString("0198a3c0-0000-7000-8000-0000000000b1");
        return List.of(
                Arguments.of(StaffPreferenceSurface.SHELL, "{}", "{}"),
                Arguments.of(StaffPreferenceSurface.SHELL, "{\"defaultLocationId\":null}", "{}"),
                Arguments.of(
                        StaffPreferenceSurface.SHELL,
                        "{\"defaultLocationId\":\"" + branch + "\"}",
                        "{\"defaultLocationId\":\"" + branch + "\"}"),
                Arguments.of(StaffPreferenceSurface.ORDER_BOARD, "{}", "{}"),
                Arguments.of(
                        StaffPreferenceSurface.ORDER_BOARD,
                        "{\"columns\":{\"show\":[\"createdBy\",\"acceptedBy\"],\"hide\":[\"courier\"]}}",
                        "{\"columns\":{\"show\":[\"createdBy\",\"acceptedBy\"],\"hide\":[\"courier\"]}}"),
                Arguments.of(
                        StaffPreferenceSurface.ORDER_BOARD,
                        "{\"columns\":{\"show\":[\"courierType\",\"discount\"]}}",
                        "{\"columns\":{\"show\":[\"courierType\",\"discount\"]}}"),
                // Schema 2 (batch 21): the five columns the board read now carries a field for.
                Arguments.of(
                        StaffPreferenceSurface.ORDER_BOARD,
                        "{\"columns\":{\"show\":[\"courierEta\",\"source\",\"fiscal\",\"distance\",\"externalNumber\"]}}",
                        "{\"columns\":{\"show\":[\"courierEta\",\"source\",\"fiscal\",\"distance\",\"externalNumber\"]}}"),
                Arguments.of(
                        StaffPreferenceSurface.ORDER_BOARD,
                        "{\"columns\":{\"hide\":[\"branch\",\"channel\",\"customer\",\"items\",\"payment\",\"courier\"]}}",
                        "{\"columns\":{\"hide\":[\"branch\",\"channel\",\"customer\",\"items\",\"payment\",\"courier\"]}}"),
                // The canonical form: an empty list, a null and a false switch are all "no change from the default".
                Arguments.of(
                        StaffPreferenceSurface.ORDER_BOARD,
                        "{\"columns\":{\"show\":[],\"hide\":null},\"savedViews\":[]}",
                        "{}"),
                Arguments.of(
                        StaffPreferenceSurface.ORDER_BOARD,
                        "{\"savedViews\":[{\"id\":\"" + VIEW_ID + "\",\"label\":\"  Late deliveries  \","
                                + "\"tab\":\"delivering\",\"filters\":{\"lateOnly\":true,\"problemOnly\":false,"
                                + "\"fulfillmentMode\":\"DELIVERY\",\"dateRange\":\"TODAY\",\"channelCode\":\"TELEGRAM\","
                                + "\"paymentMethodCode\":\"CLICK\",\"paymentStatus\":\"PENDING\",\"fiscalStatus\":\"ATTENTION\","
                                + "\"origin\":\"MARKETPLACE\",\"allBranches\":true,\"callbackRequested\":true,\"mineOnly\":true}}]}",
                        "{\"savedViews\":[{\"id\":\"" + VIEW_ID + "\",\"label\":\"Late deliveries\","
                                + "\"tab\":\"delivering\",\"filters\":{\"allBranches\":true,\"lateOnly\":true,"
                                + "\"callbackRequested\":true,\"mineOnly\":true,\"channelCode\":\"TELEGRAM\","
                                + "\"origin\":\"MARKETPLACE\",\"fulfillmentMode\":\"DELIVERY\",\"paymentMethodCode\":\"CLICK\","
                                + "\"paymentStatus\":\"PENDING\",\"fiscalStatus\":\"ATTENTION\",\"dateRange\":\"TODAY\"}}]}"),
                // A view with no filters at all is a tab, which is a fair thing to save.
                Arguments.of(
                        StaffPreferenceSurface.ORDER_BOARD,
                        "{\"savedViews\":[{\"id\":\"" + VIEW_ID + "\",\"label\":\"Cancelled\",\"tab\":\"cancelled\","
                                + "\"filters\":{}}]}",
                        "{\"savedViews\":[{\"id\":\"" + VIEW_ID + "\",\"label\":\"Cancelled\","
                                + "\"tab\":\"cancelled\",\"filters\":{}}]}"),
                // Cyrillic and Uzbek labels, counted in characters, not bytes.
                Arguments.of(
                        StaffPreferenceSurface.ORDER_BOARD,
                        "{\"savedViews\":[{\"id\":\"" + VIEW_ID + "\",\"label\":\"Опаздывающие доставки\","
                                + "\"tab\":\"attention\",\"filters\":{}}]}",
                        "{\"savedViews\":[{\"id\":\"" + VIEW_ID + "\",\"label\":\"Опаздывающие доставки\","
                                + "\"tab\":\"attention\",\"filters\":{}}]}"),
                // An ISO date in a name is a date, not a phone number.
                Arguments.of(
                        StaffPreferenceSurface.ORDER_BOARD,
                        "{\"savedViews\":[{\"id\":\"" + VIEW_ID + "\",\"label\":\"Since 2026-10-08\","
                                + "\"tab\":\"all\",\"filters\":{}}]}",
                        "{\"savedViews\":[{\"id\":\"" + VIEW_ID + "\",\"label\":\"Since 2026-10-08\","
                                + "\"tab\":\"all\",\"filters\":{}}]}"));
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("accepted")
    @DisplayName("a document inside the vocabulary is accepted and stored in its canonical form")
    void acceptedDocuments(StaffPreferenceSurface surface, String sent, String stored) {
        ObjectNode canonical = schemas.validate(surface, json(sent));

        assertThat(schemas.serialize(canonical)).isEqualTo(stored);
    }

    // ============================================================ refused documents

    /** surface, document, the field the refusal must name. */
    static List<Arguments> refused() {
        String id = VIEW_ID;
        return List.of(
                // --- shape
                shell("[]", "document"),
                shell("\"x\"", "document"),
                shell("null", "document"),
                shell("{\"defaultLocationId\":\"not-a-uuid\"}", "document.defaultLocationId"),
                shell("{\"defaultLocationId\":7}", "document.defaultLocationId"),
                // --- an unknown key is refused, never stored
                shell("{\"theme\":\"dark\"}", "document.theme"),
                board("{\"density\":\"compact\"}", "document.density"),
                board("{\"columns\":{\"sort\":[]}}", "document.columns.sort"),
                board(
                        "{\"savedViews\":[{\"id\":\"" + id
                                + "\",\"label\":\"x\",\"tab\":\"all\",\"filters\":{},\"pinned\":true}]}",
                        "document.savedViews[0].pinned"),
                // --- the filters a saved view must never carry: a search term, a customer, an order, a courier, a
                // binding, a date
                filter("\"reference\":\"1042\"", "reference"),
                filter("\"customerId\":\"" + id + "\"", "customerId"),
                filter("\"orderId\":\"" + id + "\"", "orderId"),
                filter("\"courierId\":\"" + id + "\"", "courierId"),
                filter("\"marketplaceBindingId\":\"" + id + "\"", "marketplaceBindingId"),
                filter("\"dateRange\":{\"start\":\"2026-10-01\",\"end\":\"2026-10-08\"}", "dateRange"),
                filter("\"dateRange\":\"2026-10-08\"", "dateRange"),
                filter("\"locationId\":\"" + id + "\"", "locationId"),
                // --- unknown enumeration values
                filter("\"origin\":\"ALIEN\"", "origin"),
                filter("\"fulfillmentMode\":\"TELEPORT\"", "fulfillmentMode"),
                filter("\"paymentStatus\":\"MAYBE\"", "paymentStatus"),
                filter("\"fiscalStatus\":\"NOPE\"", "fiscalStatus"),
                filter("\"dateRange\":\"NEXT_WEEK\"", "dateRange"),
                filter("\"lateOnly\":\"yes\"", "lateOnly"),
                board(
                        "{\"savedViews\":[{\"id\":\"" + id + "\",\"label\":\"x\",\"tab\":\"inbox\",\"filters\":{}}]}",
                        "document.savedViews[0].tab"),
                // --- a free-text filter field: the shape of a tenant's own codes, and not a name or a number
                filter("\"channelCode\":\"Jasur Karimov\"", "channelCode"),
                filter("\"channelCode\":\"telegram\"", "channelCode"),
                filter("\"channelCode\":\"998901234567\"", "channelCode"),
                filter("\"channelCode\":\"\"", "channelCode"),
                filter("\"paymentMethodCode\":\"cash on delivery\"", "paymentMethodCode"),
                // --- columns
                board("{\"columns\":{\"show\":[\"courierEta\",\"courierEta\"]}}", "document.columns.show"),
                board("{\"columns\":{\"hide\":[\"source\"]}}", "document.columns.hide"),
                board("{\"columns\":{\"hide\":[\"fiscal\"]}}", "document.columns.hide"),
                board("{\"columns\":{\"show\":[\"distance\"],\"hide\":[\"distance\"]}}", "document.columns.hide"),
                board("{\"columns\":{\"show\":[\"external_number\"]}}", "document.columns.show"),
                board("{\"columns\":{\"show\":[\"eta\"]}}", "document.columns.show"),
                board("{\"columns\":{\"show\":[\"Created By\"]}}", "document.columns.show"),
                board("{\"columns\":{\"show\":[7]}}", "document.columns.show"),
                board("{\"columns\":{\"hide\":\"courier\"}}", "document.columns.hide"),
                board("{\"columns\":{\"hide\":[\"number\"]}}", "document.columns.hide"),
                board("{\"columns\":{\"hide\":[\"time\"]}}", "document.columns.hide"),
                board("{\"columns\":{\"hide\":[\"total\"]}}", "document.columns.hide"),
                board("{\"columns\":{\"hide\":[\"status\"]}}", "document.columns.hide"),
                board("{\"columns\":{\"hide\":[\"actions\"]}}", "document.columns.hide"),
                board("{\"columns\":{\"show\":[\"customer\"]}}", "document.columns.show"),
                board("{\"columns\":{\"hide\":[\"createdBy\"]}}", "document.columns.hide"),
                board("{\"columns\":{\"show\":[\"createdBy\",\"createdBy\"]}}", "document.columns.show"),
                board("{\"columns\":{\"hide\":[\"courier\",\"courier\"]}}", "document.columns.hide"),
                board("{\"columns\":{\"show\":[\"discount\"],\"hide\":[\"discount\"]}}", "document.columns.hide"),
                // --- labels
                label("", "x"),
                label("   ", "x"),
                label("a\\u0000b", "x"),
                label("tab\\there", "x"),
                label("line\\nbreak", "x"),
                label("\\u200b\\u200b", "x"),
                label("zero\\u200bwidth", "x"),
                label("x".repeat(41), "x"),
                label("998901234567", "x"),
                label("+998 90 123 45 67", "x"),
                label("(90) 123-45-67", "x"),
                label("Call 998 90 123 45 67 now", "x"),
                // --- ids
                board(
                        "{\"savedViews\":[{\"id\":\"1\",\"label\":\"x\",\"tab\":\"all\",\"filters\":{}}]}",
                        "document.savedViews[0].id"),
                board(
                        "{\"savedViews\":[" + view(id, "one") + "," + view(id, "two") + "]}",
                        "document.savedViews[1].id"),
                board(
                        "{\"savedViews\":[{\"label\":\"x\",\"tab\":\"all\",\"filters\":{}}]}",
                        "document.savedViews[0].id"),
                board(
                        "{\"savedViews\":[{\"id\":\"" + id + "\",\"label\":\"x\",\"tab\":\"all\"}]}",
                        "document.savedViews[0].filters"));
    }

    private static Arguments shell(String document, String field) {
        return Arguments.of(StaffPreferenceSurface.SHELL, document, field);
    }

    private static Arguments board(String document, String field) {
        return Arguments.of(StaffPreferenceSurface.ORDER_BOARD, document, field);
    }

    /** A one-view board document whose view carries {@code filterField} among its filters; it must be refused at that filter. */
    private static Arguments filter(String filterField, String name) {
        return board(
                "{\"savedViews\":[{\"id\":\"" + VIEW_ID + "\",\"label\":\"x\",\"tab\":\"all\",\"filters\":{"
                        + filterField + "}}]}",
                "document.savedViews[0].filters." + name);
    }

    /** A one-view board document with this label (JSON-escaped by the caller); it must be refused at the label. */
    private static Arguments label(String label, String ignored) {
        return board(
                "{\"savedViews\":[{\"id\":\"" + VIEW_ID + "\",\"label\":\"" + label
                        + "\",\"tab\":\"all\",\"filters\":{}}]}",
                "document.savedViews[0].label");
    }

    @ParameterizedTest(name = "[{index}] {2}")
    @MethodSource("refused")
    @DisplayName("a document outside the vocabulary is refused, naming the field, and nothing is echoed back")
    void refusedDocuments(StaffPreferenceSurface surface, String sent, String field) {
        assertThatThrownBy(() -> schemas.validate(surface, json(sent)))
                .isInstanceOfSatisfying(ApiException.class, refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(refusal.properties()).containsEntry("field", field);
                    // A refusal names the field and says what is wrong with its kind. It never repeats what the
                    // person typed: a label can be a name, and a Problem Details body is logged by proxies.
                    assertThat(refusal.getMessage())
                            .doesNotContain("Karimov", "998", "telegram", "ALIEN", "TELEPORT", "zero", "Call");
                });
    }

    // ============================================================ the size caps

    @Test
    @DisplayName("twenty saved views are the most, and the twenty-first is refused")
    void twentyOneSavedViews() {
        assertThat(schemas.validate(
                        StaffPreferenceSurface.ORDER_BOARD, json(views(StaffPreferenceSchemas.MAX_SAVED_VIEWS))))
                .isNotNull();

        assertThatThrownBy(() -> schemas.validate(
                        StaffPreferenceSurface.ORDER_BOARD, json(views(StaffPreferenceSchemas.MAX_SAVED_VIEWS + 1))))
                .isInstanceOfSatisfying(ApiException.class, refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(refusal.properties()).containsEntry("field", "document.savedViews");
                });
    }

    @Test
    @DisplayName("a label of forty characters fits and forty-one does not, counting characters and not bytes")
    void theLabelLimitCountsCharacters() {
        String forty = "ж".repeat(40);

        ObjectNode canonical = schemas.validate(
                StaffPreferenceSurface.ORDER_BOARD, json("{\"savedViews\":[" + view(VIEW_ID, forty) + "]}"));

        assertThat(canonical.get("savedViews").get(0).get("label").asString()).isEqualTo(forty);
        assertThatThrownBy(() -> schemas.validate(
                        StaffPreferenceSurface.ORDER_BOARD,
                        json("{\"savedViews\":[" + view(VIEW_ID, "ж".repeat(41)) + "]}")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a document over 8 KB is refused even when every field in it is allowed")
    void anOversizedDocument() {
        // Twenty views of forty-character labels with every filter on is the biggest legal document; make the
        // schema's cap smaller than that by using the longest codes the shape allows.
        StringBuilder views = new StringBuilder();
        for (int index = 0; index < StaffPreferenceSchemas.MAX_SAVED_VIEWS; index++) {
            if (index > 0) {
                views.append(',');
            }
            views.append("{\"id\":\"")
                    .append(UUID.nameUUIDFromBytes(("v" + index).getBytes()))
                    .append("\",\"label\":\"")
                    .append("щ".repeat(40))
                    .append("\",\"tab\":\"attention\",\"filters\":{\"channelCode\":\"")
                    .append("A".repeat(32))
                    .append("\",\"paymentMethodCode\":\"")
                    .append("B".repeat(32))
                    .append("\",\"allBranches\":true,\"lateOnly\":true,\"problemOnly\":true,"
                            + "\"callbackRequested\":true,\"mineOnly\":true,\"origin\":\"MARKETPLACE\","
                            + "\"fulfillmentMode\":\"DINE_IN\",\"paymentStatus\":\"NOT_REQUIRED\","
                            + "\"fiscalStatus\":\"NOT_APPLICABLE\",\"dateRange\":\"LAST_7_DAYS\"}}");
        }

        // The cap is on the canonical text, UTF-8 bytes: 20 x (40 two-byte letters + 64 code characters + ids...).
        String document = "{\"savedViews\":[" + views + "]}";
        assertThat(StaffPreferenceSchemas.mapper().writeValueAsBytes(json(document)).length)
                .as("the fixture is meant to sit over the cap, or the assertion below proves nothing")
                .isGreaterThan(StaffPreferenceSchemas.MAX_DOCUMENT_BYTES);

        assertThatThrownBy(() -> schemas.validate(StaffPreferenceSurface.ORDER_BOARD, json(document)))
                .isInstanceOfSatisfying(ApiException.class, refusal -> {
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(refusal.properties()).containsEntry("field", "document");
                });
    }

    private static String views(int count) {
        StringBuilder views = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                views.append(',');
            }
            views.append(
                    view(UUID.nameUUIDFromBytes(("view" + index).getBytes()).toString(), "View " + index));
        }
        return "{\"savedViews\":[" + views + "]}";
    }

    // ============================================================ the unknown key's name

    @Test
    @DisplayName("an unknown key is named only if it is shaped like a field name; free text is never reflected")
    void anInventedKeyIsNotReflected() {
        assertThatThrownBy(() -> schemas.validate(
                        StaffPreferenceSurface.ORDER_BOARD, json("{\"Jasur Karimov +998901234567\":1}")))
                .isInstanceOfSatisfying(ApiException.class, refusal -> {
                    assertThat(refusal.properties()).containsEntry("field", "document.(unrecognised)");
                    assertThat(refusal.getMessage() + refusal.properties()).doesNotContain("Jasur", "998");
                });
    }

    // ============================================================ the schema chain

    @Test
    @DisplayName("every surface the code knows has a schema, and a surface the code does not know is not a surface")
    void everySurfaceHasASchemaAndAnUnknownNameIsNone() {
        assertThat(schemas.currentSchema(StaffPreferenceSurface.SHELL)).isEqualTo(1);
        assertThat(schemas.currentSchema(StaffPreferenceSurface.ORDER_BOARD))
                .as("batch 21 added five columns in one bump")
                .isEqualTo(2);
        for (StaffPreferenceSurface surface : StaffPreferenceSurface.values()) {
            assertThat(schemas.currentSchema(surface)).isBetween(1, 2);
        }
        assertThat(StaffPreferenceSurface.ofName("ORDER_BOARD")).isPresent();
        assertThat(StaffPreferenceSurface.ofName("order_board"))
                .as("spelled exactly")
                .isEmpty();
        assertThat(StaffPreferenceSurface.ofName("THEME")).isEmpty();
        assertThat(StaffPreferenceSurface.ofName("../SHELL")).isEmpty();
    }

    @Test
    @DisplayName("a document written under an older schema is upgraded one version at a time, in memory")
    void anOlderDocumentIsUpgradedOnRead() {
        Map<StaffPreferenceSurface, SurfaceDefinition> definitions = new EnumMap<>(StaffPreferenceSurface.class);
        definitions.put(
                StaffPreferenceSurface.SHELL,
                new SurfaceDefinition(
                        3,
                        document -> (ObjectNode) document,
                        List.of(
                                // v1 -> v2: the branch key was renamed.
                                document -> {
                                    ObjectNode copy = ((ObjectNode) document).deepCopy();
                                    JsonNode old = copy.remove("branch");
                                    if (old != null) {
                                        copy.set("defaultLocationId", old);
                                    }
                                    return copy;
                                },
                                // v2 -> v3: a marker, to prove the steps run in order and each exactly once.
                                document -> {
                                    ObjectNode copy = ((ObjectNode) document).deepCopy();
                                    copy.put("upgradedTo", copy.has("upgradedTo") ? "twice" : "3");
                                    return copy;
                                })));
        definitions.put(
                StaffPreferenceSurface.ORDER_BOARD,
                new SurfaceDefinition(1, document -> (ObjectNode) document, List.of()));
        StaffPreferenceSchemas later = new StaffPreferenceSchemas(definitions);

        JsonNode fromV1 = later.upgrade(StaffPreferenceSurface.SHELL, json("{\"branch\":\"b\"}"), 1);
        JsonNode fromV2 = later.upgrade(StaffPreferenceSurface.SHELL, json("{\"defaultLocationId\":\"b\"}"), 2);
        JsonNode current = later.upgrade(StaffPreferenceSurface.SHELL, json("{\"x\":1}"), 3);

        assertThat(later.serialize(fromV1)).isEqualTo("{\"defaultLocationId\":\"b\",\"upgradedTo\":\"3\"}");
        assertThat(later.serialize(fromV2)).isEqualTo("{\"defaultLocationId\":\"b\",\"upgradedTo\":\"3\"}");
        assertThat(later.serialize(current))
                .as("a document at the current schema is left alone")
                .isEqualTo("{\"x\":1}");
        assertThat(later.upgrade(StaffPreferenceSurface.ORDER_BOARD, json("{}"), 1)
                        .toString())
                .isEqualTo("{}");
    }

    @Test
    @DisplayName("a schema-1 order board document means the same under schema 2: the one upgrade step is the identity")
    void aSchemaOneBoardDocumentUpgradesUnchanged() {
        String stored = "{\"columns\":{\"show\":[\"createdBy\"],\"hide\":[\"courier\"]},"
                + "\"savedViews\":[{\"id\":\"" + VIEW_ID + "\",\"label\":\"Late\",\"tab\":\"attention\","
                + "\"filters\":{\"lateOnly\":true}}]}";

        JsonNode upgraded = schemas.upgrade(StaffPreferenceSurface.ORDER_BOARD, json(stored), 1);

        assertThat(schemas.serialize(upgraded)).isEqualTo(stored);
        assertThat(schemas.serialize(schemas.upgrade(StaffPreferenceSurface.ORDER_BOARD, json(stored), 2)))
                .as("a document already at the current schema is not touched either")
                .isEqualTo(stored);
        assertThat(schemas.serialize(schemas.validate(StaffPreferenceSurface.ORDER_BOARD, upgraded)))
                .as("and the upgraded document is valid under the schema it was upgraded to")
                .isEqualTo(stored);
    }

    @Test
    @DisplayName("a surface definition whose steps do not lead from 1 to its schema is a programming error")
    void aDefinitionMustHaveEveryStep() {
        assertThatThrownBy(() -> new SurfaceDefinition(2, document -> (ObjectNode) document, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "the column vocabulary is the fifteen of schema 2, five columns are never in it, and the five new ones are off by default")
    void theColumnVocabulary() {
        assertThat(java.util.Arrays.stream(OrderBoardColumn.values()).map(OrderBoardColumn::key))
                .containsExactly(
                        "branch",
                        "channel",
                        "customer",
                        "items",
                        "payment",
                        "courier",
                        "createdBy",
                        "acceptedBy",
                        "courierType",
                        "discount",
                        "courierEta",
                        "source",
                        "fiscal",
                        "distance",
                        "externalNumber");
        assertThat(OrderBoardColumn.ALWAYS_SHOWN)
                .containsExactlyInAnyOrder("number", "time", "total", "status", "actions");
        assertThat(java.util.Arrays.stream(OrderBoardColumn.values())
                        .filter(column -> !column.shownByDefault())
                        .map(OrderBoardColumn::key))
                .as("a bump adds to the addable set, so nobody's default board changes")
                .containsExactly(
                        "createdBy",
                        "acceptedBy",
                        "courierType",
                        "discount",
                        "courierEta",
                        "source",
                        "fiscal",
                        "distance",
                        "externalNumber");
        assertThat(java.util.Arrays.stream(OrderBoardColumn.values())
                        .filter(OrderBoardColumn::shownByDefault)
                        .map(OrderBoardColumn::key))
                .containsExactly("branch", "channel", "customer", "items", "payment", "courier");
    }
}
