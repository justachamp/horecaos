/**
 * The canonical catalogue.
 *
 * English is canonical because the keys are written here first, not because it
 * is the primary audience — most of this console's users read Russian. Its key
 * set is the type every other catalogue must satisfy, so adding a key here and
 * forgetting to translate it is a compile error rather than a screen that
 * quietly says `nav.subscriptions` in production.
 *
 * Flat, dotted keys rather than nested objects: a flat record gives exact key
 * types and autocompletion, and a nested one needs a recursive path type that
 * nobody can read the error message of.
 */
export const en = {
  'app.name': 'HorecaOS',
  'app.surface': 'control plane',

  'nav.overview': 'Overview',
  'nav.tenants': 'Tenants',
  'nav.onboarding': 'Onboarding',
  'nav.subscriptions': 'Subscriptions',
  'nav.payments': 'Payments',
  'nav.statistics': 'Statistics',
  'nav.configuration': 'Platform configuration',
  'nav.staff': 'Staff and access',

  'shell.skipToContent': 'Skip to content',
  'shell.signedInAs': 'Signed in as',
  'shell.unknownOperator': 'Unknown operator',
  'shell.signOut': 'Sign out',
  'shell.signIn': 'Sign in',
  'shell.locale': 'Language',

  'locale.ru': 'Русский',
  'locale.uz-Latn': "O'zbekcha",
  'locale.en': 'English',

  'auth.starting': 'Signing in',
  'auth.signed-in': 'Signed in',
  'auth.signed-out': 'Signed out',
  'auth.unavailable': 'Sign-in unavailable',

  'state.unavailable.title': 'Sign-in is unavailable',
  'state.unavailable.body':
    'The HorecaOS realm did not answer. Nothing is wrong with your account. Try again once identity is back.',
  'state.unavailable.retry': 'Try again',

  'state.denied.title': 'You do not have access to this section',
  'state.denied.body':
    'Access is granted per capability. Ask a platform administrator for the capability this section needs.',

  'state.notBuilt.title': 'Not built yet',
  'state.notBuilt.body':
    'The foundations are in place and this section has no screen. It is specified in docs/operations-spec in the platform repository.',

  'overview.title': 'Overview',
  'overview.lead': 'What the platform looks like today, for the people who run it.',
  'overview.foundations.title': 'Foundations',
  'overview.foundations.auth': 'Authentication',
  'overview.foundations.api': 'API',
  'overview.foundations.capabilities': 'Capabilities held',
  'overview.foundations.capabilitiesUnknown': 'Not loaded',
  'overview.foundations.locale': 'Language',
  'overview.foundations.timeZone': 'Timezone',
  'overview.foundations.note':
    'This panel is diagnostic, not a dashboard. It is here so the next person can see the shell, the session and the API client working, and it is deleted when the real overview is built.',

  'tenants.title': 'Tenants',
  'tenants.lead': 'Every customer, what they pay, and which of them is not fine right now.',

  'money.uzsSuffix': "so'm",

  'error.VALIDATION_FAILED': 'Some of what was entered is not valid.',
  'error.INVALID_REQUEST': 'The platform could not act on that request.',
  'error.MALFORMED_BODY': 'The platform could not read that request.',
  'error.IDEMPOTENCY_KEY_REQUIRED': 'The request was sent without a retry key and was not applied.',
  'error.UNAUTHENTICATED': 'Your session has ended. Sign in again.',
  'error.INSUFFICIENT_CAPABILITY': 'You do not have the capability this action needs.',
  'error.ENTITLEMENT_REQUIRED': "This tenant's plan does not include this feature.",
  'error.TENANT_ACCESS_DENIED': 'You do not have access to this tenant.',
  'error.RESOURCE_NOT_FOUND': 'That no longer exists.',
  'error.RESOURCE_CONFLICT': 'That conflicts with something already recorded.',
  'error.STALE_VERSION': 'Somebody changed this while you were editing. Reload and try again.',
  'error.IDEMPOTENCY_KEY_REUSED': 'This looks like a different request sent with an old retry key.',
  'error.IDEMPOTENCY_KEY_IN_PROGRESS': 'The same request is still running. It will not be applied twice.',
  'error.PRICE_CHANGED': 'The price changed while this was open. Check it and confirm again.',
  'error.UNSUPPORTED_MEDIA_TYPE': 'That file type is not accepted.',
  'error.RATE_LIMIT_EXCEEDED': 'Too many requests. Wait a moment and try again.',
  'error.INTERNAL_ERROR': 'Something failed on the platform. It has been recorded.',
  'error.NETWORK_UNREACHABLE': 'The platform could not be reached.',
  'error.UNRECOGNISED_ERROR_RESPONSE': 'The platform answered in a way this console did not understand.',
  'error.UNKNOWN': 'Something went wrong.',
  'error.correlation': 'Reference {correlationId}',
};

/** Every key the application may ask for. */
export type MessageKey = keyof typeof en;

/** The shape every catalogue must satisfy in full. */
export type Messages = Record<MessageKey, string>;
