export const workEn = {
  'work.refusal.GPS_POSITION_REQUIRED':
    'Your position is needed for this step. Turn on location for this app and try again.',
  'work.refusal.GPS_ACCURACY_INSUFFICIENT':
    'Your position is too rough to confirm. Step outside or wait a moment, then try again.',
  'work.refusal.GPS_REFERENCE_UNAVAILABLE':
    'This place has no map position yet, so the step cannot be checked. Tell your dispatcher.',
  'work.refusal.TOO_FAR_FROM_PICKUP':
    'You are too far from the pickup point. Go closer and try again.',
  'work.refusal.TOO_FAR_FROM_DROPOFF':
    'You are too far from the customer’s door. Go closer and try again.',
  'work.refusal.KITCHEN_NOT_READY': 'The kitchen has not finished this order yet.',
  'work.refusal.PAYMENT_CONFIRMATION_REQUIRED':
    'Confirm the cash you collected before marking this delivered.',
  'work.refusal.PAYMENT_AMOUNT_MISMATCH':
    'That is not the amount due. Check the cash and enter the exact amount.',
  'work.refusal.LOCATION_NOT_YET_REVEALED':
    'The customer’s address is shown only after you accept the offer.',
  'work.refusal.COURIER_NOT_ELIGIBLE':
    'You cannot take new work right now. Check your work status on the Today screen.',
  'work.refusal.STEP_NOT_ALLOWED':
    'This delivery cannot take that step from where it is. Refresh and look again.',
  'work.refusal.PAYMENT_NOT_CONFIRMABLE':
    'Cash can only be confirmed after the order has been picked up.',
  'work.refusal.unknown': 'The platform did not accept this step. Refresh and try again.',
  'work.stale': 'This changed while you were looking. The latest is shown.',
  'work.gate.kind.GEO': 'location',
  'work.gate.kind.PHOTO': 'photo',
  'work.gate.kind.PAYMENT': 'payment',
  'work.gate.failed': 'This step was refused. These checks could not be confirmed: {kinds}.',
  'work.gate.failed.unnamed': 'This step was refused because a check could not be confirmed.',
  'work.gate.unconfirmed':
    'Done. The platform could not confirm: {kinds}. Your dispatcher can see this.',
  'work.gate.passed': 'Every check passed.',
  'work.customer.show': 'Show the customer’s address',
  'work.customer.hide': 'Hide the address',
  'work.customer.title': 'Customer address',
  'work.customer.entrance': 'Entrance {value}',
  'work.customer.floor': 'Floor {value}',
  'work.customer.apartment': 'Apartment {value}',
  'work.customer.instructions': 'Note for the courier: {value}',
  'work.customer.navigate': 'Open in maps',
  'work.customer.audited': 'Each time you open this address, a record is kept.',
  'work.pickup.title': 'Pickup',
  'work.landmark': 'Landmark: {value}',
  'work.cash.collect': 'Collect {amount} in cash',
  'work.cash.prepaid': 'Paid online. Collect nothing.',
  'work.kitchen.ready': 'Kitchen: ready',
  'work.kitchen.preparing': 'Kitchen: still preparing',
  'work.route': 'Route: {distance}',
  'work.destination': 'To: {label}',
  'work.promised': 'Promised by {time}',
  'work.window': 'Pick up between {start} and {end}',
  'work.order': 'Order {reference}',
} as const;
