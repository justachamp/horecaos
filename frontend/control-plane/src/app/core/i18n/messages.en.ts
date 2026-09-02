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
  'nav.integrations': 'Integrations',

  'shell.skipToContent': 'Skip to content',
  'shell.signedInAs': 'Signed in as',
  'shell.unknownOperator': 'Unknown operator',
  'shell.signOut': 'Sign out',
  'shell.locale': 'Language',

  'locale.ru': 'Русский',
  'locale.uz-Latn': "O'zbekcha",
  'locale.en': 'English',

  'auth.starting': 'Signing in',
  'auth.signed-in': 'Signed in',
  'auth.signed-out': 'Signed out',

  'login.title': 'Sign in',
  'login.username': 'Username or email',
  'login.password': 'Password',
  'login.submit': 'Sign in',
  'login.submitting': 'Signing in…',
  // Deliberately not error.UNAUTHENTICATED's own text ("Your session has
  // ended. Sign in again.") — that copy is written for an expired bearer on
  // an already-signed-in screen, and showing it under a login form implies a
  // session that never existed. The platform answers a wrong password and an
  // unknown username identically with that same code (ADR 0062), so this
  // wording is deliberately as uninformative about which one happened.
  'login.invalidCredentials': 'Incorrect username or password.',

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

  'integrations.title': 'Integrations',
  'integrations.lead':
    'Connect Click, Payme and Telegram, rotate their credentials, and disconnect what you no longer use. A credential can be written here but never read back (ADR 0065).',
  'integrations.loading': 'Loading…',
  'integrations.error.loadFailed': 'Could not load integrations.',

  'integrations.installations.title': 'Provider installations',
  'integrations.installations.empty': 'No providers connected yet.',
  'integrations.installations.column.provider': 'Provider',
  'integrations.installations.column.environment': 'Environment',
  'integrations.installations.column.status': 'Status',
  'integrations.installations.column.credential': 'Credential',
  'integrations.installations.column.lastRotated': 'Last rotated',
  'integrations.installations.column.actions': 'Actions',

  'integrations.merchantBindings.title': 'Merchant bindings',
  'integrations.merchantBindings.empty': 'No merchant accounts registered yet.',
  'integrations.merchantBindings.column.provider': 'Provider',
  'integrations.merchantBindings.column.account': 'Merchant account',
  'integrations.merchantBindings.column.status': 'Status',
  'integrations.merchantBindings.column.credential': 'Credential',
  'integrations.merchantBindings.column.lastRotated': 'Last rotated',
  'integrations.merchantBindings.column.actions': 'Actions',

  // Never a value, and never even the reference (ADR 0065's own wording: "the
  // UI shows a masked placeholder and 'last rotated' only"). configured/none
  // read off whether secretReference is present at all.
  'integrations.credential.configured': 'Configured •••••',
  'integrations.credential.none': 'Not set',
  'integrations.lastRotated.never': 'Never',

  'integrations.status.DRAFT': 'Draft',
  'integrations.status.ACTIVE': 'Active',
  'integrations.status.SUSPENDED': 'Suspended',
  'integrations.status.RETIRED': 'Retired',
  'integrations.status.UNVERIFIED': 'Unverified',
  'integrations.status.SUCCEEDED': 'Verified',
  'integrations.status.FAILED': 'Failed',

  'integrations.connect.action': 'Connect a provider',
  'integrations.connect.title': 'Connect a provider',
  'integrations.connect.provider': 'Provider',
  'integrations.connect.displayName': 'Display name',
  'integrations.connect.environmentCode': 'Environment',
  'integrations.connect.environmentCode.hint':
    'The approved environment code for this provider (for example a sandbox code from the connect runbook). The platform never accepts a URL directly.',
  'integrations.connect.reference': 'Reference (optional)',
  'integrations.connect.reference.hint': 'A non-secret label such as a merchant or service id, for your own records.',
  'integrations.connect.submit': 'Connect',
  'integrations.connect.submitting': 'Connecting…',
  'integrations.connect.success': 'Provider connected. Bind it to a brand or location to start using it.',
  'integrations.connect.cancel': 'Cancel',

  'integrations.rotate.installationAction': 'Rotate credential',
  'integrations.rotate.bindingAction': 'Rotate credential',
  'integrations.rotate.title': 'Rotate this credential',
  'integrations.rotate.lead':
    'The new value is written to the secrets manager and never stored anywhere else in this console, in a log, or in an error message.',
  'integrations.rotate.value': 'New credential value',
  'integrations.rotate.reason': 'Reason',
  'integrations.rotate.submit': 'Rotate',
  'integrations.rotate.submitting': 'Rotating…',
  'integrations.rotate.success': 'Credential rotated.',
  'integrations.rotate.cancel': 'Cancel',
  'integrations.rotate.unverifiedNotice':
    'This provider has no way to verify a credential before it is used. It will be written and swapped in, and marked unverified until it is proven by a real payment.',

  'integrations.archive.action': 'Archive',
  'integrations.archive.confirm': 'Archive this merchant binding? A suspended binding is archived from here.',

  'integrations.registerBinding.action': 'Register a merchant binding',
  'integrations.registerBinding.title': 'Register a merchant binding',
  'integrations.registerBinding.lead':
    'Links a legal entity to a Click or Payme account through an installation you already connected above.',
  'integrations.registerBinding.provider': 'Provider',
  'integrations.registerBinding.legalEntityId': 'Legal entity id',
  'integrations.registerBinding.installationId': 'Installation id',
  'integrations.registerBinding.integrationBindingId': 'Integration binding id',
  'integrations.registerBinding.merchantAccountReference': 'Merchant account reference',
  'integrations.registerBinding.callbackPathSegment': 'Callback path segment',
  'integrations.registerBinding.value': 'Merchant secret key',
  'integrations.registerBinding.submit': 'Register',
  'integrations.registerBinding.submitting': 'Registering…',
  'integrations.registerBinding.success': 'Merchant binding registered as draft. Activate it once it is correct.',
  'integrations.registerBinding.cancel': 'Cancel',

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
  'error.ACCOUNT_ACTION_REQUIRED':
    'This account needs one more step before it can sign in. Contact a platform administrator.',
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
