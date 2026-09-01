package uz.horecaos.platform.conversations.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.conversations.api.ConversationChannelRef;
import uz.horecaos.platform.conversations.api.ConversationInboundPort;
import uz.horecaos.platform.conversations.api.ConversationOutboundGateway;
import uz.horecaos.platform.conversations.api.OutboundButton;
import uz.horecaos.platform.conversations.api.OutboundButtonKind;
import uz.horecaos.platform.conversations.api.OutboundMessage;
import uz.horecaos.platform.conversations.domain.ButtonsBlock;
import uz.horecaos.platform.conversations.domain.ConditionBlock;
import uz.horecaos.platform.conversations.domain.ConversationState;
import uz.horecaos.platform.conversations.domain.DelayBlock;
import uz.horecaos.platform.conversations.domain.FlowButton;
import uz.horecaos.platform.conversations.domain.FlowButtonKind;
import uz.horecaos.platform.conversations.domain.FlowDocument;
import uz.horecaos.platform.conversations.domain.FlowDocumentParser;
import uz.horecaos.platform.conversations.domain.FlowRunStatus;
import uz.horecaos.platform.conversations.domain.FlowState;
import uz.horecaos.platform.conversations.domain.FlowTemplate;
import uz.horecaos.platform.conversations.domain.InputToFieldBlock;
import uz.horecaos.platform.conversations.domain.MessageBlock;
import uz.horecaos.platform.conversations.domain.OperatorHandoffBlock;

/**
 * The flow engine (ADR 0059). Every state transition is a single {@link
 * FlowRunRepository#advance} compare-and-set — the idempotent-block-execution
 * discipline the ADR asks for — and every send happens only after that CAS
 * has already won, so a redelivered trigger (a duplicate webhook update, a
 * repeated sweep) that loses the race sends nothing.
 *
 * <p>{@code MESSAGE} and {@code CONDITION} advance the run and loop
 * immediately inside {@link #execute} — no wait, no round trip. {@code
 * BUTTONS}, {@code INPUT_TO_FIELD}, and {@code DELAY} each stop the loop:
 * the first two wait for an inbound update this class is called again for
 * ({@link #handleButtonTap}/{@link #handleText}); {@code DELAY} arms {@code
 * resume_due_at} and waits for {@link FlowRunResumeSweeper}.
 */
@Service
class ConversationEngine implements ConversationInboundPort {

    static final String WELCOME_FLOW_KEY = "welcome-series";
    private static final String STOREFRONT_URL_VARIABLE = "storefrontUrl";

    private static final Logger log = LoggerFactory.getLogger(ConversationEngine.class);

    private final ConversationRepository conversations;
    private final FlowRunRepository runs;
    private final ConversationMessageStore messages;
    private final FlowDocumentService flowDocuments;
    private final ConversationOutboundGateway outbound;
    private final Clock clock;
    private final String storefrontUrl;

    ConversationEngine(
            ConversationRepository conversations,
            FlowRunRepository runs,
            ConversationMessageStore messages,
            FlowDocumentService flowDocuments,
            ConversationOutboundGateway outbound,
            Clock clock,
            // ADR 0059's own named pre-work: "find how storefront URLs/deep
            // links are configured; a placeholder config property is
            // acceptable, documented." No storefront deep-link scheme exists
            // yet anywhere in the codebase (checked before adding this), so
            // this is that placeholder — a flat base URL a {{storefrontUrl}}
            // placeholder resolves to, not a brand- or order-aware deep link.
            @Value("${horecaos.conversations.storefront-url:https://storefront.horecaos.local}") String storefrontUrl) {
        this.conversations = conversations;
        this.runs = runs;
        this.messages = messages;
        this.flowDocuments = flowDocuments;
        this.outbound = outbound;
        this.clock = clock;
        this.storefrontUrl = storefrontUrl;
    }

    @Override
    public boolean hasActiveFlow(UUID tenantId, UUID brandId) {
        return flowDocuments.activeRow(tenantId, brandId, WELCOME_FLOW_KEY).isPresent();
    }

    @Override
    public void handleStart(ConversationChannelRef channel) {
        Optional<FlowDocumentRepository.Row> activeDoc =
                flowDocuments.activeRow(channel.tenantId(), channel.brandId(), WELCOME_FLOW_KEY);
        if (activeDoc.isEmpty()) {
            return;
        }
        ConversationRepository.Row conversation = conversations.getOrCreate(channel);
        if (runs.findActive(channel.tenantId(), conversation.id()).isPresent()) {
            // Already running — a repeat bare /start must not restart a flow
            // underneath an answer already waiting on the customer.
            return;
        }
        if (conversation.state() == ConversationState.HANDED_TO_OPERATOR) {
            // ADR 0059 stage 2: "the engine must stop answering" a parked
            // conversation is a real invariant, not just "no active run to
            // advance" — a bare /start has no active run either (the run
            // that parked it already ended), so without this check it would
            // silently start a brand-new flow underneath an operator who has
            // already taken this conversation over. Recorded like any other
            // stray inbound message instead, so the operator sees the
            // attempt rather than the flow quietly restarting.
            messages.record(
                    channel.tenantId(), conversation.id(), ConversationMessageStore.Direction.INBOUND, null, "/start");
            return;
        }

        FlowDocument document = FlowDocumentParser.parse(activeDoc.get().documentYaml());
        FlowRunRepository.Row run;
        try {
            run = runs.start(
                    channel.tenantId(),
                    conversation.id(),
                    activeDoc.get().id(),
                    activeDoc.get().version(),
                    document.startState());
        } catch (DataIntegrityViolationException concurrentStart) {
            // uq_flow_run_one_active lost the race to a concurrent /start on
            // the same chat — the other one is already running the flow.
            return;
        }
        conversations.updateState(channel.tenantId(), conversation.id(), ConversationState.FLOW_ACTIVE);
        execute(channel, conversation.id(), run.id(), document, document.startState(), run.version(), Map.of());
    }

    @Override
    public void handleText(ConversationChannelRef channel, String text) {
        Active active = activeInputAwaitingRun(channel);
        if (active == null || !(active.state.block() instanceof InputToFieldBlock inputToField)) {
            // No run is actually waiting on text right now — a parked
            // (HANDED_TO_OPERATOR) or closed conversation, a run mid-delay,
            // or one waiting on a button tap instead. ADR 0059 stage 2: the
            // engine still stays quiet (nothing sent), but the message must
            // still land in history so the inbox — not silence — is what the
            // customer's next message actually reaches.
            recordStrayInbound(channel, text);
            return;
        }
        boolean advanced = runs.advance(
                channel.tenantId(),
                active.run.id(),
                active.run.version(),
                inputToField.next(),
                null,
                inputToField.field(),
                text,
                active.captured);
        if (!advanced) {
            return;
        }
        messages.record(
                channel.tenantId(),
                active.conversation.id(),
                ConversationMessageStore.Direction.INBOUND,
                active.state.id(),
                text);

        Map<String, String> merged = new LinkedHashMap<>(active.captured);
        merged.put(inputToField.field(), text);
        execute(
                channel,
                active.conversation.id(),
                active.run.id(),
                active.document,
                inputToField.next(),
                active.run.version() + 1,
                merged);
    }

    @Override
    public void handleButtonTap(ConversationChannelRef channel, String buttonKey) {
        Active active = activeInputAwaitingRun(channel);
        if (active == null || !(active.state.block() instanceof ButtonsBlock buttons)) {
            // Same reasoning as handleText's fallback: no run is waiting on a
            // tap right now, but the tap is still a customer signal the
            // inbox must be able to see.
            recordStrayInbound(channel, "[" + buttonKey + "]");
            return;
        }
        FlowButton tapped = buttons.buttons().stream()
                .filter(button -> button.kind() == FlowButtonKind.CALLBACK && buttonKey.equals(button.key()))
                .findFirst()
                .orElse(null);
        if (tapped == null) {
            // A stale tap on a superseded run, or a key that no longer
            // matches this block — still worth recording (see above), but
            // not a no-op the port's contract lets a caller ignore.
            recordStrayInbound(channel, "[" + buttonKey + "]");
            return;
        }
        // FlowDocumentValidator guarantees a CALLBACK button always carries
        // next; NullAway cannot see that authoring-time guarantee here.
        String targetState = java.util.Objects.requireNonNull(tapped.next());
        boolean advanced = runs.advance(
                channel.tenantId(),
                active.run.id(),
                active.run.version(),
                targetState,
                null,
                null,
                null,
                active.captured);
        if (!advanced) {
            return;
        }
        messages.record(
                channel.tenantId(),
                active.conversation.id(),
                ConversationMessageStore.Direction.INBOUND,
                active.state.id(),
                "[" + tapped.label() + "]");
        execute(
                channel,
                active.conversation.id(),
                active.run.id(),
                active.document,
                targetState,
                active.run.version() + 1,
                active.captured);
    }

    // ------------------------------------------------------- return to flow

    /**
     * Continues a run {@code ConversationInboxService} has just reactivated
     * from a {@code HANDED_TO_OPERATOR} operator-handoff block's {@code
     * next} — the return-to-flow counterpart of {@link #resumeDelayed}.
     * Called only after the caller has already flipped the conversation to
     * {@code FLOW_ACTIVE} and won {@link FlowRunRepository#reactivate}'s own
     * CAS, so every send here is exactly as safe as any other step of {@link
     * #execute}.
     */
    void continueFlow(
            ConversationChannelRef channel,
            UUID conversationId,
            UUID runId,
            FlowDocument document,
            String stateId,
            long runVersion,
            Map<String, String> captured) {
        execute(channel, conversationId, runId, document, stateId, runVersion, captured);
    }

    // -------------------------------------------------------------- resume

    /** Called only by {@link FlowRunResumeSweeper} for a run whose delay has genuinely elapsed. */
    void resumeDelayed(
            ConversationChannelRef channel,
            FlowRunRepository.Row run,
            FlowDocument document,
            DelayBlock delay,
            Map<String, String> captured) {
        boolean advanced =
                runs.advance(channel.tenantId(), run.id(), run.version(), delay.next(), null, null, null, captured);
        if (!advanced) {
            return;
        }
        execute(channel, run.conversationId(), run.id(), document, delay.next(), run.version() + 1, captured);
    }

    // --------------------------------------------------------------- engine

    /**
     * Runs the auto-advancing chain from {@code stateId} onward, stopping at
     * the first block that waits for something external (a tap, free text,
     * or a delay) or at a terminal block. Every step here has already won its
     * CAS by the time it sends — see the class doc.
     */
    private void execute(
            ConversationChannelRef channel,
            UUID conversationId,
            UUID runId,
            FlowDocument document,
            String startStateId,
            long startVersion,
            Map<String, String> capturedFields) {
        String stateId = startStateId;
        long version = startVersion;
        Map<String, String> captured = capturedFields;
        UUID tenantId = channel.tenantId();

        while (true) {
            if (FlowDocument.END.equals(stateId)) {
                complete(tenantId, conversationId, runId, version);
                return;
            }
            Optional<FlowState> stateOpt = document.state(stateId);
            if (stateOpt.isEmpty()) {
                log.error("Flow run {} pointed at undeclared state \"{}\"; abandoning it", runId, stateId);
                runs.end(tenantId, runId, version, FlowRunStatus.ABANDONED);
                return;
            }
            FlowState state = stateOpt.get();

            switch (state.block()) {
                case MessageBlock message -> {
                    send(channel, conversationId, state.id(), message.text(), captured);
                    if (message.next() == null) {
                        complete(tenantId, conversationId, runId, version);
                        return;
                    }
                    if (!runs.advance(tenantId, runId, version, message.next(), null, null, null, captured)) {
                        return;
                    }
                    stateId = message.next();
                    version++;
                }
                case ConditionBlock condition -> {
                    String target = evaluate(condition, captured) ? condition.whenTrue() : condition.whenFalse();
                    if (!runs.advance(tenantId, runId, version, target, null, null, null, captured)) {
                        return;
                    }
                    stateId = target;
                    version++;
                }
                case ButtonsBlock buttons -> {
                    sendButtons(channel, conversationId, state.id(), buttons, captured);
                    return;
                }
                case InputToFieldBlock inputToField -> {
                    send(channel, conversationId, state.id(), inputToField.prompt(), captured);
                    return;
                }
                case DelayBlock delay -> {
                    Instant dueAt = clock.instant().plus(delay.duration());
                    // Same stateId, only resume_due_at changes — the sweeper,
                    // not this call, is what advances past a delay block.
                    runs.advance(tenantId, runId, version, stateId, dueAt, null, null, captured);
                    return;
                }
                case OperatorHandoffBlock handoff -> {
                    if (handoff.message() != null) {
                        send(channel, conversationId, state.id(), handoff.message(), captured);
                    }
                    if (runs.end(tenantId, runId, version, FlowRunStatus.HANDED_TO_OPERATOR)) {
                        conversations.updateState(tenantId, conversationId, ConversationState.HANDED_TO_OPERATOR);
                    }
                    return;
                }
            }
        }
    }

    private void complete(UUID tenantId, UUID conversationId, UUID runId, long version) {
        if (runs.end(tenantId, runId, version, FlowRunStatus.COMPLETED)) {
            conversations.updateState(tenantId, conversationId, ConversationState.IDLE);
        }
    }

    private boolean evaluate(ConditionBlock condition, Map<String, String> captured) {
        String value = captured.get(condition.field());
        return switch (condition.operator()) {
            case PRESENT -> value != null;
            case ABSENT -> value == null;
            case EQUALS -> value != null && value.equals(condition.value());
        };
    }

    private void send(
            ConversationChannelRef channel,
            UUID conversationId,
            String blockId,
            String template,
            Map<String, String> captured) {
        String rendered = FlowTemplate.render(template, withSystemVariables(captured));
        boolean delivered = outbound.send(channel, OutboundMessage.textOnly(rendered));
        messages.record(
                channel.tenantId(), conversationId, ConversationMessageStore.Direction.OUTBOUND, blockId, rendered);
        if (!delivered) {
            log.warn("Flow send from block {} was not delivered", blockId);
        }
    }

    private void sendButtons(
            ConversationChannelRef channel,
            UUID conversationId,
            String blockId,
            ButtonsBlock block,
            Map<String, String> captured) {
        Map<String, String> variables = withSystemVariables(captured);
        String rendered = FlowTemplate.render(block.text(), variables);
        List<OutboundButton> outboundButtons = block.buttons().stream()
                .map(button -> button.kind() == FlowButtonKind.URL
                        ? new OutboundButton(
                                button.label(),
                                OutboundButtonKind.URL,
                                FlowTemplate.render(java.util.Objects.requireNonNull(button.url()), variables))
                        : new OutboundButton(
                                button.label(),
                                OutboundButtonKind.CALLBACK,
                                java.util.Objects.requireNonNull(button.key())))
                .toList();
        boolean delivered = outbound.send(channel, new OutboundMessage(rendered, outboundButtons));
        messages.record(
                channel.tenantId(), conversationId, ConversationMessageStore.Direction.OUTBOUND, blockId, rendered);
        if (!delivered) {
            log.warn("Flow buttons send from block {} was not delivered", blockId);
        }
    }

    private Map<String, String> withSystemVariables(Map<String, String> captured) {
        Map<String, String> variables = new LinkedHashMap<>(captured);
        variables.put(STOREFRONT_URL_VARIABLE, storefrontUrl);
        return variables;
    }

    /**
     * Records an inbound message the engine has nothing to do with right now
     * (ADR 0059 stage 2) — a parked, closed, or between-runs conversation, or
     * one whose active run is not currently waiting on this kind of input.
     * Reopens a {@code CLOSED} conversation to {@code HANDED_TO_OPERATOR}
     * rather than leaving a customer's new message sitting against a thread
     * staff have already closed and have no reason to look at again; a
     * conversation already {@code HANDED_TO_OPERATOR} needs no state change,
     * since it already reads as needing attention, and one still {@code
     * FLOW_ACTIVE} is left exactly as is — the run may simply be waiting on
     * something else (a delay, a different button), and the inbox's own
     * needs-reply computation is what surfaces this message either way.
     *
     * <p>A channel identity with no conversation row at all (never reached
     * {@link #handleStart} or a customer link) has nothing to record against
     * and is left as the silent no-op it always was.
     */
    private void recordStrayInbound(ConversationChannelRef channel, String body) {
        Optional<ConversationRepository.Row> conversationOpt =
                conversations.find(channel.tenantId(), channel.brandId(), channel.channel(), channel.externalChatId());
        if (conversationOpt.isEmpty()) {
            return;
        }
        ConversationRepository.Row conversation = conversationOpt.get();
        messages.record(channel.tenantId(), conversation.id(), ConversationMessageStore.Direction.INBOUND, null, body);
        if (conversation.state() == ConversationState.CLOSED) {
            conversations.updateState(channel.tenantId(), conversation.id(), ConversationState.HANDED_TO_OPERATOR);
        }
    }

    /** The run, its document, its current state, and its captured fields — resolved once for a text/tap handler. */
    private @Nullable Active activeInputAwaitingRun(ConversationChannelRef channel) {
        Optional<ConversationRepository.Row> conversationOpt =
                conversations.find(channel.tenantId(), channel.brandId(), channel.channel(), channel.externalChatId());
        if (conversationOpt.isEmpty()) {
            return null;
        }
        ConversationRepository.Row conversation = conversationOpt.get();
        Optional<FlowRunRepository.Row> runOpt = runs.findActive(channel.tenantId(), conversation.id());
        if (runOpt.isEmpty()) {
            return null;
        }
        FlowRunRepository.Row run = runOpt.get();
        Optional<FlowDocument> documentOpt = flowDocuments.parsedById(channel.tenantId(), run.flowDocumentId());
        if (documentOpt.isEmpty()) {
            return null;
        }
        FlowDocument document = documentOpt.get();
        Optional<FlowState> stateOpt = document.state(run.currentStateId());
        if (stateOpt.isEmpty()) {
            return null;
        }
        Map<String, String> captured = runs.capturedFields(channel.tenantId(), run);
        return new Active(conversation, run, document, stateOpt.get(), captured);
    }

    private record Active(
            ConversationRepository.Row conversation,
            FlowRunRepository.Row run,
            FlowDocument document,
            FlowState state,
            Map<String, String> captured) {}
}
