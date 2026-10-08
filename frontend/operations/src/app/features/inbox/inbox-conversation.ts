/**
 * These interfaces mirror `ConversationInboxController`'s response records in
 * the platform directly — the same "hand-copy the Java source, not the
 * generated spec" convention `order-detail.ts` documents and follows.
 *
 * All three response shapes come out of `uz.horecaos.platform.conversations.web.
 * ConversationInboxController` (ADR 0059 stage 2).
 */

/**
 * One of the four directions a message can carry (`ConversationMessageStore.Direction`).
 * `ASSISTANT` is the grounded assistant's own voice (ADR 0069): a third author beside the flow
 * engine (`OUTBOUND`) and staff (`OPERATOR`).
 */
export type ConversationMessageDirection = 'INBOUND' | 'OUTBOUND' | 'OPERATOR' | 'ASSISTANT';

/** One of the four states a conversation can be in (`ConversationState`). */
export type ConversationStateValue = 'IDLE' | 'FLOW_ACTIVE' | 'HANDED_TO_OPERATOR' | 'CLOSED';

/**
 * `ConversationSummaryResponse` — `GET .../conversations`, one row of the
 * needs-attention-first list. Never a message body (ADR 0059 stage 2's PII
 * posture for this endpoint).
 */
export interface ConversationSummaryResponse {
  readonly conversationId: string;
  readonly channel: string;
  readonly customerAccountId?: string | null;
  readonly state: ConversationStateValue;
  readonly needsReply: boolean;
  /**
   * The assistant (ADR 0069) has answered in this conversation and nobody has taken it over:
   * it is the one answering the customer's next message. Decided by the server.
   */
  readonly assistantActive: boolean;
  /** The assistant has taken at least one turn here, whoever holds the conversation now. */
  readonly assistantInvolved: boolean;
  /** RFC 3339, UTC. */
  readonly lastActivityAt: string;
}

/**
 * `ConversationResponse` — the conversation's own header, embedded in
 * {@link ConversationDetailResponse} and returned again by every mutation
 * (takeover/return-to-flow/close) so a caller never needs a second read just
 * to learn the version a mutation left the conversation at.
 */
export interface ConversationResponse {
  readonly conversationId: string;
  readonly brandId: string;
  readonly channel: string;
  readonly customerAccountId?: string | null;
  readonly state: ConversationStateValue;
  /** The operator who currently holds this conversation, or null. */
  readonly assignedTo?: string | null;
  /** See {@link ConversationSummaryResponse.assistantActive}. */
  readonly assistantActive: boolean;
  /** See {@link ConversationSummaryResponse.assistantInvolved}. */
  readonly assistantInvolved: boolean;
  readonly updatedAt: string;
  readonly version: number;
}

/** `ConversationMessageResponse` — one decrypted message, returned by history and by a sent reply. */
export interface ConversationMessageResponse {
  readonly messageId: string;
  readonly direction: ConversationMessageDirection;
  readonly blockId?: string | null;
  /** The replying operator's subject — set only when `direction` is `OPERATOR`. */
  readonly actorPrincipalId?: string | null;
  /** The `assistant.turns` row that produced this message — set only when `direction` is `ASSISTANT`. */
  readonly assistantTurnId?: string | null;
  readonly body: string;
  readonly occurredAt: string;
}

/** `ConversationDetailResponse` — `GET .../conversations/{conversationId}`. Returns an `ETag`. */
export interface ConversationDetailResponse {
  readonly conversation: ConversationResponse;
  readonly messages: readonly ConversationMessageResponse[];
}

/** `SendReplyRequest` — `POST .../replies`. */
export interface SendReplyRequest {
  readonly body: string;
}

/** One fact an assistant turn retrieved — its kind, and whether the reply cited it. */
export interface AssistantTurnFact {
  readonly id: string;
  /** `PRICE`, `AVAILABILITY`, `BRANCH`, `HOURS`, `COVERAGE`, `ORDER` or `KNOWLEDGE`. */
  readonly kind: string;
  readonly cited: boolean;
}

/** A tenant knowledge entry version a turn stood on. */
export interface AssistantTurnKnowledgeVersion {
  readonly entryId: string;
  readonly version: number;
}

/**
 * `AssistantTurnController.TurnResponse` — `GET .../assistant/turns/{turnId}`. How a turn ended and
 * what it stood on; never any words (those stay in the conversation).
 */
export interface AssistantTurnResponse {
  readonly turnId: string;
  readonly occurredAt: string;
  readonly locale: string;
  readonly questionKinds: readonly string[];
  /** `ANSWERED`, `REFUSED`, `ESCALATED` or `DECLINED`. */
  readonly outcome: string;
  readonly refusalReason?: string | null;
  readonly modelId?: string | null;
  readonly servedFromCache: boolean;
  readonly facts: readonly AssistantTurnFact[];
  readonly knowledgeVersions: readonly AssistantTurnKnowledgeVersion[];
}
