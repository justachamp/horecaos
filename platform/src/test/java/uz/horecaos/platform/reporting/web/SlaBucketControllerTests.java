package uz.horecaos.platform.reporting.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ADR 0043's buckets as the reference-data screen reads them: exhaustive, the last one open. */
class SlaBucketControllerTests {

    @Test
    void theBucketsCoverEveryElapsedTimeWithoutGaps() {
        SlaBucketController.SlaBuckets set = new SlaBucketController().buckets();

        assertThat(set.buckets()).hasSize(6);
        assertThat(set.buckets().getFirst().fromMinutes()).isZero();
        assertThat(set.buckets().getLast().toMinutesExclusive()).isNull();
        for (int index = 1; index < set.buckets().size(); index++) {
            assertThat(set.buckets().get(index).fromMinutes())
                    .isEqualTo(set.buckets().get(index - 1).toMinutesExclusive());
        }
    }
}
