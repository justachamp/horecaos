import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { BrandScope } from '../../../core/api/catalog-paths';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import {
  FeedbackSettingsApi,
  FulfilmentMode,
  PROMPT_CHANNELS,
  PROMPT_MODES,
  PolicyTarget,
  PromptChannel,
  PromptPolicy,
  PromptRow,
} from './feedback-settings-api';
import { describePolicyError, isForbidden, isStale, toInt } from './policy-support';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

/** The bounds `FeedbackPromptDocument` enforces; the panel mirrors them so Save is honest, and the server still decides. */
const MAX_DELAY_MINUTES = 1440;
const MIN_GIVE_UP_HOURS = 1;
const MAX_GIVE_UP_HOURS = 72;
const MIN_INLINE_DAYS = 1;
const MAX_INLINE_DAYS = 90;
const MAX_ROWS = 40;
const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

/**
 * When a customer is asked to rate a finished order (ADR 0159, Decision 4, row 5.5): a send window
 * in the branch's own time zone, how long a finished order stays in the storefront's own "rate your
 * order" list, and a matrix of order type by channel.
 *
 * <p>A document can only make a prompt later or rarer than today's immediate send, so nothing here
 * offers a negative delay or a second prompt. The most specific row wins and the order type counts
 * before the channel; anything not listed follows the built-in default, which is why the matrix may
 * be empty.
 */
@Component({
  selector: 'q-prompt-policy-panel',
  imports: [TPipe],
  templateUrl: './prompt-policy-panel.html',
  styleUrl: './policy-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromptPolicyPanel {
  private readonly api = inject(FeedbackSettingsApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<BrandScope>();
  readonly target = input<'TENANT' | 'BRAND'>('TENANT');
  readonly canWrite = input(false);

  protected readonly state = signal<LoadState>('loading');
  protected readonly policy = signal<PromptPolicy | null>(null);
  protected readonly errorText = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly windowMode = signal<'any' | 'custom'>('custom');
  protected readonly windowFrom = signal('09:00');
  protected readonly windowTo = signal('21:00');
  protected readonly inlineDays = signal(7);
  protected readonly rows = signal<readonly PromptRow[]>([]);
  protected readonly reason = signal('');

  protected readonly modes = PROMPT_MODES;
  protected readonly channels = PROMPT_CHANNELS;

  protected readonly policyTarget = computed<PolicyTarget>(() =>
    this.target() === 'TENANT'
      ? { kind: 'TENANT', tenantId: this.scope().tenantId }
      : { kind: 'BRAND', scope: this.scope() },
  );

  /** What the panel says about where the shown document comes from. */
  protected readonly provenance = computed<MessageKey | null>(() => {
    const current = this.policy();
    if (current === null) {
      return null;
    }
    if (current.isDefault) {
      return 'customers.feedback.scope.builtIn';
    }
    return this.target() === 'BRAND' && current.versionAtScope === 0
      ? 'customers.feedback.scope.inheritedFromTenant'
      : null;
  });

  protected readonly valid = computed(() => {
    const rows = this.rows();
    const inline = this.inlineDays();
    if (inline < MIN_INLINE_DAYS || inline > MAX_INLINE_DAYS || rows.length > MAX_ROWS) {
      return false;
    }
    if (this.windowMode() === 'custom') {
      const from = this.windowFrom();
      const to = this.windowTo();
      if (!TIME.test(from) || !TIME.test(to) || from >= to) {
        return false;
      }
    }
    return rows.every(
      (row) =>
        row.delayMinutes >= 0 &&
        row.delayMinutes <= MAX_DELAY_MINUTES &&
        row.giveUpAfterHours >= MIN_GIVE_UP_HOURS &&
        row.giveUpAfterHours <= MAX_GIVE_UP_HOURS,
    );
  });

  protected readonly canSave = computed(
    () => this.canWrite() && !this.busy() && this.valid() && this.reason().trim() !== '',
  );

  constructor() {
    effect(() => {
      this.policyTarget();
      untracked(() => void this.load());
    });
  }

  /** The wildcard is stored as `*` and worded as "any"; a key may not carry the character. */
  protected modeKey(mode: FulfilmentMode): MessageKey {
    return `customers.feedback.prompt.mode.${mode === '*' ? 'ANY' : mode}`;
  }

  protected channelKey(channel: PromptChannel): MessageKey {
    return `customers.feedback.prompt.channel.${channel === '*' ? 'ANY' : channel}`;
  }

  // ----------------------------------------------------------------------------- editing

  protected onWindowMode(value: string): void {
    this.windowMode.set(value === 'any' ? 'any' : 'custom');
  }

  protected onInlineDays(value: string): void {
    this.inlineDays.set(toInt(value, this.inlineDays()));
  }

  protected addRow(): void {
    this.rows.update((rows) => [
      ...rows,
      {
        fulfillmentMode: '*',
        channel: 'SMS',
        enabled: true,
        delayMinutes: 30,
        giveUpAfterHours: 6,
      },
    ]);
  }

  protected removeRow(index: number): void {
    this.rows.update((rows) => rows.filter((_, i) => i !== index));
  }

  protected patchRow(index: number, change: Partial<PromptRow>): void {
    this.rows.update((rows) => rows.map((row, i) => (i === index ? { ...row, ...change } : row)));
  }

  protected onRowDelay(index: number, value: string): void {
    this.patchRow(index, { delayMinutes: toInt(value, 0) });
  }

  protected onRowGiveUp(index: number, value: string): void {
    this.patchRow(index, { giveUpAfterHours: toInt(value, MIN_GIVE_UP_HOURS) });
  }

  protected async save(): Promise<void> {
    const current = this.policy();
    if (current === null || !this.canSave()) {
      return;
    }
    this.busy.set(true);
    this.errorText.set(null);
    this.notice.set(null);
    try {
      const saved = await this.api.savePromptPolicy(
        this.policyTarget(),
        {
          sendWindow:
            this.windowMode() === 'any' ? null : { start: this.windowFrom(), end: this.windowTo() },
          inlineWindowDays: this.inlineDays(),
          rows: this.rows(),
          reason: this.reason().trim(),
        },
        current.versionAtScope,
      );
      this.adopt(saved);
      this.reason.set('');
      this.notice.set(this.i18n.t('customers.feedback.saved'));
    } catch (error) {
      // The reload clears the messages, so the lost race is said after it, not before.
      const message = describePolicyError(this.i18n, error);
      if (isStale(error)) {
        await this.load();
      }
      this.errorText.set(message);
    } finally {
      this.busy.set(false);
    }
  }

  // ---------------------------------------------------------------------------- plumbing

  private async load(): Promise<void> {
    this.state.set('loading');
    this.errorText.set(null);
    this.notice.set(null);
    try {
      this.adopt(await this.api.promptPolicy(this.policyTarget()));
      this.state.set('ready');
    } catch (error) {
      if (isForbidden(error)) {
        this.state.set('denied');
      } else {
        this.errorText.set(describePolicyError(this.i18n, error));
        this.state.set('error');
      }
    }
  }

  private adopt(policy: PromptPolicy): void {
    this.policy.set(policy);
    this.windowMode.set(policy.sendWindow === null ? 'any' : 'custom');
    this.windowFrom.set(policy.sendWindow?.start ?? '09:00');
    this.windowTo.set(policy.sendWindow?.end ?? '21:00');
    this.inlineDays.set(policy.inlineWindowDays);
    this.rows.set(policy.rows);
  }
}
