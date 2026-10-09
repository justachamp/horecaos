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
  'device.enrol.kindTitle': 'What is this screen?',
  'device.enrol.begin': 'Cook’s touch board',
  'device.enrol.beginWall': 'Wall display (view only)',
  'device.enrol.manual': 'Enter the branch by hand instead',
  'device.enrol.beginning': 'Requesting a code…',
  'device.enrol.userCodeLabel': 'Code for the manager to type',
  'device.enrol.waiting': 'Waiting for a manager to approve this device…',
  'device.enrol.qrLabel': 'This device’s pairing code',
  'device.enrol.denied': 'This pairing request was denied. Try again.',
  'device.enrol.expired': 'This pairing code expired. Try again.',
  'device.enrol.error': 'Could not reach the platform. Try again.',
  'device.enrol.retry': 'Try again',
  'device.profile.loading': 'Reading this device’s record…',
  'device.profile.failed':
    'Could not read this device’s record. Check the connection, or enter the branch by hand.',
  'device.board.loading': 'Loading the board',
  'device.board.empty': 'No tickets on the line right now',
  'device.board.error': 'Could not load the board',
  'device.board.start': 'Start',
  'device.board.ready': 'Ready',
  'device.resetDevice': 'Reset this device',

  // ------------------------------------------------------------------- terminal unlock and the kiosk mode (ADR 0155, ADR 0162)
  'device.enrol.beginKiosk': 'This is a self-service kiosk',
  'device.enrol.waitingKiosk':
    'A manager scans the QR with a phone, or types this code in the console, and confirms that it matches this screen.',
  'device.enrol.countdown': 'The code works for {time}. A new one appears by itself.',
  'device.kiosk.title': 'Self-service kiosk',
  'device.kiosk.channel': 'Sales channel',
  'device.kiosk.branch': 'Branch',
  'device.kiosk.channelDown':
    'The channel is switched off at this branch. The kiosk sells nothing until a manager fixes it.',
  'device.kiosk.ready': 'Paired and ready. Nothing needs to be done on this screen.',
  'device.kiosk.unbound':
    'This kiosk has no sales channel. A manager must choose one in the console.',
  'device.terminal.keypad.clear': 'Clear',
  'device.terminal.keypad.ok': 'OK',
  'device.terminal.who': 'Who is working?',
  'device.terminal.rosterEmpty':
    'Nobody is set up to unlock this screen. A manager can add people in the console.',
  'device.terminal.state.NOT_SET': 'Set a PIN',
  'device.terminal.state.LOCKED': 'Locked',
  'device.terminal.enterPin': '{who}, enter your PIN',
  'device.terminal.haveCode': 'I have an activation code',
  'device.terminal.refused': 'That PIN did not work.',
  'device.terminal.lockedMinutes': 'Too many tries. Try again in {minutes} min.',
  'device.terminal.lockedUntilReset': 'This PIN is locked. Ask your manager to reset it.',
  'device.terminal.noPin':
    'You have no PIN yet. Ask your manager for an activation code, or get one in your profile.',
  'device.terminal.setPin': 'Set a PIN with a code',
  'device.terminal.back': 'Back',
  'device.terminal.close': 'Close',
  'device.terminal.enterCode': 'Enter the activation code',
  'device.terminal.codeRefused':
    'That code was not accepted. It may have been used or have expired.',
  'device.terminal.chooseNewPin': 'Choose a PIN of {digits} digits',
  'device.terminal.confirmNewPin': 'Enter the PIN again',
  'device.terminal.mismatch': 'The two PINs differ. Start again.',
  'device.terminal.enterCurrentPin': 'Enter your current PIN',
  'device.terminal.pinChanged': 'Your PIN was changed.',
  'device.terminal.error': 'Could not reach the server. Try again.',
  'device.terminal.rule.NOT_DIGITS': 'A PIN is digits only.',
  'device.terminal.rule.WRONG_LENGTH': 'A PIN has {digits} digits.',
  'device.terminal.rule.REPEATED_DIGIT': 'A PIN of one repeated digit is easy to guess.',
  'device.terminal.rule.SEQUENCE': 'A run like 1234 or 4321 is easy to guess.',
  'device.terminal.rule.COMMON': 'That PIN is too common.',
  'device.terminal.rule.PERSONAL_NUMBER':
    'A PIN must not be the end of your phone or employee number.',
  'device.terminal.rule.OTHER': 'That PIN is not allowed.',
  'device.terminal.changePin': 'Change PIN',
  'device.terminal.lock': 'Lock',
  'device.terminal.unlock': 'Unlock',
  'device.terminal.nobody': 'Nobody is unlocked',
  'device.terminal.refusal.TERMINAL_UNLOCK_REQUIRED': 'Unlock the tablet first.',
  'device.terminal.refusal.TERMINAL_SESSION_ENDED': 'The unlock ended. Unlock again.',
  'device.terminal.refusal.TERMINAL_PERSON_NOT_AUTHORIZED': 'Your job does not allow that here.',
} as const;
