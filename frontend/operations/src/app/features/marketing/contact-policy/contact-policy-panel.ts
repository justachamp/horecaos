import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Drawer } from '../../../shared/ui/drawer';
import { Modal } from '../../../shared/ui/modal';
import { describeApiError } from '../../orders/order-errors';
import { REFUSAL_EFFECT_KEYS, REFUSAL_EXPLANATIONS } from '../refusal-explainer';
import {
  ContactPeriod,
  ContactPolicyApi,
  ContactPolicyDefaultsView,
  ContactPolicyOverrideRequest,
  ContactPolicyOverrideView,
  ContactPolicyView,
  PlatformBoundsView,
} from './contact-policy-api';

/** The channels a contact policy governs: the four that send a message. */
export const POLICY_CHANNELS: readonly string[] = ['MESSAGING_APP', 'SMS', 'EMAIL', 'PUSH'];

export const POLICY_PERIODS: readonly ContactPeriod[] = [
  'DAILY',
  'WEEKLY',
  'ROLLING_7D',
  'ROLLING_30D',
];

/** What an override form is missing or gets wrong, said with the platform's number in it. */
export interface PolicyProblem {
  readonly key: MessageKey;
  readonly values?: Readonly<Record<string, string | number>>;
}

/** `HH:mm`, from the `HH:mm:ss` or `HH:mm` a LocalTime comes over the wire as. */
export function clock(time: string): string {
  return time.slice(0, 5);
}

/** The most a cap may be, per period, read off the platform's own numbers. */
export function ceilingFor(period: ContactPeriod, bounds: PlatformBoundsView): number {
  switch (period) {
    case 'DAILY':
      return bounds.dailyCapCeiling;
    case 'WEEKLY':
      return bounds.weeklyCapCeiling;
    case 'ROLLING_7D':
      return bounds.rolling7DayCapCeiling;
    case 'ROLLING_30D':
      return bounds.rolling30DayCapCeiling;
  }
}

/**
 * What `ContactPolicyService.validateTightenOnly` would refuse, read earlier so the author sees
 * the platform's number beside the field instead of in a 400. The server and the table's CHECKs
 * stay the authority; this is the same rule, never a replacement.
 */
export function overrideProblem(
  form: {
    readonly capCount: number | null;
    readonly quietStart: string;
    readonly quietEnd: string;
    readonly period: ContactPeriod;
    readonly reason: string;
    readonly purpose: string;
  },
  bounds: PlatformBoundsView,
): PolicyProblem | null {
  if (form.purpose.trim() === '') {
    return { key: 'marketing.contactPolicy.problem.purpose' };
  }
  const hasQuiet = form.quietStart !== '' || form.quietEnd !== '';
  if (form.capCount === null && !hasQuiet) {
    return { key: 'marketing.contactPolicy.problem.empty' };
  }
  if ((form.quietStart === '') !== (form.quietEnd === '')) {
    return { key: 'marketing.contactPolicy.problem.quietPair' };
  }
  if (form.capCount !== null) {
    const ceiling = ceilingFor(form.period, bounds);
    if (!Number.isInteger(form.capCount) || form.capCount < 0) {
      return { key: 'marketing.contactPolicy.problem.capNegative' };
    }
    if (form.capCount > ceiling) {
      return {
        key: 'marketing.contactPolicy.problem.capLoosened',
        values: { cap: form.capCount, ceiling },
      };
    }
  }
  if (hasQuiet) {
    const latestStart = clock(bounds.quietHoursStartNoLaterThan);
    const earliestEnd = clock(bounds.quietHoursEndNoEarlierThan);
    if (form.quietStart > latestStart) {
      return {
        key: 'marketing.contactPolicy.problem.quietStartLoosened',
        values: { start: form.quietStart, bound: latestStart },
      };
    }
    if (form.quietEnd < earliestEnd) {
      return {
        key: 'marketing.contactPolicy.problem.quietEndLoosened',
        values: { end: form.quietEnd, bound: earliestEnd },
      };
    }
  }
  if (form.reason.trim() === '') {
    return { key: 'marketing.contactPolicy.problem.reason' };
  }
  return null;
}

/**
 * A brand's contact policy (ADR 0112 Decision 4): the platform's bounds, the tighter rules a
 * tenant has added, and, beside them, why a guest is blocked.
 *
 * **An override may make a brand quieter and never louder.** The numbers protect a sending
 * reputation shared across tenants, so "it is their customer relationship" is not an argument for
 * letting one tenant spend it. The form states the platform's number next to each field and
 * refuses a loosening in words before the server does; the server refuses it with the same number
 * and the table's CHECK is the third wall. Every override carries the reason it was set, and so
 * does removing one: a rule that silences a campaign is a decision somebody should be able to
 * attribute.
 *
 * **The explainer is the other half of the answer.** A marketer asking "why did this guest not
 * get step 2" is told by the decision log, and this screen lists every reason that log can hold,
 * what it means, whether it ends the guest's run or only holds the step, and what a person can do
 * about it. Quiet hours are not on that list because they never refuse: a message that falls due
 * inside the closed window is held to the next open boundary and sent then.
 *
 * Reading needs `campaign.author`; writing needs `marketing.contact_policy.manage`, enforced by
 * the server, so a refusal is shown and not hidden behind a button that is merely absent.
 */
@Component({
  selector: 'q-contact-policy-panel',
  imports: [TPipe, Drawer, Modal],
  templateUrl: './contact-policy-panel.html',
  styleUrl: './contact-policy-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContactPolicyPanel implements OnInit {
  private readonly api = inject(ContactPolicyApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly policy = signal<ContactPolicyView | null>(null);
  protected readonly defaults = signal<ContactPolicyDefaultsView | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly acting = signal(false);

  protected readonly channels = POLICY_CHANNELS;
  protected readonly periods = POLICY_PERIODS;
  protected readonly explanations = REFUSAL_EXPLANATIONS;
  protected readonly effectKeys = REFUSAL_EFFECT_KEYS;

  // ---------------------------------------------------------------- the form

  protected readonly formOpen = signal(false);
  /** The override being replaced, or null when a new one is being set. */
  private editing: ContactPolicyOverrideView | null = null;
  protected readonly formSubmitting = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly formChannel = signal('SMS');
  protected readonly formPurpose = signal('MARKETING_PROMOTIONS');
  protected readonly formPeriod = signal<ContactPeriod>('WEEKLY');
  protected readonly formCap = signal<number | null>(null);
  protected readonly formQuietStart = signal('');
  protected readonly formQuietEnd = signal('');
  protected readonly formReason = signal('');

  protected readonly formIsReplacement = computed(() => this.formOpen() && this.editing !== null);

  protected readonly formProblem = computed<PolicyProblem | null>(() => {
    const bounds = this.policy()?.platform;
    if (!bounds) {
      return null;
    }
    return overrideProblem(
      {
        capCount: this.formCap(),
        quietStart: this.formQuietStart(),
        quietEnd: this.formQuietEnd(),
        period: this.formPeriod(),
        reason: this.formReason(),
        purpose: this.formPurpose(),
      },
      bounds,
    );
  });

  /** The cap's ceiling for the period chosen, so the field can say its own limit. */
  protected readonly formCeiling = computed(() => {
    const bounds = this.policy()?.platform;
    return bounds ? ceilingFor(this.formPeriod(), bounds) : null;
  });

  // -------------------------------------------------------------- removal

  protected readonly removing = signal<ContactPolicyOverrideView | null>(null);
  protected readonly removeReason = signal('');

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    if (!scope) {
      this.denied.set(this.brand.denied());
      this.loading.set(false);
      return;
    }
    try {
      this.policy.set(await this.api.read(scope));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
    if (this.denied()) {
      return;
    }
    // Read-only context beside the rules; a failure leaves the rules usable.
    try {
      this.defaults.set(await this.api.defaults(scope));
    } catch {
      this.defaults.set(null);
    }
  }

  // ---------------------------------------------------------------- rendering

  protected channelKey(channel: string): MessageKey {
    return CHANNEL_KEYS[channel] ?? 'marketing.channel.SMS';
  }

  protected periodKey(period: string): MessageKey {
    return PERIOD_KEYS[period as ContactPeriod] ?? 'marketing.contactPolicy.period.DAILY';
  }

  protected quietWindow(override: ContactPolicyOverrideView): string | null {
    return override.quietHoursStart !== null && override.quietHoursEnd !== null
      ? `${clock(override.quietHoursStart)}–${clock(override.quietHoursEnd)}`
      : null;
  }

  protected clock(time: string): string {
    return clock(time);
  }

  protected problemText(problem: PolicyProblem): string {
    return this.i18n.t(problem.key, problem.values);
  }

  // ------------------------------------------------------------------- the form

  protected openNew(): void {
    this.editing = null;
    this.formChannel.set('SMS');
    this.formPurpose.set('MARKETING_PROMOTIONS');
    this.formPeriod.set('WEEKLY');
    this.formCap.set(null);
    this.formQuietStart.set('');
    this.formQuietEnd.set('');
    this.formReason.set('');
    this.formError.set(null);
    this.formOpen.set(true);
  }

  protected openReplace(override: ContactPolicyOverrideView): void {
    this.editing = override;
    this.formChannel.set(override.channel);
    this.formPurpose.set(override.campaignPurpose);
    this.formPeriod.set(override.period);
    this.formCap.set(override.capCount);
    this.formQuietStart.set(
      override.quietHoursStart === null ? '' : clock(override.quietHoursStart),
    );
    this.formQuietEnd.set(override.quietHoursEnd === null ? '' : clock(override.quietHoursEnd));
    this.formReason.set('');
    this.formError.set(null);
    this.formOpen.set(true);
  }

  protected closeForm(): void {
    this.formOpen.set(false);
  }

  protected onCapInput(value: string): void {
    this.formCap.set(value === '' ? null : Number(value));
  }

  protected async submitForm(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || this.formProblem() !== null || this.formSubmitting()) {
      return;
    }
    const request: ContactPolicyOverrideRequest = {
      channel: this.formChannel(),
      campaignPurpose: this.formPurpose().trim(),
      period: this.formPeriod(),
      capCount: this.formCap(),
      quietHoursStart: this.formQuietStart() === '' ? null : this.formQuietStart(),
      quietHoursEnd: this.formQuietEnd() === '' ? null : this.formQuietEnd(),
      statedReason: this.formReason().trim(),
    };
    this.formSubmitting.set(true);
    this.formError.set(null);
    try {
      await this.api.set(scope, request, this.editing?.version);
      this.formOpen.set(false);
      this.policy.set(await this.api.read(scope));
    } catch (error) {
      this.formError.set(this.describe(error));
    } finally {
      this.formSubmitting.set(false);
    }
  }

  // ------------------------------------------------------------------- removal

  protected askRemove(override: ContactPolicyOverrideView): void {
    this.actionError.set(null);
    this.removeReason.set('');
    this.removing.set(override);
  }

  protected async confirmRemove(): Promise<void> {
    const scope = this.brand.scope();
    const override = this.removing();
    const reason = this.removeReason().trim();
    if (!scope || !override || reason === '') {
      return;
    }
    this.acting.set(true);
    this.actionError.set(null);
    try {
      await this.api.remove(scope, override, reason);
      this.removing.set(null);
      this.policy.set(await this.api.read(scope));
    } catch (error) {
      this.removing.set(null);
      this.actionError.set(this.describe(error));
    } finally {
      this.acting.set(false);
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

const CHANNEL_KEYS: Readonly<Record<string, MessageKey>> = {
  SMS: 'marketing.channel.SMS',
  EMAIL: 'marketing.channel.EMAIL',
  PUSH: 'marketing.channel.PUSH',
  MESSAGING_APP: 'marketing.channel.MESSAGING_APP',
};

const PERIOD_KEYS: Readonly<Record<ContactPeriod, MessageKey>> = {
  DAILY: 'marketing.contactPolicy.period.DAILY',
  WEEKLY: 'marketing.contactPolicy.period.WEEKLY',
  ROLLING_7D: 'marketing.contactPolicy.period.ROLLING_7D',
  ROLLING_30D: 'marketing.contactPolicy.period.ROLLING_30D',
};
