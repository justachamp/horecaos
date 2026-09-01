/**
 * These interfaces mirror `ConversationInboxController`'s response records in
 * the platform directly — the same "hand-copy the Java source, not the
 * generated spec" convention `order-detail.ts` documents and follows.
 *
 * All three response shapes come out of `uz.horecaos.platform.conversations.web.
 * ConversationInboxController` (ADR 0059 stage 2).
 */

/** One of the three directions a message can carry (`ConversationMessageStore.Direction`). */
export type ConversationMessageDirection = 'INBOUND' | 'OUTBOUND' | 'OPERATOR';

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
