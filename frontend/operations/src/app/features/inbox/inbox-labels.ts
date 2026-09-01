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
