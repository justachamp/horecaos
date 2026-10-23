export const shiftEn = {
  'shift.title': 'Shift',
  'shift.none.title': 'No shift is open',
  'shift.none.body': 'Open a shift to start receiving offers.',
  'shift.branch.label': 'Branch',
  'shift.open': 'Open shift',
  'shift.open.locationNote':
    'Your position is noted when you open a shift, if your phone shares it. A shift opens without it too.',
  'shift.state.open': 'Shift open',
  'shift.break.start': 'Start a break',
  'shift.break.end': 'End the break',
  'shift.break.note': 'On a break you are not offered work and your location is not shared.',
  'shift.close': 'Close shift',
  'shift.close.title': 'Close this shift?',
  'shift.close.body':
    'An open break ends, your location stops being shared and the shift is settled.',
  'shift.close.confirm': 'Yes, close it',
  'shift.close.cancel': 'Keep working',
  'shift.closed.title': 'Shift closed',
  'shift.closed.paid': 'Paid time (h:min): {duration}',
  'shift.closed.break': 'Break time (h:min): {duration}',
  'shift.cash.title': 'Cash to hand over',
  'shift.cash.body':
    'Count the cash in your bag and enter what you are handing over. A cashier confirms what they receive.',
  'shift.cash.label': 'Amount ({currency})',
  'shift.cash.declare': 'Declare',
  'shift.cash.invalid': 'Enter an amount, for example 125000.',
  'shift.cash.done': 'Declared. A cashier will confirm what they receive.',
  'shift.error.UNPROCESSABLE_STATE':
    'The platform did not accept this. Check your work status on the Today screen.',
  'shift.error.RESOURCE_CONFLICT': 'A shift is already open. Refresh the screen.',
  'duty.title': 'Location sharing',
  'duty.explain':
    'While you are on duty your dispatcher sees where you are, so the right orders reach you. Nothing is shared on a break or once your shift ends.',
  'duty.limit.title': 'Keep the screen on',
  'duty.limit.body':
    'This app can only read your position while it is open and the screen is on. If you lock the phone or switch to another app, sharing pauses and your pin on the dispatcher’s map goes stale. Your pay does not depend on it.',
  'duty.notice.link': 'Read the location notice',
  'duty.notice.required': 'Before you go on duty, read and accept the location notice.',
  'duty.noShift': 'Open a shift to go on duty.',
  'duty.goOn': 'Go on duty',
  'duty.goOff': 'Stop sharing location',
  'duty.on': 'You are on duty.',
  'duty.off': 'You are not on duty.',
  'duty.wakeLock.held': 'The screen is kept awake.',
  'duty.wakeLock.refused':
    'Your browser would not keep the screen awake. Keep it on yourself, or sharing stops when it locks.',
  'duty.wakeLock.released':
    'The screen lock was released. It comes back when you return to the app.',
  'duty.awaitingNotice':
    'A duty session is open from an earlier visit or another phone. Read the notice on this phone to continue.',
  'duty.endedByPlatform': 'Your duty session was ended by the platform.',
  'duty.location.denied': 'Location is blocked for this app. Allow it in your browser settings.',
  'duty.location.unavailable': 'Your phone cannot find its position right now.',
  'duty.error.NOTICE_REQUIRED': 'Before you go on duty, read and accept the location notice.',
  'duty.error.NO_OPEN_SHIFT': 'Open a shift first.',
  'duty.error.REGISTRATION_LAPSED':
    'Your registration has lapsed. Renew it with your dispatcher to go on duty.',
} as const;
