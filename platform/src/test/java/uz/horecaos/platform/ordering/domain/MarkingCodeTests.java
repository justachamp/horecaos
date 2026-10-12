package uz.horecaos.platform.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.ordering.domain.MarkingCodes.ClaimedGtin;

/**
 * The server's half of the shape of a marking code (ADR 0161).
 *
 * <p>The vectors are a file the console's spec reads too, byte for byte ({@code
 * tools/checks/repo_hygiene.py} fails when the two copies differ), so the browser's instant feedback and
 * this class's authority cannot drift apart unnoticed: each would otherwise pass its own suite while one
 * accepted a code the other refuses.
 */
class MarkingCodeTests {

    private static final JsonNode VECTORS = load();

    private static JsonNode load() {
        try (InputStream stream = MarkingCodeTests.class.getResourceAsStream("/marking/normalisation-vectors.json")) {
            assertThat(stream)
                    .as("the shared vector file is on the test classpath")
                    .isNotNull();
            return JsonMapper.builder().build().readTree(stream);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    @DisplayName("every normalisation vector produces exactly its canonical string, or a refusal")
    void normalisationFollowsTheSharedVectors() {
        JsonNode vectors = VECTORS.get("normalisation");
        assertThat(vectors.size()).isGreaterThan(20);
        int accepted = 0;
        int refused = 0;
        for (JsonNode vector : vectors) {
            String name = vector.get("name").asString();
            Optional<String> actual = MarkingCodes.normalise(vector.get("input").asString());
            JsonNode canonical = vector.get("canonical");
            if (canonical.isNull()) {
                assertThat(actual).as(name).isEmpty();
                refused++;
            } else {
                assertThat(actual).as(name).contains(canonical.asString());
                accepted++;
            }
        }
        assertThat(accepted).as("the vectors include accepted inputs").isGreaterThan(8);
        assertThat(refused).as("and refused ones").isGreaterThan(8);
    }

    @Test
    @DisplayName("every structure vector agrees with the GS1 check, including the GTIN it reads")
    void structureFollowsTheSharedVectors() {
        for (JsonNode vector : VECTORS.get("structure")) {
            String name = vector.get("name").asString();
            MarkingCodes.Structure structure =
                    MarkingCodes.gs1Structure(vector.get("canonical").asString());
            assertThat(structure.valid())
                    .as(name)
                    .isEqualTo(vector.get("gs1Valid").asBoolean());
            JsonNode gtin = vector.get("gtin");
            assertThat(structure.gtin()).as(name).isEqualTo(gtin.isNull() ? null : gtin.asString());
        }
    }

    @Test
    @DisplayName("the structure policy NONE reads a GTIN when there is one and marks the rest unreadable")
    void policyNoneChecksOnlyWhatNormalisationChecked() {
        String valid = "010460000000001521Zq8mK2pXr4Wd";
        assertThat(MarkingCodes.claimedGtin(valid, MarkingStructureCheck.GS1))
                .contains(new ClaimedGtin("04600000000015", true));
        assertThat(MarkingCodes.claimedGtin(valid, MarkingStructureCheck.NONE))
                .contains(new ClaimedGtin("04600000000015", true));

        // Not GS1 at all: GS1 refuses it, NONE accepts it and records that no GTIN could be read.
        String opaque = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        assertThat(MarkingCodes.claimedGtin(opaque, MarkingStructureCheck.GS1)).isEmpty();
        assertThat(MarkingCodes.claimedGtin(opaque, MarkingStructureCheck.NONE))
                .contains(new ClaimedGtin(MarkingCodes.UNKNOWN_GTIN, false));

        // A bad check digit: GS1 refuses; NONE does not verify it.
        String badCheckDigit = "010460000000001621Zq8mK2pXr4Wd";
        assertThat(MarkingCodes.claimedGtin(badCheckDigit, MarkingStructureCheck.GS1))
                .isEmpty();
        assertThat(MarkingCodes.claimedGtin(badCheckDigit, MarkingStructureCheck.NONE))
                .contains(new ClaimedGtin("04600000000016", true));
    }

    @Test
    @DisplayName("a classification barcode is left-padded to fourteen digits to meet a code's GTIN")
    void barcodesAreComparedAsGtin14() {
        assertThat(MarkingCodes.barcodeAsGtin14("4600000000015")).contains("04600000000015");
        assertThat(MarkingCodes.barcodeAsGtin14("96385074")).contains("00000096385074"); // EAN-8
        assertThat(MarkingCodes.barcodeAsGtin14("036000291452")).contains("00036000291452"); // UPC-A
        assertThat(MarkingCodes.barcodeAsGtin14("04600000000015")).contains("04600000000015");
        assertThat(MarkingCodes.barcodeAsGtin14(null)).isEmpty();
        assertThat(MarkingCodes.barcodeAsGtin14("")).isEmpty();
        assertThat(MarkingCodes.barcodeAsGtin14("46000000000AB")).isEmpty();
        assertThat(MarkingCodes.barcodeAsGtin14("123456789012345")).isEmpty();
    }

    @Test
    @DisplayName("the structure-check setting reads NONE and treats anything else, including a typo, as GS1")
    void aTypoInThePolicyIsReadAsTheStricterOne() {
        assertThat(MarkingStructureCheck.of("NONE")).isEqualTo(MarkingStructureCheck.NONE);
        assertThat(MarkingStructureCheck.of("GS1")).isEqualTo(MarkingStructureCheck.GS1);
        assertThat(MarkingStructureCheck.of("none")).isEqualTo(MarkingStructureCheck.GS1);
        assertThat(MarkingStructureCheck.of(null)).isEqualTo(MarkingStructureCheck.GS1);
    }

    @Test
    @DisplayName("an operator's reason is one of the three coded ones; the platform's two are not an operator's choice")
    void releaseReasonsAreCodeOwned() {
        assertThat(MarkReleaseReason.parseOperatorReason("MISSCAN")).contains(MarkReleaseReason.MISSCAN);
        assertThat(MarkReleaseReason.parseOperatorReason("UNIT_DAMAGED")).contains(MarkReleaseReason.UNIT_DAMAGED);
        assertThat(MarkReleaseReason.parseOperatorReason("ORDER_CANCELLED")).isEmpty();
        assertThat(MarkReleaseReason.parseOperatorReason("LINE_REPLACED")).isEmpty();
        assertThat(MarkReleaseReason.parseOperatorReason("the customer, Aziza, asked"))
                .isEmpty();
    }

    @Test
    @DisplayName("the stage follows the status: pick before the pass, handover at it, and nothing after")
    void theStageIsDerivedFromTheStatus() {
        assertThat(MarkCaptureStage.forStatus(OrderStatus.CONFIRMED)).contains(MarkCaptureStage.PICK);
        assertThat(MarkCaptureStage.forStatus(OrderStatus.PREPARING)).contains(MarkCaptureStage.PICK);
        assertThat(MarkCaptureStage.forStatus(OrderStatus.READY)).contains(MarkCaptureStage.HANDOVER);
        for (OrderStatus other : List.of(
                OrderStatus.RECEIVED,
                OrderStatus.AWAITING_APPROVAL,
                OrderStatus.FULFILLING,
                OrderStatus.COMPLETED,
                OrderStatus.CANCELLED)) {
            assertThat(MarkCaptureStage.forStatus(other)).as(other.name()).isEmpty();
        }
    }
}
