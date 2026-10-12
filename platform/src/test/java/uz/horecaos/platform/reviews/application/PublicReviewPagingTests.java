package uz.horecaos.platform.reviews.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic of the anonymous comment list's cursor (ADR 0159, Decision 5): a reader pages down at
 * most {@link PublicReviewService#MAXIMUM_OFFSET} comments, and a chain of cursors must end there however
 * the page size divides the ceiling.
 *
 * <p>The walk below reads from a brand with far more comments than the ceiling, as a client does, and
 * asserts what the chain does rather than how a single cursor is computed: it ends, it reads exactly the
 * ceiling, and no comment is read twice.
 */
class PublicReviewPagingTests {

    private static final int COMMENTS_IN_BRAND = 5_000;

    /** What the list does for one page: the comments it reads (by index) and the cursor it hands back. */
    private record Page(int firstIndex, int count, @Nullable String nextCursor) {}

    private static Page pageAt(int offset, int pageSize) {
        int window = PublicReviewService.windowAt(offset, pageSize);
        int count = Math.max(0, Math.min(window, COMMENTS_IN_BRAND - offset));
        return new Page(offset, count, window == 0 ? null : PublicReviewService.cursorAfter(offset, window, count));
    }

    @Test
    @DisplayName(
            "whatever the page size, the cursor chain ends, reads exactly the first five hundred, and reads none twice")
    void theChainEndsAtTheCeilingForEveryPageSize() {
        for (int pageSize = 1; pageSize <= PublicReviewService.MAXIMUM_LIMIT; pageSize++) {
            boolean[] read = new boolean[COMMENTS_IN_BRAND];
            int pages = 0;
            int offset = 0;
            @Nullable String cursor;
            do {
                Page page = pageAt(offset, pageSize);
                for (int i = page.firstIndex(); i < page.firstIndex() + page.count(); i++) {
                    assertThat(read[i])
                            .as("comment %d read twice at page size %d", i, pageSize)
                            .isFalse();
                    read[i] = true;
                }
                cursor = page.nextCursor();
                offset = cursor == null ? offset : Integer.parseInt(cursor);
                pages++;
            } while (cursor != null && pages <= PublicReviewService.MAXIMUM_OFFSET + 1);

            assertThat(cursor).as("the chain ends at page size %d", pageSize).isNull();
            int total = 0;
            for (boolean seen : read) {
                total += seen ? 1 : 0;
            }
            assertThat(total)
                    .as("comments read at page size %d", pageSize)
                    .isEqualTo(PublicReviewService.MAXIMUM_OFFSET);
        }
    }

    @Test
    @DisplayName("a page that falls short of its size is the end, and so is the page that reaches the ceiling")
    void aShortPageAndTheCeilingBothEndTheChain() {
        assertThat(PublicReviewService.cursorAfter(0, 20, 7))
                .as("fewer than asked for")
                .isNull();
        assertThat(PublicReviewService.cursorAfter(0, 20, 20))
                .as("a full page, more to come")
                .isEqualTo("20");
        assertThat(PublicReviewService.cursorAfter(480, 20, 20))
                .as("the ceiling reached exactly")
                .isNull();
        assertThat(PublicReviewService.windowAt(480, 50))
                .as("cut short at the ceiling")
                .isEqualTo(20);
        assertThat(PublicReviewService.windowAt(500, 50))
                .as("a cursor at the ceiling reads nothing")
                .isZero();
    }
}
