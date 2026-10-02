package uz.horecaos.platform.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchDecision;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.IntRange;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Rule;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.StartBasis;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.TimeWindow;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Weekday;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingMode;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.DispatchRulesDocumentMixin;

/**
 * The document as it is stored (ADR 0142 "The document").
 *
 * <p>The policy resolver stores and reads it as JSON with the application's mapper, so the shape on the
 * wire is part of the contract: {@code default} (a Java keyword) must round-trip under its own name, an
 * omitted piece must read as its default rather than fail, and a document the record's example shows must
 * read exactly as written.
 */
class DispatchRulesDocumentJsonTests {

    // The application's mapper gets the same rename from Spring Boot's mixin scan; a hand-built one asks for it.
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addMixIn(DispatchRulesDocument.class, DispatchRulesDocumentMixin.class)
            .build();

    private static final String RECORD_EXAMPLE = """
            {
              "schema": 1,
              "rules": [
                {
                  "id": "far-zone-yandex-first",
                  "name": "Far zone, evenings: Yandex, then own fleet",
                  "enabled": true,
                  "when": {
                    "sources":     ["WEB", "TELEGRAM"],
                    "channelIds":  [],
                    "zoneIds":     ["00000000-0000-0000-0000-0000000000f1"],
                    "locationIds": [],
                    "prepMinutes": { "min": 0, "max": 45 },
                    "distanceMeters": { "min": 6000 },
                    "localTime":   { "days": ["MON","TUE","WED","THU","FRI"], "from": "18:00", "to": "23:00" },
                    "prepaid":     true
                  },
                  "then": {
                    "mode": "PARTNER_FIRST",
                    "partners": { "order": ["00000000-0000-0000-0000-0000000000e1"], "exclude": [], "selection": "LADDER" },
                    "dispatchAt": { "basis": "LEAD", "offsetSeconds": 0 },
                    "grouping": null
                  }
                }
              ],
              "default": {
                "mode": "FLEET_FIRST",
                "partners": { "order": [], "exclude": [], "selection": "CHEAPEST" },
                "dispatchAt": { "basis": "LEAD", "offsetSeconds": 0 },
                "grouping": null
              }
            }
            """;

    @Test
    @DisplayName("the document in the record reads exactly as written")
    void theRecordsExampleReadsAsWritten() {
        DispatchRulesDocument document = MAPPER.readValue(RECORD_EXAMPLE, DispatchRulesDocument.class);

        assertThat(document.schema()).isEqualTo(1);
        assertThat(document.rules()).hasSize(1);
        Rule rule = document.rules().getFirst();
        assertThat(rule.id()).isEqualTo("far-zone-yandex-first");
        assertThat(rule.when().sources()).containsExactly("WEB", "TELEGRAM");
        assertThat(rule.when().zoneIds()).containsExactly(UUID.fromString("00000000-0000-0000-0000-0000000000f1"));
        IntRange prep = Objects.requireNonNull(rule.when().prepMinutes());
        IntRange distance = Objects.requireNonNull(rule.when().distanceMeters());
        TimeWindow window = Objects.requireNonNull(rule.when().localTime());
        assertThat(prep.min()).isZero();
        assertThat(prep.max()).isEqualTo(45);
        assertThat(distance.min()).isEqualTo(6_000);
        assertThat(distance.max()).as("an open end is absent").isNull();
        assertThat(window.days()).containsExactly(Weekday.MON, Weekday.TUE, Weekday.WED, Weekday.THU, Weekday.FRI);
        assertThat(window.from()).isEqualTo("18:00");
        assertThat(rule.when().prepaid()).isTrue();
        assertThat(rule.then().mode()).isEqualTo(SourcingMode.PARTNER_FIRST);
        assertThat(rule.then().grouping()).isNull();
        assertThat(document.fallback().mode()).isEqualTo(SourcingMode.FLEET_FIRST);
    }

    @Test
    @DisplayName("a document round-trips, and the default is written under its own name")
    void aDocumentRoundTrips() {
        DispatchRulesDocument document = MAPPER.readValue(RECORD_EXAMPLE, DispatchRulesDocument.class);

        String json = MAPPER.writeValueAsString(document);
        JsonNode tree = MAPPER.readTree(json);

        assertThat(tree.has("default"))
                .as("`default` is a Java keyword; it must not be written as `fallback`")
                .isTrue();
        assertThat(tree.has("fallback")).isFalse();
        assertThat(MAPPER.readValue(json, DispatchRulesDocument.class)).isEqualTo(document);
    }

    @Test
    @DisplayName("an omitted piece reads as its default rather than failing")
    void omittedPiecesReadAsDefaults() {
        DispatchRulesDocument document = MAPPER.readValue("""
                {
                  "schema": 1,
                  "rules": [ { "id": "bare", "name": "Bare", "enabled": true, "then": { "mode": "MANUAL" } } ],
                  "default": { "mode": "FLEET_ONLY" }
                }
                """, DispatchRulesDocument.class);

        Rule bare = document.rules().getFirst();
        assertThat(bare.when().sources()).isEmpty();
        assertThat(bare.when().zoneIds()).isEmpty();
        assertThat(bare.when().localTime()).isNull();
        assertThat(bare.then().partners().order()).isEmpty();
        assertThat(bare.then().partners().selection().name())
                .as("a rule that says nothing about selection takes today's behaviour")
                .isEqualTo("CHEAPEST");
        assertThat(bare.then().dispatchAt().basis()).isEqualTo(StartBasis.LEAD);
        assertThat(bare.then().dispatchAt().offsetSeconds()).isZero();
        assertThat(document.fallback().mode()).isEqualTo(SourcingMode.FLEET_ONLY);
    }

    @Test
    @DisplayName("a stored decision carries identifiers only: no coordinates, names or money")
    void aDecisionIsSafeToStore() {
        UUID installation = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        DispatchDecision decision = new DispatchDecision(
                "far-zone",
                SourcingMode.PARTNER_FIRST,
                new DispatchRulesDocument.PartnerSet(
                        List.of(installation), List.of(), DispatchRulesDocument.PartnerSelection.LADDER),
                DispatchRulesDocument.Start.lead(),
                null,
                List.of(new DispatchRulesDocument.Skip(installation, DispatchRulesDocument.Skip.NO_ACTIVE_BINDING)));

        String json = MAPPER.writeValueAsString(decision);

        assertThat(json)
                .contains("far-zone", installation.toString(), "PARTNER_FIRST", "NO_ACTIVE_BINDING")
                .doesNotContain("latitude", "longitude", "address", "phone", "Minor");
        assertThat(MAPPER.readValue(json, DispatchDecision.class)).isEqualTo(decision);
    }
}
