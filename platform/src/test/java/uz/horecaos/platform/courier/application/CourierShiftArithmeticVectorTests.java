package uz.horecaos.platform.courier.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The courier side of ADR 0156's shared vector file: a courier shift's paid time is wall time less
 * break time, the same two lines the staff-shift arithmetic states. Variance is not tested here: a
 * courier's variance is a different record (ADR 0042) and the file's planned and variance columns
 * are the staff shift's.
 */
class CourierShiftArithmeticVectorTests {

    @Test
    @DisplayName("a courier shift's paid time agrees with every worked/break/paid vector the staff shift is pinned to")
    void paidTimeAgreesWithTheSharedVectors() throws Exception {
        int checked = 0;
        try (InputStream in = getClass().getResourceAsStream("/workforce/shift-arithmetic-vectors.csv");
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(java.util.Objects.requireNonNull(in), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split(",", -1);
                long wall = Duration.between(Instant.parse(f[1]), Instant.parse(f[2]))
                        .toSeconds();
                long breakSeconds = Long.parseLong(f[3]);
                assertThat(CourierShiftService.paidSecondsOf(wall, breakSeconds))
                        .as(f[0])
                        .isEqualTo(Long.parseLong(f[8]));
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("where the two records differ on purpose: a courier's break over the wall time clamps to zero")
    void aCourierClampsWhereAnEmployeeIsRefused() {
        assertThat(CourierShiftService.paidSecondsOf(3_600, 7_200)).isZero();
    }
}
