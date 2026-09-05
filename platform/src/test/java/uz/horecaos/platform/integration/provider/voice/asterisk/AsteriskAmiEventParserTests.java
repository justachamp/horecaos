package uz.horecaos.platform.integration.provider.voice.asterisk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AsteriskAmiEventParserTests {

    @Test
    void readsOneBlockUpToTheBlankLine() throws IOException {
        BufferedReader reader = readerFor("Event: Newchannel\r\n" + "Uniqueid: 1725530000.42\r\n" + "\r\n");

        Optional<Map<String, String>> block = AsteriskAmiEventParser.readBlock(reader);

        assertThat(block).isPresent();
        assertThat(block.get()).containsEntry("Event", "Newchannel").containsEntry("Uniqueid", "1725530000.42");
    }

    @Test
    void stopsExactlyAtTheBlankLineSoASecondBlockCanBeReadNext() throws IOException {
        BufferedReader reader = readerFor("Event: Newchannel\r\n" + "\r\n" + "Event: Hangup\r\n" + "\r\n");

        Optional<Map<String, String>> first = AsteriskAmiEventParser.readBlock(reader);
        Optional<Map<String, String>> second = AsteriskAmiEventParser.readBlock(reader);

        assertThat(first).isPresent();
        assertThat(first.get()).containsEntry("Event", "Newchannel");
        assertThat(second).isPresent();
        assertThat(second.get()).containsEntry("Event", "Hangup");
    }

    @Test
    void returnsEmptyAtEndOfStreamWithNothingRead() throws IOException {
        BufferedReader reader = readerFor("");

        Optional<Map<String, String>> block = AsteriskAmiEventParser.readBlock(reader);

        // What would still pass if the code were broken: a parser that always
        // returned Optional.of(Map.of()) on EOF would make every caller loop
        // forever reading the same empty block. Asserting isEmpty(), not just
        // "has no fields", is what catches that.
        assertThat(block).isEmpty();
    }

    @Test
    void skipsLeadingBlankLinesBetweenBlocksRatherThanReturningAnEmptyBlock() throws IOException {
        BufferedReader reader = readerFor("\r\n\r\n" + "Event: Hangup\r\n" + "Uniqueid: x\r\n" + "\r\n");

        Optional<Map<String, String>> block = AsteriskAmiEventParser.readBlock(reader);

        assertThat(block).isPresent();
        assertThat(block.get()).containsEntry("Event", "Hangup");
    }

    @Test
    void ignoresAContentLineWithNoColonRatherThanFailing() throws IOException {
        // The AMI greeting banner ("Asterisk Call Manager/x.y.z") has no colon.
        BufferedReader reader = readerFor("Asterisk Call Manager/9.0.0\r\n" + "Response: Success\r\n" + "\r\n");

        Optional<Map<String, String>> block = AsteriskAmiEventParser.readBlock(reader);

        assertThat(block).isPresent();
        assertThat(block.get()).containsEntry("Response", "Success");
    }

    private static BufferedReader readerFor(String text) {
        return new BufferedReader(new StringReader(text));
    }
}
