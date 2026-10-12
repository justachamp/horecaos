package uz.horecaos.platform.support.printing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rendering snapshots (ADR 0154, Testing): the layout dump of a document, compared with a text file a
 * person has read.
 *
 * <p>A snapshot of the <em>layout</em> and not of pixels, because layout is deterministic on every JVM and
 * every machine while rasterised text depends on the font engine; the pixel assertions are made on what
 * does not (ink in a cell, a QR that decodes). A snapshot file changes only when someone runs the suite
 * with {@code UPDATE_SNAPSHOTS=1} and reads the diff -- an unreviewed golden is evidence about the test.
 */
public final class Snapshots {

    private static final Path DIRECTORY = Path.of("src/test/resources/printing/snapshots");

    private Snapshots() {}

    public static void assertMatches(String name, String actual) {
        Path file = DIRECTORY.resolve(name + ".txt");
        try {
            if (System.getenv("UPDATE_SNAPSHOTS") != null) {
                Files.createDirectories(DIRECTORY);
                Files.writeString(file, actual, StandardCharsets.UTF_8);
                return;
            }
            assertThat(Files.exists(file))
                    .as("the snapshot %s exists; run with UPDATE_SNAPSHOTS=1 and read it", file)
                    .isTrue();
            assertThat(actual).isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
