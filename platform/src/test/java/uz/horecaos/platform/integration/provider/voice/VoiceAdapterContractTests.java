package uz.horecaos.platform.integration.provider.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup.VoiceInstallation;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.CallEventTypeCode;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.InboundCallEvent;

/**
 * The three clauses ADR 0175 adds to the VOICE adapter contract, held against both adapters this
 * build ships (the Asterisk AMI mapper and the hosted-PBX webhook mapper) through the same scripted
 * fake PBX call:
 *
 * <ol>
 *   <li>{@code ANSWERED} means a person picked up, never an IVR or voicebot leg;
 *   <li>{@code OFFERED} for a provider call id precedes its {@code ANSWERED} and {@code ENDED}, and
 *       {@code occurredAt} is non-decreasing within a call;
 *   <li>the webhook's {@code occurredAt} is the provider's event time, not the delivery time (proved
 *       for the controller in {@code HostedPbxWebhookControllerTests}; here, that the mapper keeps it).
 * </ol>
 *
 * <p>The ring interval is the one the script states, so the figure the day close later derives
 * (answer speed) is held to a known value. Neither adapter has met a live provider (ADR 0064): this
 * proves the mapping against the wire shape each documents, not that a live PBX speaks it.
 */
class VoiceAdapterContractTests {

    private static final Instant T0 = Instant.parse("2026-09-05T10:00:00Z");

    /** One adapter, as a function from a scripted call to the canonical events it would ingest. */
    private record Adapter(String name, String providerType, Function<Scripted, List<InboundCallEvent>> map) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record Scripted(FakePbxCall call, VoiceInstallation installation) {}

    static Stream<Adapter> adapters() {
        return Stream.of(
                new Adapter("asterisk", "ASTERISK_AMI", s -> s.call().throughAsterisk(s.installation())),
                new Adapter("hosted-pbx", "HOSTED_PBX", s -> s.call().throughHostedPbx(s.installation())));
    }

    private static List<InboundCallEvent> run(Adapter adapter, FakePbxCall call) {
        return adapter.map().apply(new Scripted(call, FakePbxCall.scopedInstallation(adapter.providerType())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("a call that rings seven seconds is OFFERED, then ANSWERED seven seconds later, then ENDED")
    void aRingDelayIsCarriedAsTheGapBetweenOfferedAndAnswered(Adapter adapter) {
        List<InboundCallEvent> events = run(
                adapter,
                FakePbxCall.call("call-1", T0)
                        .ringsThenAnswered(Duration.ofSeconds(7))
                        .endsAfter(Duration.ofSeconds(60)));

        assertThat(events)
                .extracting(InboundCallEvent::type)
                .containsExactly(CallEventTypeCode.OFFERED, CallEventTypeCode.ANSWERED, CallEventTypeCode.ENDED);
        assertThat(Duration.between(at(events, CallEventTypeCode.OFFERED), at(events, CallEventTypeCode.ANSWERED)))
                .isEqualTo(Duration.ofSeconds(7));
        assertThat(events).extracting(InboundCallEvent::providerCallId).containsOnly("call-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("OFFERED precedes ANSWERED and ENDED and occurredAt never decreases within a call, transfer included")
    void offeredPrecedesAndTimeNeverRunsBackwards(Adapter adapter) {
        List<InboundCallEvent> events = run(
                adapter,
                FakePbxCall.call("call-2", T0)
                        .ringsThenAnswered(Duration.ofSeconds(3))
                        .transferredAfter(Duration.ofSeconds(20), "1002", Duration.ofSeconds(40))
                        .endsAfter(Duration.ofSeconds(90)));

        assertThat(events.getFirst().type()).isEqualTo(CallEventTypeCode.OFFERED);
        assertThat(events.getLast().type()).isEqualTo(CallEventTypeCode.ENDED);
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).occurredAt())
                    .as(
                            "event %d (%s) is not before the one it follows",
                            i, events.get(i).type())
                    .isAfterOrEqualTo(events.get(i - 1).occurredAt());
        }
        // A transfer is answered twice: the day close counts the call once, on its first answer.
        assertThat(events.stream().filter(e -> e.type() == CallEventTypeCode.ANSWERED))
                .hasSize(2);
        assertThat(events.stream().filter(e -> e.type() == CallEventTypeCode.TRANSFERRED))
                .singleElement()
                .satisfies(e -> assertThat(e.transferTargetLine()).isEqualTo("1002"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("an IVR or voicebot leg is never reported as ANSWERED: the answer is the person's, after the IVR")
    void anAutomatedLegIsNotAnAnswer(Adapter adapter) {
        // The caller spends twelve seconds in the IVR before the PBX rings an operator for five more.
        List<InboundCallEvent> events = run(
                adapter,
                FakePbxCall.call("call-3", T0)
                        .ivrLegFor(Duration.ofSeconds(12))
                        .ringsThenAnswered(Duration.ofSeconds(5))
                        .endsAfter(Duration.ofSeconds(30)));

        List<InboundCallEvent> answers = events.stream()
                .filter(e -> e.type() == CallEventTypeCode.ANSWERED)
                .toList();
        assertThat(answers).hasSize(1);
        assertThat(Duration.between(
                        at(events, CallEventTypeCode.OFFERED),
                        answers.getFirst().occurredAt()))
                .as("the interval includes the IVR, as the 'Answer speed' definition says")
                .isEqualTo(Duration.ofSeconds(17));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("an unanswered call is OFFERED then MISSED, with no ANSWERED to pair")
    void aMissedCallHasNoAnswer(Adapter adapter) {
        List<InboundCallEvent> events = run(
                adapter,
                FakePbxCall.call("call-4", T0)
                        .ringsThenMissed(Duration.ofSeconds(25))
                        .endsAfter(Duration.ofSeconds(1)));

        assertThat(events).extracting(InboundCallEvent::type).doesNotContain(CallEventTypeCode.ANSWERED);
        assertThat(events).extracting(InboundCallEvent::type).contains(CallEventTypeCode.MISSED);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the hosted-webhook mapping keeps the provider's event time even when delivery was hours later")
    void theHostedMapperKeepsTheProvidersOwnEventTime() {
        Instant providerTime = Instant.parse("2026-01-01T03:00:00Z");
        FakePbxCall call = FakePbxCall.call("call-5", providerTime)
                .ringsThenAnswered(Duration.ofSeconds(9))
                .endsAfter(Duration.ofSeconds(10));

        // Delivered "now", months later: the canonical event still says when the provider says it happened.
        List<InboundCallEvent> events = call.throughHostedPbx(FakePbxCall.scopedInstallation("HOSTED_PBX"));

        assertThat(events.getFirst().occurredAt()).isEqualTo(providerTime);
        assertThat(events.get(1).occurredAt()).isEqualTo(providerTime.plusSeconds(9));
    }

    private static Instant at(List<InboundCallEvent> events, CallEventTypeCode type) {
        return events.stream()
                .filter(e -> e.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " in " + events))
                .occurredAt();
    }
}
