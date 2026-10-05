/**
 * English messages of the `wallboard` area (namespaces `wallboard`, `wallboardKitchen`,
 * `wallboardVdu`).
 *
 * This file defines the key set of its area: `wallboardRu` and `wallboardUzLatn`
 * are typed against it, so a key missing from either is a compile error. Which area a key belongs to is
 * decided by its prefix, in `../message-areas.ts`; `../messages.en.ts` puts the areas back together.
 */
export const wallboardEn = {
  // ---- IA 0.1e / X/X.3 Wallboard (wallboard-shell.ts) — wave T23 ----
  'wallboard.title': 'Live board',
  'wallboard.denied': 'No access to the live board',
  'wallboard.counters.inProgress': 'In progress',
  'wallboard.counters.cancelled': 'Cancelled',
  'wallboard.mix.source.title': 'By source',
  'wallboard.mix.type.title': 'By type',
  'wallboard.mix.empty': 'No orders in progress',
  'wallboard.branches.title': 'Branch load',
  'wallboard.branches.empty': 'No branch data',
  'wallboard.branches.unavailable': 'Could not load the branch list',
  'wallboard.operators.deferred':
    'Operators are not shown on the shared screen: whether it may name individual employees is not decided yet.',
  'wallboard.fullscreen.enter': 'Enter fullscreen',
  'wallboard.freshness.loading': 'Connecting…',
  'wallboard.freshness.seconds': 'Updated {seconds}s ago',
  'wallboard.freshness.minutes': 'Updated {minutes} min ago',

  // Kitchen wallboard shell (2.1/2.4, ADR 0045/0041 rollout step 4) — touch
  // KDS and VDU, hosted like the wallboard (0.1e) rather than the operator
  // console.
  'wallboardKitchen.title': 'Kitchen',
  'wallboardKitchen.denied': 'No access to this location’s kitchen board',
  'wallboardKitchen.loading': 'Loading the kitchen board',
  'wallboardKitchen.empty': 'Nothing in production right now',
  'wallboardKitchen.offline': 'Disconnected — showing the last board this screen saw',
  'wallboardKitchen.actionError': 'That did not go through. Try again.',
  'wallboardVdu.title': 'Display board',
  'wallboardVdu.denied': 'No access to this location’s kitchen board',
  'wallboardVdu.loading': 'Loading the display board',
  'wallboardVdu.empty': 'Nothing in production right now',
  'wallboardVdu.offline': 'Disconnected — showing the last board this screen saw',
  'wallboardVdu.stationFilter.label': 'Station',
  'wallboardVdu.stationFilter.all': 'All stations',
} as const;
