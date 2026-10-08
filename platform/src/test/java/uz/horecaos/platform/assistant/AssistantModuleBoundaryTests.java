package uz.horecaos.platform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two properties ADR 0069 states in prose and this makes mechanical.
 *
 * <p>The first is the one the ADR calls its default: <em>the assistant assembles
 * orders and never completes them</em>. Stage one does not even assemble, so the
 * module has no business with a cart, a checkout or a payment, and a source scan
 * is how that stays true when someone adds "just one convenience" later. The scan
 * is of imports and of the few method names that mean completing a purchase; it
 * is not a security boundary, it is a tripwire that makes the change a visible
 * decision in a diff.
 *
 * <p>The second is that {@code conversations} stays a leaf: the engine offers a
 * turn to whichever participant exists and imports nothing about the assistant.
 */
class AssistantModuleBoundaryTests {

    private static final Path ROOT = Path.of("src/main/java/uz/horecaos/platform");

    @Test
    @DisplayName("the assistant module cannot build a cart, check one out, or touch a payment")
    void theAssistantCannotCompleteAnOrder() throws IOException {
        List<String> offenders = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT.resolve("assistant"))) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String code = line.strip();
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                        continue;
                    }
                    if (code.startsWith("import uz.horecaos.platform.payments")
                            || code.startsWith("import uz.horecaos.platform.ordering.application")
                            || code.startsWith("import uz.horecaos.platform.ordering.web")
                            || code.contains("checkoutForCash")
                            || code.contains(".repeat(") && code.contains("orders.")
                            || code.contains("CartService")
                            || code.contains("CheckoutService")
                            || code.contains("PaymentIntentPort")) {
                        offenders.add(ROOT.relativize(file) + ": " + code);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("ADR 0069: assemble-and-confirm is the default; completing an order from a chat is an open input "
                        + "the owner has not closed, and stage one assembles nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("of CustomerBotOrderingPort the assistant reads one order and nothing else")
    void theAssistantOnlyReadsOrders() throws IOException {
        List<String> uses = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT.resolve("assistant"))) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String mutating : List.of("repeat(", "checkoutForCash(", "currentCart(")) {
                    if (source.contains("orders." + mutating)) {
                        uses.add(ROOT.relativize(file) + " calls " + mutating);
                    }
                }
            }
        }

        assertThat(uses).isEmpty();
    }

    @Test
    @DisplayName(
            "conversations imports nothing from the assistant: the engine is a leaf that offers a turn to a participant")
    void conversationsDoesNotKnowTheAssistant() throws IOException {
        List<String> offenders = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT.resolve("conversations"))) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (line.startsWith("import uz.horecaos.platform.assistant")) {
                        offenders.add(ROOT.relativize(file) + ": " + line);
                    }
                }
            }
        }

        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("no class of the assistant writes a customer's words to a log")
    void nothingLogsAMessage() throws IOException {
        List<String> offenders = new java.util.ArrayList<>();
        for (String module : List.of("assistant", "integration/provider/assistant")) {
            try (Stream<Path> files = Files.walk(ROOT.resolve(module))) {
                for (Path file :
                        files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    for (String line : Files.readAllLines(file)) {
                        boolean logs = line.contains("log.")
                                && (line.contains("info(")
                                        || line.contains("warn(")
                                        || line.contains("error(")
                                        || line.contains("debug(")
                                        || line.contains("trace("));
                        if (logs
                                && (line.contains("customerText")
                                        || line.contains(".text()")
                                        || line.contains("reply")
                                        || line.contains("request.")
                                        || line.contains("response."))) {
                            offenders.add(ROOT.relativize(file) + ": " + line.strip());
                        }
                    }
                }
            }
        }

        assertThat(offenders)
                .as("ADR 0029: personal data -- and a customer's own words are personal data -- never reaches a log")
                .isEmpty();
    }
}
