package uz.horecaos.platform.migration.api;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The import flag, as the binding it is (ADR 0024).
 *
 * <p>What this file asserts is the shape of the binding: when it is on, when it
 * is off, and where it deliberately does not reach. That an import still
 * validates, still audits, and still enforces tenant ancestry is asserted in
 * {@code MigrationControlPlaneTests}, against the real schema, because those are
 * claims about what other code does while the flag is set and cannot be made
 * about the flag on its own.
 */
class MigrationImportContextTests {

    @Test
    @DisplayName("the flag is off outside an import, on inside it, and off again afterwards")
    void theBindingIsStrictlyBalanced() {
        assertThat(ImportContext.isImporting())
                .as("a pooled request thread must not start out silencing notifications")
                .isFalse();

        String answer = ImportContext.runAsImport(() -> {
            assertThat(ImportContext.isImporting()).isTrue();
            return "imported";
        });

        assertThat(answer).isEqualTo("imported");
        assertThat(ImportContext.isImporting())
                .as("the binding is balanced by construction, so there is no way to forget to clear it")
                .isFalse();
    }

    @Test
    @DisplayName("the flag clears even when the import fails")
    void anExceptionDoesNotLeaveTheFlagSet() {
        Throwable failure = catchThrowable(() -> ImportContext.runAsImport(() -> {
            throw new IllegalStateException("a page blew up");
        }));

        assertThat(failure).hasMessage("a page blew up");
        assertThat(ImportContext.isImporting())
                .as("an import that died mid-page must not leave the next caller's "
                        + "customer notification suppressed")
                .isFalse();
    }

    @Test
    @DisplayName("an import inside an import is still one import")
    void nestingIsHarmless() {
        boolean inner = ImportContext.runAsImport(() ->
                ImportContext.runAsImport(ImportContext::isImporting));

        assertThat(inner).isTrue();
        assertThat(ImportContext.isImporting()).isFalse();
    }

    /**
     * The binding is confined to the calling thread and does not follow work
     * handed to an executor. That failure is the unsafe direction — effects
     * escaping rather than being suppressed — so it is asserted rather than left
     * to be discovered by a five-year backfill sending real order confirmations.
     */
    @Test
    @DisplayName("the flag does not follow work handed to another thread")
    void theBindingDoesNotCrossThreads() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Callable<Boolean> onAnotherThread = ImportContext::isImporting;

            Future<Boolean> escaped = ImportContext.runAsImport(() -> pool.submit(onAnotherThread));

            assertThat(escaped.get())
                    .as("an import port must perform its writes on the thread it was given; "
                            + "fanning a page out to a pool is how the suppression is lost")
                    .isFalse();
        }
    }
}
