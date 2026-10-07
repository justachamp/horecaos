import { MessageKey } from '../../core/i18n/messages.en';

/** The four states `ConversationState` declares. Code-owned; no tenant may reorder or extend them. */
export const CONVERSATION_STATES = ['IDLE', 'FLOW_ACTIVE', 'HANDED_TO_OPERATOR', 'CLOSED'] as const;

export type ConversationState = (typeof CONVERSATION_STATES)[number];

const KNOWN_STATES: ReadonlySet<string> = new Set(CONVERSATION_STATES);

export function isKnownConversationState(value: string): value is ConversationState {
  return KNOWN_STATES.has(value);
}

const STATE_LABEL_KEYS: Readonly<Record<ConversationState, MessageKey>> = {
  IDLE: 'inbox.state.IDLE',
  FLOW_ACTIVE: 'inbox.state.FLOW_ACTIVE',
  HANDED_TO_OPERATOR: 'inbox.state.HANDED_TO_OPERATOR',
  CLOSED: 'inbox.state.CLOSED',
};

/**
 * The label for a conversation state, known or not.
 *
 * Same forward-compatibility rule `orderStatusLabel` follows: an unfamiliar
 * value renders as the raw wire value rather than being refused, so an
 * additive server release never blanks a row this client has not learned
 * about yet.
 */
export function stateLabel(state: string, translate: (key: MessageKey) => string): string {
  return isKnownConversationState(state) ? translate(STATE_LABEL_KEYS[state]) : state;
}

/** The one channel `ConversationChannelRef` declares today (ADR 0059: "only the Telegram adapter is built"). */
const CHANNEL_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  TELEGRAM: 'inbox.channel.TELEGRAM',
};

export function channelLabel(channel: string, translate: (key: MessageKey) => string): string {
  const key = CHANNEL_LABEL_KEYS[channel];
  return key ? translate(key) : channel;
}

const ASSISTANT_OUTCOME_KEYS: Readonly<Record<string, MessageKey>> = {
  ANSWERED: 'inbox.assistant.outcome.ANSWERED',
  REFUSED: 'inbox.assistant.outcome.REFUSED',
  ESCALATED: 'inbox.assistant.outcome.ESCALATED',
  DECLINED: 'inbox.assistant.outcome.DECLINED',
};

const ASSISTANT_REASON_KEYS: Readonly<Record<string, MessageKey>> = {
  NO_GROUNDING: 'inbox.assistant.reason.NO_GROUNDING',
  UNGROUNDED_REPLY: 'inbox.assistant.reason.UNGROUNDED_REPLY',
  MODEL_REFUSED: 'inbox.assistant.reason.MODEL_REFUSED',
  PROVIDER_UNAVAILABLE: 'inbox.assistant.reason.PROVIDER_UNAVAILABLE',
  SPEND_CEILING: 'inbox.assistant.reason.SPEND_CEILING',
  TURN_CAP: 'inbox.assistant.reason.TURN_CAP',
  ENTITLEMENT_LIMIT: 'inbox.assistant.reason.ENTITLEMENT_LIMIT',
  RATE_LIMITED: 'inbox.assistant.reason.RATE_LIMITED',
};

const ASSISTANT_FACT_KEYS: Readonly<Record<string, MessageKey>> = {
  PRICE: 'inbox.assistant.fact.PRICE',
  AVAILABILITY: 'inbox.assistant.fact.AVAILABILITY',
  BRANCH: 'inbox.assistant.fact.BRANCH',
  HOURS: 'inbox.assistant.fact.HOURS',
  COVERAGE: 'inbox.assistant.fact.COVERAGE',
  ORDER: 'inbox.assistant.fact.ORDER',
  KNOWLEDGE: 'inbox.assistant.fact.KNOWLEDGE',
};

/** The words for how an assistant turn ended; an outcome this client has not learned renders as its wire value. */
export function assistantOutcomeLabel(
  outcome: string,
  translate: (key: MessageKey) => string,
): string {
  const key = ASSISTANT_OUTCOME_KEYS[outcome];
  return key ? translate(key) : outcome;
}

/** The words for why a turn was refused; same forward-compatibility rule as {@link stateLabel}. */
export function assistantReasonLabel(
  reason: string,
  translate: (key: MessageKey) => string,
): string {
  const key = ASSISTANT_REASON_KEYS[reason];
  return key ? translate(key) : reason;
}

/** The words for a kind of retrieved fact; same forward-compatibility rule as {@link stateLabel}. */
export function assistantFactLabel(kind: string, translate: (key: MessageKey) => string): string {
  const key = ASSISTANT_FACT_KEYS[kind];
  return key ? translate(key) : kind;
}
