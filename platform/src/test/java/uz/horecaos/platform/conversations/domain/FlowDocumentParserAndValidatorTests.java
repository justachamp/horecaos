package uz.horecaos.platform.conversations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 0059: a broken flow document must be rejected at authoring time — "a
 * broken flow must never be discoverable only at runtime." These tests never
 * touch a database or Spring context; {@link FlowDocumentParser} and {@link
 * FlowDocumentValidator} are pure functions over text.
 */
class FlowDocumentParserAndValidatorTests {

    private static final String VALID_DOCUMENT = """
            flowKey: welcome-series
            name: Welcome series
            startState: greeting
            states:
              greeting:
                type: buttons
                text: "Welcome! Order now, or tell us what you think."
                buttons:
                  - label: "Order now"
                    kind: url
                    url: "{{storefrontUrl}}"
                  - label: "Leave feedback"
                    kind: callback
                    key: feedback
                    next: awaiting_feedback
              awaiting_feedback:
                type: input-to-field
                prompt: "Please share your feedback."
                field: feedback
                next: routing
              routing:
                type: condition
                field: feedback
                operator: present
                whenTrue: thank_you
                whenFalse: thank_you
              thank_you:
                type: message
                text: "Thank you!"
            """;

    @Test
    @DisplayName("a valid document parses to all six block types and validates clean")
    void validDocumentParsesToAllSixBlockTypes() {
        FlowDocument document = FlowDocumentParser.parse(VALID_DOCUMENT);
        FlowDocumentValidator.validate(document);

        assertThat(document.flowKey()).isEqualTo("welcome-series");
        assertThat(document.startState()).isEqualTo("greeting");
        assertThat(document.states()).hasSize(4);
        assertThat(document.state("greeting").orElseThrow().block()).isInstanceOf(ButtonsBlock.class);
        assertThat(document.state("awaiting_feedback").orElseThrow().block()).isInstanceOf(InputToFieldBlock.class);
        assertThat(document.state("routing").orElseThrow().block()).isInstanceOf(ConditionBlock.class);
        assertThat(document.state("thank_you").orElseThrow().block()).isInstanceOf(MessageBlock.class);

        // The remaining two block types, exercised for their own parse shape
        // (a document combining every one of the six is not itself a
        // meaningful flow, so they are checked in isolation below).
        FlowDocument withDelay = FlowDocumentParser.parse("""
                flowKey: with-delay
                name: With delay
                startState: waiting
                states:
                  waiting:
                    type: delay
                    duration: PT1H
                    next: done
                  done:
                    type: operator-handoff
                    message: "A person will take it from here."
                """);
        assertThat(withDelay.state("waiting").orElseThrow().block()).isInstanceOf(DelayBlock.class);
        assertThat(withDelay.state("done").orElseThrow().block()).isInstanceOf(OperatorHandoffBlock.class);
    }

    @Test
    @DisplayName("an unknown block type is rejected with an actionable error")
    void unknownBlockTypeIsRejected() {
        assertThatThrownBy(() -> FlowDocumentParser.parse("""
                        flowKey: bad
                        name: Bad
                        startState: only
                        states:
                          only:
                            type: carrier-pigeon
                            text: "hi"
                        """))
                .isInstanceOf(FlowDocumentException.class)
                .hasMessageContaining("unknown block type")
                .hasMessageContaining("carrier-pigeon");
    }

    @Test
    @DisplayName("a missing required field is rejected, naming the state and the field")
    void missingFieldIsRejected() {
        assertThatThrownBy(() -> FlowDocumentParser.parse("""
                        flowKey: bad
                        name: Bad
                        startState: only
                        states:
                          only:
                            type: message
                        """))
                .isInstanceOf(FlowDocumentException.class)
                .hasMessageContaining("only")
                .hasMessageContaining("text");
    }

    @Test
    @DisplayName("a transition naming a state that does not exist is rejected")
    void missingStateTargetIsRejected() {
        FlowDocument document = FlowDocumentParser.parse("""
                flowKey: bad
                name: Bad
                startState: greeting
                states:
                  greeting:
                    type: message
                    text: "hi"
                    next: nowhere
                """);

        assertThatThrownBy(() -> FlowDocumentValidator.validate(document))
                .isInstanceOf(FlowDocumentException.class)
                .hasMessageContaining("nowhere")
                .hasMessageContaining("not a declared state");
    }

    @Test
    @DisplayName("startState itself must be declared")
    void undeclaredStartStateIsRejected() {
        FlowDocument document = FlowDocumentParser.parse("""
                flowKey: bad
                name: Bad
                startState: missing
                states:
                  greeting:
                    type: message
                    text: "hi"
                """);

        assertThatThrownBy(() -> FlowDocumentValidator.validate(document))
                .isInstanceOf(FlowDocumentException.class)
                .hasMessageContaining("startState");
    }

    @Test
    @DisplayName("a cycle with no exit — every state auto-advances, none of them waits — is rejected")
    void cycleWithNoExitIsRejected() {
        FlowDocument document = FlowDocumentParser.parse("""
                flowKey: bad
                name: Bad
                startState: a
                states:
                  a:
                    type: message
                    text: "a"
                    next: b
                  b:
                    type: message
                    text: "b"
                    next: a
                """);

        assertThatThrownBy(() -> FlowDocumentValidator.validate(document))
                .isInstanceOf(FlowDocumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("a cycle broken by a buttons block (which waits for a tap) is not rejected")
    void cycleBrokenByAWaitingBlockIsAccepted() {
        FlowDocument document = FlowDocumentParser.parse("""
                flowKey: fine
                name: Fine
                startState: a
                states:
                  a:
                    type: buttons
                    text: "pick one"
                    buttons:
                      - label: "loop"
                        kind: callback
                        key: loop
                        next: a
                      - label: "leave"
                        kind: callback
                        key: leave
                        next: END
                """);

        FlowDocumentValidator.validate(document);
    }

    @Test
    @DisplayName("a state may not be named END — the sentinel is reserved")
    void stateNamedEndIsRejected() {
        FlowDocument document = FlowDocumentParser.parse("""
                flowKey: bad
                name: Bad
                startState: END
                states:
                  END:
                    type: message
                    text: "oops"
                """);

        assertThatThrownBy(() -> FlowDocumentValidator.validate(document))
                .isInstanceOf(FlowDocumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    @DisplayName("a buttons block mixing url and callback fields incorrectly is rejected")
    void malformedButtonIsRejected() {
        assertThatThrownBy(() -> FlowDocumentParser.parse("""
                        flowKey: bad
                        name: Bad
                        startState: only
                        states:
                          only:
                            type: buttons
                            text: "hi"
                            buttons:
                              - label: "broken"
                                kind: callback
                        """))
                .isInstanceOf(FlowDocumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    @DisplayName("template rendering substitutes a known variable and leaves an unknown one verbatim")
    void templateRendering() {
        assertThat(FlowTemplate.render("Order at {{storefrontUrl}}!", java.util.Map.of("storefrontUrl", "https://x")))
                .isEqualTo("Order at https://x!");
        assertThat(FlowTemplate.render("Hi {{unknown}}", java.util.Map.of())).isEqualTo("Hi {{unknown}}");
    }
}
