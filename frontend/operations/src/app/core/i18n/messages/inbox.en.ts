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
} as const;
