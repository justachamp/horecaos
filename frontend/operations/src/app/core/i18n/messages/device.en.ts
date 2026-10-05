/**
 * English messages of the `device` area (namespaces `device`).
 *
 * This file defines the key set of its area: `deviceRu` and `deviceUzLatn`
 * are typed against it, so a key missing from either is a compile error. Which area a key belongs to is
 * decided by its prefix, in `../message-areas.ts`; `../messages.en.ts` puts the areas back together.
 */
export const deviceEn = {
  // Device shell — rows X/X.2 and X.35 (ADR 0079, ADR 0119, wave P17). Not
  // reached from the console shell: `/device`, a top-level route.
  'device.offlineBanner': 'Offline — waiting for the connection to come back',
  'device.setup.title': 'Set up this device',
  'device.setup.hint': 'One-time — enter this device’s branch before pairing it.',
  'device.setup.tenantId': 'Tenant ID',
  'device.setup.brandId': 'Brand ID',
  'device.setup.locationId': 'Branch ID',
  'device.setup.incomplete': 'Enter all three IDs.',
  'device.setup.save': 'Save',
  'device.enrol.title': 'Pair this device',
  'device.enrol.hint': 'A manager reads the code below and approves it from Kitchen → Devices.',
  'device.enrol.begin': 'Show pairing code',
  'device.enrol.beginning': 'Requesting a code…',
  'device.enrol.userCodeLabel': 'Code for the manager to type',
  'device.enrol.waiting': 'Waiting for a manager to approve this device…',
  'device.enrol.qrLabel': 'This device’s pairing code',
  'device.enrol.denied': 'This pairing request was denied. Try again.',
  'device.enrol.expired': 'This pairing code expired. Try again.',
  'device.enrol.error': 'Could not reach the platform. Try again.',
  'device.enrol.retry': 'Try again',
  'device.board.loading': 'Loading the board',
  'device.board.empty': 'No tickets on the line right now',
  'device.board.error': 'Could not load the board',
  'device.board.start': 'Start',
  'device.board.ready': 'Ready',
  'device.resetDevice': 'Reset this device',
} as const;
