/**
 * English messages of the `today` area (namespaces `today`, `myWork`).
 *
 * This file defines the key set of its area: `todayRu` and `todayUzLatn`
 * are typed against it, so a key missing from either is a compile error. Which area a key belongs to is
 * decided by its prefix, in `../message-areas.ts`; `../messages.en.ts` puts the areas back together.
 */
export const todayEn = {
  // ---- IA 0.1 Live board (today-page.ts, live-board.ts) ----
  'today.myWork.link': 'My work',
  'today.loading': 'Loading the live board',
  'today.denied': 'No access to the live board',
  'today.error.retry': 'Retry',
  'today.updated': 'updated {time}',
  'today.counters.inProgress': 'In progress',
  'today.counters.cancelled': 'Cancelled',
  'today.period.businessDay': 'Cancelled and completed: this trading day, from {from}',
  'today.mix.source.title': 'By source',
  'today.mix.type.title': 'By type',
  'today.mix.empty': 'No orders in progress',
  'today.branches.title': 'Branch load',
  'today.branches.column.branch': 'Branch',
  'today.branches.column.inProgress': 'In progress',
  'today.branches.empty': 'No branch data',
  'today.branches.unavailable': 'Could not load the branch list',
  'today.branches.partial': 'Branches shown: {shown} of {total}',
  'today.operators.title': 'Operators',
  'today.operators.column.operator': 'Operator',
  'today.operators.column.accepted': 'Accepted',
  'today.operators.column.created': 'Created',
  'today.operators.unnamed': 'Unnamed colleague',
  'today.operators.empty': 'Nobody has taken an order yet today',
  'today.operators.unavailable': 'Could not load the operators',
  'today.operators.overlap':
    'One order can count in both columns: the same person may take it and accept it.',

  // ---- IA 0.2 My work (my-work-page.ts) — wave T01 ----
  'myWork.title': 'My work',
  'myWork.subtitle': 'Your own orders and takings at this branch, today only.',
  'myWork.loading': 'Loading your work',
  'myWork.error': 'Could not load this',
  'myWork.channel.title': 'My orders by channel',
  'myWork.channel.period': 'Since {from}, this trading day',
  'myWork.channel.empty': 'No orders of yours yet today',
  'myWork.payment.title': 'Revenue by payment method',
  'myWork.payment.empty': 'No takings recorded yet today',
  'myWork.locked.title': 'Not available yet',
  'myWork.locked.ask': 'Interface personalization (saved filters, layout) is not built yet.',
  'myWork.profile.body': 'Your name, phone, photo and languages are edited in your profile.',
} as const;
