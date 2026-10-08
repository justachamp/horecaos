/**
 * English messages of the `inbox` area (namespaces `inbox`).
 *
 * This file defines the key set of its area: `inboxRu` and `inboxUzLatn`
 * are typed against it, so a key missing from either is a compile error. Which area a key belongs to is
 * decided by its prefix, in `../message-areas.ts`; `../messages.en.ts` puts the areas back together.
 */
export const inboxEn = {
  'inbox.title': 'Inbox',
  'inbox.denied': 'No access to this brand’s conversations',
  'inbox.list.empty': 'No conversations yet',

  'inbox.column.channel': 'Channel',
  'inbox.column.customer': 'Customer',
  'inbox.column.state': 'State',
  'inbox.column.lastActivity': 'Last activity',

  'inbox.customer.linked': 'Linked customer',
  'inbox.customer.unlinked': 'Not linked',
  'inbox.needsReply': 'needs reply',

  'inbox.state.IDLE': 'Idle',
  'inbox.state.FLOW_ACTIVE': 'Flow active',
  'inbox.state.HANDED_TO_OPERATOR': 'With operator',
  'inbox.state.CLOSED': 'Closed',

  'inbox.channel.TELEGRAM': 'Telegram',

  'inbox.detail.assignedTo': 'Assigned to {operator}',
  'inbox.detail.history': 'History',
  'inbox.detail.history.empty': 'No messages yet',

  'inbox.action.takeover': 'Take over',
  'inbox.action.returnToFlow': 'Return to flow',
  'inbox.action.close': 'Close',

  'inbox.reply.placeholder': 'Type a reply',
  'inbox.reply.send': 'Send',

  'inbox.message.author.customer': 'Customer',
  'inbox.message.author.operator': 'Operator',
  'inbox.message.author.flow': 'Flow',

  'inbox.message.author.assistant': 'Assistant',

  'inbox.assistant.active': 'Assistant answering',
  'inbox.assistant.involved': 'The assistant answered earlier in this conversation',
  'inbox.assistant.involvedShort': 'Assistant answered earlier',
  'inbox.assistant.banner':
    'The assistant answers this customer from your menu, branches and notes. Take over to answer yourself: it stops answering at once.',
  'inbox.action.takeoverFromAssistant': 'Take over from the assistant',

  'inbox.assistant.why': 'Why this answer',
  'inbox.assistant.why.hide': 'Hide',
  'inbox.assistant.why.loading': 'Loading how the assistant answered…',
  'inbox.assistant.why.denied': 'Your role cannot see how the assistant answered.',
  'inbox.assistant.why.error': 'Could not load how the assistant answered.',
  'inbox.assistant.why.facts': 'Facts it stood on',
  'inbox.assistant.why.facts.none': 'No facts were retrieved.',
  'inbox.assistant.why.cited': 'used in the reply',
  'inbox.assistant.why.notes': 'Your notes it used',
  'inbox.assistant.why.noteVersion': 'note, version {version}',
  'inbox.assistant.why.cached': 'Served from an answer already given for the same facts.',

  'inbox.assistant.outcome.ANSWERED': 'Answered from retrieved facts',
  'inbox.assistant.outcome.REFUSED': 'Could not answer reliably, so it handed over to a person',
  'inbox.assistant.outcome.ESCALATED': 'A topic for a person: handed over without asking the model',
  'inbox.assistant.outcome.DECLINED': 'Not answered',

  'inbox.assistant.reason.NO_GROUNDING':
    'Nothing in the menu, branches or notes answered the question',
  'inbox.assistant.reason.UNGROUNDED_REPLY':
    'The drafted reply did not match its facts, so it was discarded',
  'inbox.assistant.reason.MODEL_REFUSED': 'The facts did not answer the question',
  'inbox.assistant.reason.PROVIDER_UNAVAILABLE': 'The AI service could not be reached',
  'inbox.assistant.reason.SPEND_CEILING': 'The monthly spend ceiling was reached',
  'inbox.assistant.reason.TURN_CAP': 'The conversation reached its daily limit of assistant turns',
  'inbox.assistant.reason.ENTITLEMENT_LIMIT': 'The answers included in the plan are used up',
  'inbox.assistant.reason.RATE_LIMITED': 'Too many messages too fast',

  'inbox.assistant.fact.PRICE': 'Price',
  'inbox.assistant.fact.AVAILABILITY': 'Availability',
  'inbox.assistant.fact.BRANCH': 'Branch',
  'inbox.assistant.fact.HOURS': 'Opening hours',
  'inbox.assistant.fact.COVERAGE': 'Delivery coverage',
  'inbox.assistant.fact.ORDER': 'Order status',
  'inbox.assistant.fact.KNOWLEDGE': 'Your note',
} as const;
