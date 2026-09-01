package uz.horecaos.platform.conversations.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.conversations.api.ConversationChannelRef;
import uz.horecaos.platform.conversations.api.ConversationOutboundGateway;
import uz.horecaos.platform.conversations.api.OutboundMessage;
import uz.horecaos.platform.conversations.domain.ConversationState;
import uz.horecaos.platform.conversations.domain.FlowDocument;
import uz.horecaos.platform.conversations.domain.FlowRunStatus;
import uz.horecaos.platform.conversations.domain.FlowState;
import uz.horecaos.platform.conversations.domain.OperatorHandoffBlock;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The operator inbox's application service (ADR 0059 stage 2): list, read
 * history, reply, take a conversation over from the flow engine, return it,
 * and close it. Everything here is brand-scoped — {@code
 * conversations.conversations} has no location column — and every mutation
 * is an ADR 0027 audit fact with the real acting principal.
 *
 * <p><strong>Ordering under concurrency.</strong> Every mutation here uses
 * {@link ConversationRepository#transition}, guarded by the conversation's
 * own aggregate {@code version} (ADR 0031's {@code If-Match} discipline). The
 * two directions are ordered asymmetrically on purpose: a transition that
 * <em>stops</em> the engine ({@link #takeover} and {@link #close}) ends the
 * active flow run <em>before</em> attempting the guarded conversation update,
 * so a lost race over the conversation's version never leaves the engine
 * still able to answer. A transition that <em>starts</em> the engine again
 * ({@link #returnToFlow}) does the opposite — the guarded conversation update
 * happens first, and the run is only reactivated once that has genuinely
 * won. Either way, the engine is never left running when the record of it
 * disagrees; the failure mode this ordering accepts is the engine stopping
 * slightly early, never staying live past what the conversation row says.
 */
@Service
public class ConversationInboxService {

    private static final Logger log = LoggerFactory.getLogger(ConversationInboxService.class);

    /** Falls back to this when an operator does not type one — {@code OrderStateService}'s own idiom for an optional reason. */
    private static final String NO_REASON_GIVEN = "No reason given";

    private final ConversationRepository conversations;
    private final FlowRunRepository runs;
    private final ConversationMessageStore messages;
    private final FlowDocumentService flowDocuments;
    private final ConversationOutboundGateway outbound;
    private final ConversationEngine engine;
    private final AuditRecorder audit;
    private final EntitlementService entitlements;
    private final Clock clock;

    ConversationInboxService(
            ConversationRepository conversations,
            FlowRunRepository runs,
            ConversationMessageStore messages,
            FlowDocumentService flowDocuments,
            ConversationOutboundGateway outbound,
            ConversationEngine engine,
            AuditRecorder audit,
            EntitlementService entitlements,
            Clock clock) {
        this.conversations = conversations;
        this.runs = runs;
        this.messages = messages;
        this.flowDocuments = flowDocuments;
        this.outbound = outbound;
        this.engine = engine;
        this.audit = audit;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    // -------------------------------------------------------------- list

    /**
     * A brand's conversations, needs-attention first. No message bodies —
     * {@link ConversationSummaryView} carries only what the list screen may
     * show without decrypting anything (ADR 0059 stage 2's PII posture: "NO
     * message bodies in the list payload").
     */
    @Transactional(readOnly = true)
    public List<ConversationSummaryView> list(UUID tenantId, UUID brandId, int limit) {
        return conversations.listForBrand(tenantId, brandId, limit).stream()
                .map(ConversationSummaryView::of)
                .toList();
    }

    // ------------------------------------------------------------ history

    /** {@link #history}'s result: the conversation as it stands, and its full decrypted message history. */
    public record ConversationHistory(ConversationView conversation, List<ConversationMessageView> messages) {}

    /**
     * The conversation's full decrypted history — the inbox detail screen's
     * whole purpose. Writes a {@code conversation.history.read} ADR 0027
     * audit fact the first time this particular operator opens this
     * particular conversation (tracked by {@code last_read_by}), not on
     * every later poll of an already-open thread — seeing this conversation
     * decrypted is what needs a standing record, not each individual refresh
     * of a screen already open.
     */
    @Transactional
    public ConversationHistory history(UUID tenantId, UUID brandId, UUID conversationId, String actorSubject) {
        ConversationRepository.Row conversation = requireConversation(tenantId, brandId, conversationId);
        List<ConversationMessageStore.Row> decrypted = messages.history(tenantId, conversationId);

        boolean firstOpenByThisOperator =
                conversation.lastReadBy() == null || !conversation.lastReadBy().equals(actorSubject);
        if (firstOpenByThisOperator) {
            audit.record(AuditFact.of("conversation.history.read", AuditClass.BUSINESS)
                    .by(ActorRef.user(actorSubject, null))
                    .at(ResourceScope.brand(tenantId, brandId))
                    .target("Conversation", conversationId)
                    .because("Operator opened the conversation's decrypted message history")
                    .usingCapability(Capability.CONVERSATION_INBOX_MANAGE.code())
                    .correlatedBy(conversationId.toString())
                    .occurredAt(clock.instant())
                    .build());
        }
        conversations.markRead(tenantId, conversationId, actorSubject);

        return new ConversationHistory(
                ConversationView.of(conversation),
                decrypted.stream().map(ConversationMessageView::of).toList());
    }

    // -------------------------------------------------------------- reply

    /**
     * An operator's own reply, sent through {@link ConversationOutboundGateway}
     * and recorded with an {@code OPERATOR} direction and the acting
     * principal. Only a {@code HANDED_TO_OPERATOR} conversation may be
     * replied to — a {@code FLOW_ACTIVE} one is the engine's to answer, and
     * an operator wanting to speak instead takes it over first ({@link
     * #takeover}), which is exactly the ADR 0059 handoff model: a human
     * types back only once the conversation is theirs.
     */
    @Transactional
    public ConversationMessageView reply(
            UUID tenantId, UUID brandId, UUID conversationId, String actorSubject, String body) {
        entitlements.requireFeature(tenantId, EntitlementKeys.TELEGRAM_CONVERSATIONS_ENABLED);
        ConversationRepository.Row conversation = requireConversation(tenantId, brandId, conversationId);
        requireState(conversation, ConversationState.HANDED_TO_OPERATOR, "reply");

        boolean delivered = outbound.send(channelRef(conversation), OutboundMessage.textOnly(body));
        ConversationMessageStore.Row recorded =
                messages.recordOperatorReply(tenantId, conversationId, actorSubject, body);
        if (!delivered) {
            log.warn("Operator reply on conversation {} was not delivered", conversationId);
        }

        audit.record(AuditFact.of("conversation.reply.sent", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Conversation", conversationId)
                .because("Operator replied to the customer")
                .usingCapability(Capability.CONVERSATION_INBOX_MANAGE.code())
                .correlatedBy(conversationId.toString())
                .occurredAt(clock.instant())
                .build());

        return ConversationMessageView.of(recorded);
    }

    // ----------------------------------------------------------- takeover

    /**
     * FLOW_ACTIVE → HANDED_TO_OPERATOR by explicit operator action: an
     * operator steps in mid-flow, before the flow document itself ever
     * reaches an {@code operator-handoff} block. Ends the customer's active
     * run (status {@code HANDED_TO_OPERATOR}, same terminal status a flow
     * document's own handoff block produces) and assigns the conversation to
     * the acting operator.
     */
    @Transactional
    public ConversationView takeover(
            UUID tenantId,
            UUID brandId,
            UUID conversationId,
            long expectedVersion,
            String actorSubject,
            @Nullable String reason) {
        ConversationRepository.Row conversation = requireConversation(tenantId, brandId, conversationId);
        requireState(conversation, ConversationState.FLOW_ACTIVE, "takeover");

        runs.findActive(tenantId, conversationId)
                .ifPresent(run -> runs.end(tenantId, run.id(), run.version(), FlowRunStatus.HANDED_TO_OPERATOR));

        boolean applied = conversations.transition(
                tenantId, conversationId, expectedVersion, ConversationState.HANDED_TO_OPERATOR, actorSubject);
        if (!applied) {
            throw ApiException.staleVersion(expectedVersion, currentVersion(tenantId, conversationId));
        }

        audit.record(AuditFact.of("conversation.takeover", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Conversation", conversationId)
                .because(reasonOrDefault(reason))
                .usingCapability(Capability.CONVERSATION_INBOX_MANAGE.code())
                .correlatedBy(conversationId.toString())
                .occurredAt(clock.instant())
                .build());

        return ConversationView.of(requireConversation(tenantId, brandId, conversationId));
    }

    // ------------------------------------------------------- return to flow

    /**
     * HANDED_TO_OPERATOR → the flow resumes (ADR 0059 stage 2's own design
     * decision, documented here): looks at the block the parked run is
     * sitting at.
     *
     * <ul>
     *   <li>An {@code operator-handoff} block declaring {@code next}: the run
     *       is reactivated at {@code next} and the engine continues executing
     *       from there — {@code next} is exactly the flow author's way to say
     *       what should happen once a human releases the conversation back.
     *   <li>An {@code operator-handoff} block with no {@code next} (every
     *       flow document authored so far): re-entering the same block would
     *       just re-park the conversation, so instead it goes to {@code
     *       IDLE} rather than replaying the handoff. The run itself is left
     *       in its terminal state; a future {@code /start} begins a fresh
     *       run, the same as any other idle conversation.
     *   <li>Any other block (a takeover mid-flow, parked at whatever the
     *       customer was already waiting on): the run is reactivated at that
     *       same state without re-executing it, so the flow simply resumes
     *       waiting for the input it always was — this deliberately does not
     *       resend the block's own prompt, since the customer already saw it
     *       once and a second copy would read as a duplicate message rather
     *       than a continuation.
     * </ul>
     */
    @Transactional
    public ConversationView returnToFlow(
            UUID tenantId, UUID brandId, UUID conversationId, long expectedVersion, String actorSubject) {
        ConversationRepository.Row conversation = requireConversation(tenantId, brandId, conversationId);
        requireState(conversation, ConversationState.HANDED_TO_OPERATOR, "return-to-flow");

        Optional<FlowRunRepository.Row> handedOff = runs.mostRecentHandedOff(tenantId, conversationId);
        Plan plan = planReturn(tenantId, handedOff);

        boolean applied =
                conversations.transition(tenantId, conversationId, expectedVersion, plan.landingState(), null);
        if (!applied) {
            throw ApiException.staleVersion(expectedVersion, currentVersion(tenantId, conversationId));
        }

        if (plan.reactivateAt() != null && handedOff.isPresent()) {
            FlowRunRepository.Row run = handedOff.get();
            boolean reactivated = runs.reactivate(tenantId, run.id(), run.version(), plan.reactivateAt());
            if (!reactivated) {
                // Another concurrent return already claimed this exact run —
                // vanishingly unlikely (the conversation's own version guard
                // above already serializes ordinary double-clicks) but not
                // impossible, and the conversation row is already correctly
                // FLOW_ACTIVE either way.
                log.warn("Flow run {} could not be reactivated on return-to-flow — lost its own race", run.id());
            } else if (plan.continueExecuting()) {
                FlowDocument document = flowDocuments
                        .parsedById(tenantId, run.flowDocumentId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Flow run %s names a flow document that no longer exists".formatted(run.id())));
                engine.continueFlow(
                        channelRef(conversation),
                        conversationId,
                        run.id(),
                        document,
                        plan.reactivateAt(),
                        run.version() + 1,
                        runs.capturedFields(tenantId, run));
            }
        }

        audit.record(AuditFact.of("conversation.returned_to_flow", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Conversation", conversationId)
                .because("Operator returned the conversation to the flow")
                .usingCapability(Capability.CONVERSATION_INBOX_MANAGE.code())
                .changed(Map.of("landingState", plan.landingState().name()))
                .correlatedBy(conversationId.toString())
                .occurredAt(clock.instant())
                .build());

        return ConversationView.of(requireConversation(tenantId, brandId, conversationId));
    }

    /** Where return-to-flow should land, and whether/where to reactivate the run — see {@link #returnToFlow}'s own doc. */
    private record Plan(
            ConversationState landingState, @Nullable String reactivateAt, boolean continueExecuting) {}

    private Plan planReturn(UUID tenantId, Optional<FlowRunRepository.Row> handedOff) {
        if (handedOff.isEmpty()) {
            // No handed-off run at all — a defensive case (e.g. the run row
            // was somehow lost) rather than one this stage's own flows
            // produce. Idle is the safe landing: a future /start begins
            // fresh, same as any other idle conversation.
            return new Plan(ConversationState.IDLE, null, false);
        }
        FlowRunRepository.Row run = handedOff.get();
        Optional<FlowState> stateOpt = flowDocuments
                .parsedById(tenantId, run.flowDocumentId())
                .flatMap(document -> document.state(run.currentStateId()));
        if (stateOpt.isEmpty()) {
            return new Plan(ConversationState.IDLE, null, false);
        }
        FlowState state = stateOpt.get();
        if (state.block() instanceof OperatorHandoffBlock handoff) {
            return handoff.next() == null
                    ? new Plan(ConversationState.IDLE, null, false)
                    : new Plan(ConversationState.FLOW_ACTIVE, handoff.next(), true);
        }
        // Parked mid-flow by a takeover, not by a flow-authored handoff:
        // resume waiting for the same input, without re-sending its prompt.
        return new Plan(ConversationState.FLOW_ACTIVE, run.currentStateId(), false);
    }

    // ---------------------------------------------------------------- close

    /** → CLOSED, audited. Ends an active run (status ABANDONED) if one exists; always clears the assignment. */
    @Transactional
    public ConversationView close(
            UUID tenantId,
            UUID brandId,
            UUID conversationId,
            long expectedVersion,
            String actorSubject,
            @Nullable String reason) {
        ConversationRepository.Row conversation = requireConversation(tenantId, brandId, conversationId);
        if (conversation.state() == ConversationState.CLOSED) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This conversation is already closed");
        }

        runs.findActive(tenantId, conversationId)
                .ifPresent(run -> runs.end(tenantId, run.id(), run.version(), FlowRunStatus.ABANDONED));

        boolean applied =
                conversations.transition(tenantId, conversationId, expectedVersion, ConversationState.CLOSED, null);
        if (!applied) {
            throw ApiException.staleVersion(expectedVersion, currentVersion(tenantId, conversationId));
        }

        audit.record(AuditFact.of("conversation.closed", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Conversation", conversationId)
                .because(reasonOrDefault(reason))
                .usingCapability(Capability.CONVERSATION_INBOX_MANAGE.code())
                .correlatedBy(conversationId.toString())
                .occurredAt(clock.instant())
                .build());

        return ConversationView.of(requireConversation(tenantId, brandId, conversationId));
    }

    // --------------------------------------------------------------- shared

    private ConversationRepository.Row requireConversation(UUID tenantId, UUID brandId, UUID conversationId) {
        return conversations
                .findById(tenantId, conversationId)
                .filter(row -> row.brandId().equals(brandId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such conversation"));
    }

    private static void requireState(
            ConversationRepository.Row conversation, ConversationState required, String action) {
        if (conversation.state() != required) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Cannot %s a conversation in state %s (needs %s)".formatted(action, conversation.state(), required),
                    Map.of("actualState", conversation.state().name(), "requiredState", required.name()));
        }
    }

    private long currentVersion(UUID tenantId, UUID conversationId) {
        return conversations
                .findById(tenantId, conversationId)
                .map(ConversationRepository.Row::version)
                .orElse(-1L);
    }

    private static String reasonOrDefault(@Nullable String reason) {
        return reason == null || reason.isBlank() ? NO_REASON_GIVEN : reason;
    }

    private static ConversationChannelRef channelRef(ConversationRepository.Row conversation) {
        return new ConversationChannelRef(
                conversation.tenantId(),
                conversation.brandId(),
                conversation.installationId(),
                conversation.channel(),
                conversation.channelChatId(),
                conversation.customerAccountId());
    }
}
