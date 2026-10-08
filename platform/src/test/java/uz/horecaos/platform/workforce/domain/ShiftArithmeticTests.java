package uz.horecaos.platform.workforce.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 0156's three-line arithmetic against {@code workforce/shift-arithmetic-vectors.csv}, the same
 * file the courier-shift tests read: neither side can drift without a test saying so.
 */
class ShiftArithmeticTests {

    record Vector(
            String name,
            Instant start,
            Instant end,
            long breakSeconds,
            @Nullable Instant plannedStart,
            @Nullable Instant plannedEnd,
            int plannedBreakMinutes,
            long worked,
            long paid,
            @Nullable Long planned,
            @Nullable Long variance) {}

    static List<Vector> vectors() throws IOException {
        List<Vector> vectors = new ArrayList<>();
        try (InputStream in =
                        ShiftArithmeticTests.class.getResourceAsStream("/workforce/shift-arithmetic-vectors.csv");
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(java.util.Objects.requireNonNull(in), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split(",", -1);
                vectors.add(new Vector(
                        f[0],
                        Instant.parse(f[1]),
                        Instant.parse(f[2]),
                        Long.parseLong(f[3]),
                        f[4].isBlank() ? null : Instant.parse(f[4]),
                        f[5].isBlank() ? null : Instant.parse(f[5]),
                        f[6].isBlank() ? 0 : Integer.parseInt(f[6]),
                        Long.parseLong(f[7]),
                        Long.parseLong(f[8]),
                        f[9].isBlank() ? null : Long.parseLong(f[9]),
                        f[10].isBlank() ? null : Long.parseLong(f[10])));
            }
        }
        return vectors;
    }

    @Test
    @DisplayName("every vector gives the worked, paid, planned and variance seconds the file states")
    void theVectorsHold() throws IOException {
        List<Vector> vectors = vectors();
        assertThat(vectors).as("the file is not empty").hasSizeGreaterThanOrEqualTo(8);
        for (Vector v : vectors) {
            long worked = ShiftArithmetic.workedSeconds(v.start(), v.end());
            long paid = ShiftArithmetic.paidSeconds(worked, v.breakSeconds());
            Long planned = v.plannedStart() == null
                    ? null
                    : ShiftArithmetic.plannedSeconds(
                            v.plannedStart(),
                            java.util.Objects.requireNonNull(v.plannedEnd()),
                            v.plannedBreakMinutes());
            Long variance = ShiftArithmetic.varianceSeconds(paid, planned);

            assertThat(worked).as(v.name() + " worked").isEqualTo(v.worked());
            assertThat(paid).as(v.name() + " paid").isEqualTo(v.paid());
            assertThat(planned).as(v.name() + " planned").isEqualTo(v.planned());
            assertThat(variance).as(v.name() + " variance").isEqualTo(v.variance());
        }
    }

    @Test
    @DisplayName("null is not zero: no plan is no variance, and a plan met exactly is a variance of zero")
    void nullIsNotZero() {
        assertThat(ShiftArithmetic.varianceSeconds(28_800, null)).isNull();
        assertThat(ShiftArithmetic.varianceSeconds(27_000, 27_000L)).isZero();
        assertThat(ShiftArithmetic.needsApproval(null, 30))
                .as("no plan never waits for approval")
                .isFalse();
        assertThat(ShiftArithmetic.needsApproval(0L, 30)).isFalse();
    }

    @Test
    @DisplayName("the threshold is on the absolute variance and a variance exactly at it does not wait")
    void thresholdIsAbsoluteAndExclusive() {
        assertThat(ShiftArithmetic.needsApproval(1_800L, 30))
                .as("exactly 30 minutes is within")
                .isFalse();
        assertThat(ShiftArithmetic.needsApproval(1_801L, 30)).isTrue();
        assertThat(ShiftArithmetic.needsApproval(-1_801L, 30))
                .as("leaving early counts the same")
                .isTrue();
        assertThat(ShiftArithmetic.needsApproval(-1_800L, 30)).isFalse();
        assertThat(ShiftArithmetic.needsApproval(300L, 5)).isFalse();
        assertThat(ShiftArithmetic.needsApproval(301L, 5)).isTrue();
    }

    @Test
    @DisplayName("a break longer than the shift is refused, where a courier's would be clamped to zero")
    void aBreakCannotExceedTheShift() {
        assertThatThrownBy(() -> ShiftArithmetic.paidSeconds(3_600, 3_601))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShiftArithmetic.paidSeconds(3_600, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a span is sane when positive and at most sixteen hours")
    void theSanityBound() {
        Instant start = Instant.parse("2026-10-08T00:00:00Z");
        assertThat(ShiftArithmetic.isSaneSpan(start, start.plusSeconds(16 * 3_600)))
                .isTrue();
        assertThat(ShiftArithmetic.isSaneSpan(start, start.plusSeconds(16 * 3_600 + 1)))
                .isFalse();
        assertThat(ShiftArithmetic.isSaneSpan(start, start)).isFalse();
        assertThat(ShiftArithmetic.isSaneSpan(start, start.minusSeconds(60))).isFalse();
    }
}
