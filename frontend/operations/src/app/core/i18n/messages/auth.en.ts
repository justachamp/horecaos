/**
 * English messages of the `auth` area (namespaces `login`, `invite`, `forgotPassword`,
 * `resetPassword`).
 *
 * This file defines the key set of its area: `authRu` and `authUzLatn`
 * are typed against it, so a key missing from either is a compile error. Which area a key belongs to is
 * decided by its prefix, in `../message-areas.ts`; `../messages.en.ts` puts the areas back together.
 */
export const authEn = {
  'login.title': 'Sign in',
  'login.username': 'Username or email',
  'login.password': 'Password',
  'login.submit': 'Sign in',
  'login.submitting': 'Signing in…',

  // Deliberately not error.UNAUTHENTICATED's own text ("The session has
  // ended. Sign in again.") — that copy is written for an expired bearer on
  // an already-signed-in screen, and showing it under a login form implies a
  // session that never existed. The platform answers a wrong password and an
  // unknown username identically with that same code (ADR 0062).
  'login.invalidCredentials': 'Incorrect username or password.',
  'invite.loading': 'Checking your invitation…',
  'invite.title': 'Set up your account',
  'invite.lead': '{tenant} uses HorecaOS, and you are its owner.',
  'invite.leadStaff': '{tenant} invited you to work as {job}.',
  'invite.sentTo': 'Invitation sent to {email}',
  'invite.firstName': 'First name',
  'invite.lastName': 'Last name',
  'invite.password': 'Password',
  'invite.passwordRule': 'At least {count} characters, and not your email address.',
  'invite.confirm': 'Repeat the password',
  'invite.mismatch': 'The two passwords are different.',
  'invite.submit': 'Set up and sign in',
  'invite.submitting': 'Setting up…',
  'invite.signingIn': 'Your account is ready. Signing you in…',
  'invite.invalid.title': 'This link cannot be used',
  'invite.invalid.body':
    'It may already have been used, or a newer invitation replaced it. If you already set your password, sign in.',
  'invite.expired.title': 'This link has expired',
  'invite.expired.body':
    'Invitation links work for 72 hours. Ask the HorecaOS team to send a new one.',
  'invite.toSignIn': 'Go to sign in',
  'invite.policy.length': 'The password is too short: use at least 12 characters.',
  'invite.policy.notEmail': 'The password must not be your email address.',
  'invite.policy.history': 'Choose a password you have not used recently.',
  'invite.policy.other': 'This password does not meet the rules. Choose another.',
  'invite.failed': 'Your account could not be set up. Try again in a moment.',

  'login.forgotPassword': 'Forgot password?',

  // ADR 0098: a staff member who forgot their password.
  'forgotPassword.title': 'Reset your password',
  'forgotPassword.lead': 'Enter your username or email address and we will send you a link.',
  'forgotPassword.login': 'Username or email',
  'forgotPassword.submit': 'Send the link',
  'forgotPassword.submitting': 'Sending…',
  'forgotPassword.sent.title': 'Check your email',

  // One sentence for every outcome. The platform answers a login that names an
  // account and one that does not identically (ADR 0098), so there is nothing
  // more specific this screen could honestly say -- and saying more would turn
  // an unauthenticated endpoint into a directory of who works here.
  'forgotPassword.sent.body':
    'If the account exists, an email is on its way. The link works for 60 minutes.',
  'forgotPassword.toSignIn': 'Back to sign in',
  'forgotPassword.failed': 'The request could not be sent. Try again in a moment.',

  'resetPassword.loading': 'Checking your link…',
  'resetPassword.title': 'Choose a new password',
  'resetPassword.forAccount': 'For {account}',
  'resetPassword.password': 'New password',
  'resetPassword.passwordRule': 'At least {count} characters, and not your email address.',
  'resetPassword.confirm': 'Repeat the password',
  'resetPassword.mismatch': 'The two passwords are different.',
  'resetPassword.submit': 'Set the password',
  'resetPassword.submitting': 'Saving…',
  'resetPassword.done.title': 'Your password is set',
  'resetPassword.done.body': 'Every other session has been ended. Sign in with your new password.',
  'resetPassword.doneSessionsNotEnded.title':
    'Your password is set, but other sessions are still open',
  'resetPassword.doneSessionsNotEnded.body':
    'Your new password works. We could not end your other sessions — sign out on every other device you are signed in on, and contact support if you cannot.',
  'resetPassword.invalid.title': 'This link cannot be used',
  'resetPassword.invalid.body':
    'It may already have been used, or a newer request replaced it. Ask for a new one.',
  'resetPassword.expired.title': 'This link has expired',
  'resetPassword.expired.body': 'Reset links work for 60 minutes. Ask for a new one.',
  'resetPassword.retry.title': 'We could not check your link',
  'resetPassword.retry.body':
    'This is a problem reaching HorecaOS, not a problem with the link. It is still good — try again.',
  'resetPassword.retry.action': 'Try again',
  'resetPassword.askAgain': 'Ask for a new link',
  'resetPassword.toSignIn': 'Go to sign in',
  'resetPassword.policy.length': 'The password is too short: use at least 12 characters.',
  'resetPassword.policy.notEmail': 'The password must not be your email address.',
  'resetPassword.policy.history': 'Choose a password you have not used recently.',
  'resetPassword.policy.other': 'This password does not meet the rules. Choose another.',
  'resetPassword.failed': 'The password could not be set. Try again in a moment.',
  'login.mfa.title': 'Enter your code',
  'login.mfa.lead': 'Open your authenticator app and enter the six-digit code for HorecaOS.',
  'login.mfa.group': 'One-time code',
  'login.mfa.digit': 'Digit',
  'login.mfa.submit': 'Confirm',
  'login.mfa.submitting': 'Checking…',
  'login.mfa.back': 'Use a different account',
  'login.mfa.invalid': 'That code is not right. Check the code in your app and try again.',
  'login.mfa.rateLimited':
    'Too many code attempts for this account. Try again in about {minutes} min.',
  'login.mfa.expiredPassword':
    'The password was not accepted. Enter your username and password again.',
  'mfa.enrol.title': 'Set up two-step sign-in',
  'mfa.enrol.leadRequired':
    'Your account must use a code from an authenticator app. Set it up now to continue.',
  'mfa.enrol.lead': 'Protect your account with a code from an authenticator app.',
  'mfa.enrol.passwordStep': 'First, confirm it is you.',
  'mfa.enrol.password': 'Your current password',
  'mfa.enrol.continue': 'Continue',
  'mfa.enrol.scanStep':
    'Scan this code with an authenticator app, then enter the first code it shows.',
  'mfa.enrol.qrLabel': 'QR code for the authenticator app',
  'mfa.enrol.cantScan': 'Cannot scan? Enter this key in the app:',
  'mfa.enrol.label': 'Name this device (optional)',
  'mfa.enrol.labelPlaceholder': 'My phone',
  'mfa.enrol.confirm': 'Confirm and turn on',
  'mfa.enrol.confirming': 'Checking…',
  'mfa.enrol.group': 'First code from the app',
  'mfa.enrol.digit': 'Digit',
  'mfa.enrol.wrongPassword': 'That is not your current password.',
  'mfa.enrol.wrongCode':
    'That code did not match, so nothing was set up. Check the app and try again.',
  'mfa.enrol.expired': 'This setup has expired. Start again.',
  'mfa.enrol.full': 'An account holds at most two authenticators. Remove one first.',
  'mfa.enrol.rateLimited': 'Too many attempts. Try again in about {minutes} min.',
  'mfa.enrol.failed': 'Two-step sign-in could not be set up. Try again in a moment.',
  'mfa.enrol.noTicket': 'Sign in first to set up two-step sign-in.',
  'mfa.enrol.toSignIn': 'Back to sign-in',
} as const;
