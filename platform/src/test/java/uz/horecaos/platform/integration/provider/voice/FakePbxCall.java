package uz.horecaos.platform.integration.provider.voice;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.integration.provider.voice.VoiceInstallationLookup.VoiceInstallation;
import uz.horecaos.platform.integration.provider.voice.asterisk.AsteriskAmiEventMapper;
import uz.horecaos.platform.integration.provider.voice.hostedpbx.HostedPbxEventMapper;
import uz.horecaos.platform.integration.provider.voice.hostedpbx.HostedPbxWebhookPayload;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort.InboundCallEvent;

/**
 * A scripted call against a fake PBX (ADR 0007, ADR 0175): one call's life as the PBX would report it,
 * with a configurable ring delay and an optional answered-then-transferred leg, rendered in the wire
 * shape of each adapter this build ships and mapped through that adapter's real mapper.
 *
 * <p>The script is the single source of the intervals a test then asserts, so "ring seven seconds" is
 * stated once and both the Asterisk and the hosted-webhook mapping are held to it. No scenario switch
 * reaches production code: this class is test-only, like {@code FakeAsteriskAmiServer}.
 *
 * <p>Neither mapper is given a clock of its own. {@link AsteriskAmiEventMapper} takes the receipt
 * instant from its caller (the socket client stamps it from its clock) and {@link HostedPbxEventMapper}
 * takes the provider's own {@code occurredAt}; the script supplies the instants for both so the
 * interval is deterministic and no test sleeps.
 */
public final class FakePbxCall {

    private final String callId;
    private final Instant offeredAt;
    private final List<Step> steps = new ArrayList<>();
    private Instant cursor;

    private FakePbxCall(String callId, Instant offeredAt) {
        this.callId = callId;
        this.offeredAt = offeredAt;
        this.cursor = offeredAt;
        steps.add(new Step(StepKind.OFFERED, offeredAt, null));
    }

    public static FakePbxCall call(String callId, Instant offeredAt) {
        return new FakePbxCall(callId, offeredAt);
    }

    /** An IVR or voicebot leg: the PBX reports it, and a conforming adapter does not call it an answer. */
    public FakePbxCall ivrLegFor(Duration length) {
        cursor = cursor.plus(length);
        steps.add(new Step(StepKind.IVR_LEG, cursor, null));
        return this;
    }

    /** The call rings for {@code ring} and a person picks up. */
    public FakePbxCall ringsThenAnswered(Duration ring) {
        cursor = cursor.plus(ring);
        steps.add(new Step(StepKind.ANSWERED, cursor, null));
        return this;
    }

    /** The answering person transfers the call after {@code talk}, and the target picks up after {@code ring}. */
    public FakePbxCall transferredAfter(Duration talk, String targetLine, Duration ring) {
        cursor = cursor.plus(talk);
        steps.add(new Step(StepKind.TRANSFERRED, cursor, targetLine));
        cursor = cursor.plus(ring);
        steps.add(new Step(StepKind.ANSWERED, cursor, null));
        return this;
    }

    /** Nobody picks up before {@code ring} has elapsed. */
    public FakePbxCall ringsThenMissed(Duration ring) {
        cursor = cursor.plus(ring);
        steps.add(new Step(StepKind.MISSED, cursor, null));
        return this;
    }

    public FakePbxCall endsAfter(Duration talk) {
        cursor = cursor.plus(talk);
        steps.add(new Step(StepKind.ENDED, cursor, null));
        return this;
    }

    public Instant offeredAt() {
        return offeredAt;
    }

    /** The AMI blocks this script produces, paired with the instant the client would stamp on receipt. */
    public List<AmiBlock> asAmiBlocks() {
        List<AmiBlock> blocks = new ArrayList<>();
        int sequence = 0;
        for (Step step : steps) {
            Map<String, String> fields = new LinkedHashMap<>();
            // A newer Asterisk reports a SequenceNumber; the client's dedup key uses it first, which is
            // what lets a transfer's second DialEnd on the same channel through as a second answer.
            fields.put("SequenceNumber", callId + "-" + sequence++);
            switch (step.kind) {
                case OFFERED -> {
                    fields.put("Event", "Newchannel");
                    fields.put("Uniqueid", callId);
                    fields.put("CallerIDNum", "998901234567");
                    fields.put("Exten", "1001");
                }
                case IVR_LEG -> {
                    // The PBX plays a prompt to the caller. No DialEnd: nobody was dialled.
                    fields.put("Event", "Newexten");
                    fields.put("Uniqueid", callId);
                    fields.put("Application", "Playback");
                }
                case ANSWERED -> {
                    fields.put("Event", "DialEnd");
                    fields.put("Uniqueid", callId);
                    fields.put("DialStatus", "ANSWER");
                }
                case MISSED -> {
                    fields.put("Event", "DialEnd");
                    fields.put("Uniqueid", callId);
                    fields.put("DialStatus", "NOANSWER");
                }
                case TRANSFERRED -> {
                    fields.put("Event", "BlindTransfer");
                    fields.put("TransfererUniqueid", callId);
                    fields.put("TransferTargetExten", step.target == null ? "" : step.target);
                }
                case ENDED -> {
                    fields.put("Event", "Hangup");
                    fields.put("Uniqueid", callId);
                }
            }
            blocks.add(new AmiBlock(fields, step.at));
        }
        return blocks;
    }

    /** The hosted-webhook payloads this script produces; an IVR leg is simply not reported as an event. */
    public List<HostedPbxWebhookPayload> asHostedPayloads() {
        List<HostedPbxWebhookPayload> payloads = new ArrayList<>();
        int sequence = 0;
        for (Step step : steps) {
            if (step.kind == StepKind.IVR_LEG) {
                continue;
            }
            payloads.add(new HostedPbxWebhookPayload(
                    callId + "-evt-" + sequence++,
                    step.kind.name(),
                    callId,
                    "INBOUND",
                    "+998712001234",
                    step.kind == StepKind.OFFERED ? "+998901234567" : null,
                    null,
                    step.target,
                    step.at));
        }
        return payloads;
    }

    /** Every event this script yields through the Asterisk mapper, in wire order. */
    public List<InboundCallEvent> throughAsterisk(VoiceInstallation installation) {
        return asAmiBlocks().stream()
                .map(block ->
                        AsteriskAmiEventMapper.toInboundCallEvent(block.fields(), installation, block.receivedAt()))
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toList());
    }

    /** Every event this script yields through the hosted-webhook mapper, in wire order. */
    public List<InboundCallEvent> throughHostedPbx(VoiceInstallation installation) {
        return asHostedPayloads().stream()
                .map(payload -> HostedPbxEventMapper.toInboundCallEvent(payload, installation))
                .collect(Collectors.toList());
    }

    /** A scoped installation to map through, with fresh ids. */
    public static VoiceInstallation scopedInstallation(String providerType) {
        return new VoiceInstallation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                providerType,
                "ACTIVE",
                "https://pbx.example.invalid",
                null,
                null,
                "{}");
    }

    public record AmiBlock(Map<String, String> fields, Instant receivedAt) {}

    private enum StepKind {
        OFFERED,
        IVR_LEG,
        ANSWERED,
        MISSED,
        TRANSFERRED,
        ENDED
    }

    private record Step(StepKind kind, Instant at, @Nullable String target) {}
}
