import { MessageKey } from '../../core/i18n/messages.en';
import { ChannelView } from './marketing-api';

/**
 * Why a channel cannot carry this brand's marketing, in words (ADR 0146 Decision 8).
 *
 * The platform answers with a stable code, never a sentence, because the sentence belongs to
 * whoever reads it. Each code below is one the server can produce:
 * `SMS_PURPOSE_NOT_PERMITTED` and its siblings come from the notification transport's readiness
 * check (the binding, the installation's status and the account's own configuration), and
 * `NO_DELIVERY_ADAPTER` from a channel that has no delivery in this release at all.
 *
 * **SMS is honest about its gate.** An SMS account is a commercial relationship, and the provider
 * may have cleared it for sign-in codes and order messages without agreeing to carry promotions.
 * Until the platform owner answers in writing which account may carry marketing and names it in
 * the installation's `permittedPurposes`, a marketing SMS is refused at launch, and the console
 * says so before an author spends two signatures on it, instead of after.
 *
 * **Email is honest about its relay.** A campaign email leaves through the platform's own mail
 * relay (ADR 0097, ADR 0182): there is no per-brand account to connect, so the only reasons it
 * cannot send are `EMAIL_NOT_CONFIGURED` (this deployment has no mail server set up) and
 * `EMAIL_PURPOSE_NOT_PERMITTED` (the relay carries campaign email and nothing else).
 *
 * **Push is honest about its identity.** A campaign push leaves through the platform's own Web Push
 * (ADR 0182, D14): there is no per-brand account either, so the only reasons it cannot send are
 * `PUSH_NOT_CONFIGURED` (this deployment has no sending key pair and contact set up) and
 * `PUSH_PURPOSE_NOT_PERMITTED` (it carries campaign push and nothing else). Whether a *customer* can
 * receive one is not a wiring question: it is consent and a subscription, said per recipient.
 *
 * **A courier SMS has its own reasons and its own sentences** (`courierWiringSentence`): the same
 * account codes mean something different there. An SMS account that cannot carry marketing is a
 * different finding from one that cannot carry a dispatcher's message to a courier.
 */
const WIRING_KEYS: Readonly<Record<string, MessageKey>> = {
  SMS_PURPOSE_NOT_PERMITTED: 'marketing.wiring.SMS_PURPOSE_NOT_PERMITTED',
  NO_PROVIDER_BINDING: 'marketing.wiring.NO_PROVIDER_BINDING',
  INSTALLATION_INACTIVE: 'marketing.wiring.INSTALLATION_INACTIVE',
  INSTALLATION_MISSING: 'marketing.wiring.INSTALLATION_MISSING',
  SMS_ACCOUNT_MISCONFIGURED: 'marketing.wiring.SMS_ACCOUNT_MISCONFIGURED',
  PROVIDER_ADAPTER_MISMATCH: 'marketing.wiring.PROVIDER_ADAPTER_MISMATCH',
  NO_ADAPTER: 'marketing.wiring.NO_ADAPTER',
  NO_DELIVERY_ADAPTER: 'marketing.wiring.NO_DELIVERY_ADAPTER',
  EMAIL_NOT_CONFIGURED: 'marketing.wiring.EMAIL_NOT_CONFIGURED',
  EMAIL_PURPOSE_NOT_PERMITTED: 'marketing.wiring.EMAIL_PURPOSE_NOT_PERMITTED',
  PUSH_NOT_CONFIGURED: 'marketing.wiring.PUSH_NOT_CONFIGURED',
  PUSH_PURPOSE_NOT_PERMITTED: 'marketing.wiring.PUSH_PURPOSE_NOT_PERMITTED',
  ACCOUNT_MISCONFIGURED: 'marketing.wiring.ACCOUNT_MISCONFIGURED',
};

export interface WiringSentence {
  readonly key: MessageKey;
  readonly values?: Readonly<Record<string, string>>;
}

/**
 * The sentence for a channel that is not wired. A code this build does not know still gets a
 * sentence, with the code in it for support, rather than a blank or a guess.
 */
export function wiringSentence(_channel: string, reason: string | null): WiringSentence {
  const key = reason === null ? undefined : WIRING_KEYS[reason];
  if (key !== undefined) {
    return { key };
  }
  return { key: 'marketing.wiring.UNKNOWN', values: { reason: reason ?? '—' } };
}

/**
 * The wiring code a server refusal's sentence names, if it names one: a launch refused with
 * "No ... delivery path is wired for SMS for this brand (SMS_PURPOSE_NOT_PERMITTED)" carries its
 * reason in parentheses. A console that read the channel list a minute ago may be looking at a
 * state the brand has since changed, and this is how the refusal it then meets is still said in
 * words rather than as that English sentence.
 */
export function wiringCodeIn(detail: string | null | undefined): string | null {
  if (!detail) {
    return null;
  }
  return Object.keys(WIRING_KEYS).find((code) => detail.includes(code)) ?? null;
}

/** The read model entry for one channel, if the server listed it. */
export function viewOf(channels: readonly ChannelView[], channel: string): ChannelView | undefined {
  return channels.find((candidate) => candidate.channel === channel);
}

/**
 * Whether a channel may be chosen. A channel the server did not list is treated as unknown rather
 * than unwired: a read model that has not caught up must not make a working channel vanish.
 */
export function isSelectable(channels: readonly ChannelView[], channel: string): boolean {
  return viewOf(channels, channel)?.isWired ?? true;
}

/**
 * Why a courier SMS cannot leave for this brand, or could not for this broadcast, in words
 * (ADR 0182, D15). Every code is one the server produces: the account codes come from the same
 * readiness check a campaign reads, read for the `COURIER` purpose, and the rest from the
 * broadcast's own checks.
 *
 * The two a dispatcher meets first are `SMS_PURPOSE_NOT_PERMITTED` (the brand's gateway account has
 * not been cleared to carry courier traffic, which the platform owner records per account) and
 * `COURIER_CONTACTS_NOT_WIRED` (the owner has not recorded that a courier's own number may be used
 * for a dispatcher's message). Neither is something a dispatcher can fix, which is why the console
 * says so before they spend time on a draft.
 */
const COURIER_WIRING_KEYS: Readonly<Record<string, MessageKey>> = {
  SMS_PURPOSE_NOT_PERMITTED: 'marketing.courierBroadcasts.wiring.SMS_PURPOSE_NOT_PERMITTED',
  COURIER_CONTACTS_NOT_WIRED: 'marketing.courierBroadcasts.wiring.COURIER_CONTACTS_NOT_WIRED',
  COURIER_CONTACTS_UNAVAILABLE: 'marketing.courierBroadcasts.wiring.COURIER_CONTACTS_UNAVAILABLE',
  NO_PROVIDER_BINDING: 'marketing.courierBroadcasts.wiring.NO_PROVIDER_BINDING',
  INSTALLATION_INACTIVE: 'marketing.courierBroadcasts.wiring.INSTALLATION_INACTIVE',
  INSTALLATION_MISSING: 'marketing.courierBroadcasts.wiring.INSTALLATION_MISSING',
  SMS_ACCOUNT_MISCONFIGURED: 'marketing.courierBroadcasts.wiring.SMS_ACCOUNT_MISCONFIGURED',
  PROVIDER_ADAPTER_MISMATCH: 'marketing.courierBroadcasts.wiring.PROVIDER_ADAPTER_MISMATCH',
  NO_ADAPTER: 'marketing.courierBroadcasts.wiring.NO_ADAPTER',
  NO_DELIVERY_ADAPTER: 'marketing.courierBroadcasts.wiring.NO_DELIVERY_ADAPTER',
  COURIER_TEMPLATE_NOT_AVAILABLE:
    'marketing.courierBroadcasts.wiring.COURIER_TEMPLATE_NOT_AVAILABLE',
  TEMPLATE_AWAITING_PROVIDER: 'marketing.courierBroadcasts.wiring.TEMPLATE_AWAITING_PROVIDER',
  NO_ACTIVE_COURIER: 'marketing.courierBroadcasts.wiring.NO_ACTIVE_COURIER',
  NO_COURIER_HAS_A_NUMBER: 'marketing.courierBroadcasts.wiring.NO_COURIER_HAS_A_NUMBER',
};

/** The sentence for a courier SMS that cannot leave. An unknown code still gets one, with the code in it. */
export function courierWiringSentence(reason: string | null): WiringSentence {
  const key = reason === null ? undefined : COURIER_WIRING_KEYS[reason];
  if (key !== undefined) {
    return { key };
  }
  return { key: 'marketing.courierBroadcasts.wiring.UNKNOWN', values: { reason: reason ?? '—' } };
}

/**
 * The code a refused broadcast's recorded sentence names, if it names one: "No SMS delivery path can
 * reach couriers for this brand (COURIER_CONTACTS_NOT_WIRED)". The server records its reason in
 * English; the console says it in the dispatcher's language.
 */
export function courierCodeIn(detail: string | null | undefined): string | null {
  if (!detail) {
    return null;
  }
  return Object.keys(COURIER_WIRING_KEYS).find((code) => detail.includes(code)) ?? null;
}

/**
 * The words for a message's delivery state, per channel (ADR 0182). «Handed to the operator» is what a
 * gateway that accepted a message and reported nothing earns; Telegram has no operator in between,
 * its Bot API takes the message and that is all it says, so there the honest word is «sent»; and a
 * browser vendor's push service accepts a message and says no more, which is «accepted by the push
 * service».
 */
export function handedKey(channel: string | null): MessageKey {
  switch (channel) {
    case 'MESSAGING_APP':
      return 'marketing.delivery.HANDED_TO_OPERATOR.MESSAGING_APP';
    case 'PUSH':
      return 'marketing.delivery.HANDED_TO_OPERATOR.PUSH';
    default:
      return 'marketing.delivery.HANDED_TO_OPERATOR';
  }
}

/**
 * The word for a read, per channel (ADR 0182, D14). Email would call it «opened»; the only read signal
 * Web Push offers is the customer tapping the notification, and saying «read» of a tap would claim more
 * than the service worker saw.
 */
export function readKey(channel: string | null): MessageKey {
  return channel === 'PUSH' ? 'marketing.delivery.READ.PUSH' : 'marketing.delivery.READ';
}
