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
 * **Email and push are honest about not being connected.** The platform's mail module (ADR 0097)
 * sends staff invitations and password resets over one SMTP submission account; the record says
 * the notification pipeline is where a tenant's own guests would be written to, and it has no
 * email delivery. Push has no provider at all. Neither is offered as if it worked.
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
};

/** The two channels whose "no delivery path" has a different, more useful sentence of its own. */
const NO_DELIVERY_KEYS: Readonly<Record<string, MessageKey>> = {
  EMAIL: 'marketing.wiring.NO_DELIVERY_ADAPTER.EMAIL',
  PUSH: 'marketing.wiring.NO_DELIVERY_ADAPTER.PUSH',
};

export interface WiringSentence {
  readonly key: MessageKey;
  readonly values?: Readonly<Record<string, string>>;
}

/**
 * The sentence for a channel that is not wired. A code this build does not know still gets a
 * sentence, with the code in it for support, rather than a blank or a guess.
 */
export function wiringSentence(channel: string, reason: string | null): WiringSentence {
  if (reason === 'NO_DELIVERY_ADAPTER' && NO_DELIVERY_KEYS[channel] !== undefined) {
    return { key: NO_DELIVERY_KEYS[channel] };
  }
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
