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
import { ConfigurationApi } from '../../settings/configuration-api';
import { FeedbackSettingsApi, PolicyTarget, RecoveryPolicy } from './feedback-settings-api';
import { FlagState, readFlag, writeFlag } from './flag-support';
import { describePolicyError, isForbidden, isStale, toInt } from './policy-support';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

const FLAG = 'feature.service_recovery';
const RATINGS: readonly number[] = [0, 1, 2, 3, 4, 5];

const MIN_FIRST_RESPONSE = 15;
const MAX_FIRST_RESPONSE = 10_080;
const MIN_RESOLUTION = 60;
const MAX_RESOLUTION = 43_200;

/**
 * Which reviews open a service-recovery case and how long each of the two clocks runs (ADR 0159,
 * Decision 8), with the tenant's switch for the feature beside them.
 *
 * <p>A case stores the version of the policy it opened under, so editing here never makes an old case
 * late; the panel says so, because it is the first thing a manager asks before tightening a clock.
 * Resolution may not be shorter than the first answer, and the bounds are the server's.
 */
@Component({
  selector: 'q-recovery-policy-panel',
  imports: [TPipe],
  templateUrl: './recovery-policy-panel.html',
  styleUrl: './policy-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RecoveryPolicyPanel {
  private readonly api = inject(FeedbackSettingsApi);
  private readonly configuration = inject(ConfigurationApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<BrandScope>();
  readonly target = input<'TENANT' | 'BRAND'>('TENANT');
  readonly canWrite = input(false);

  protected readonly state = signal<LoadState>('loading');
  protected readonly policy = signal<RecoveryPolicy | null>(null);
  protected readonly flag = signal<FlagState | null>(null);
  protected readonly errorText = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly autoOpen = signal(2);
  protected readonly firstResponse = signal(240);
  protected readonly resolution = signal(4320);
  protected readonly alertOnOpen = signal(false);
  protected readonly alertOnBreach = signal(false);
  protected readonly reason = signal('');

  protected readonly ratings = RATINGS;

  protected readonly policyTarget = computed<PolicyTarget>(() =>
    this.target() === 'TENANT'
      ? { kind: 'TENANT', tenantId: this.scope().tenantId }
      : { kind: 'BRAND', scope: this.scope() },
  );

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

  protected readonly valid = computed(
    () =>
      this.firstResponse() >= MIN_FIRST_RESPONSE &&
      this.firstResponse() <= MAX_FIRST_RESPONSE &&
      this.resolution() >= MIN_RESOLUTION &&
      this.resolution() <= MAX_RESOLUTION &&
      this.resolution() >= this.firstResponse(),
  );

  protected readonly canSave = computed(
    () => this.canWrite() && !this.busy() && this.valid() && this.reason().trim() !== '',
  );

  constructor() {
    effect(() => {
      this.policyTarget();
      untracked(() => void this.load());
    });
  }

  protected onAutoOpen(value: string): void {
    this.autoOpen.set(toInt(value, this.autoOpen()));
  }

  protected onFirstResponse(value: string): void {
    this.firstResponse.set(toInt(value, this.firstResponse()));
  }

  protected onResolution(value: string): void {
    this.resolution.set(toInt(value, this.resolution()));
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
      const saved = await this.api.saveRecoveryPolicy(
        this.policyTarget(),
        {
          autoOpenMaxRating: this.autoOpen(),
          firstResponseMinutes: this.firstResponse(),
          resolutionMinutes: this.resolution(),
          alertOnOpen: this.alertOnOpen(),
          alertOnBreach: this.alertOnBreach(),
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

  protected async toggleFlag(): Promise<void> {
    const current = this.flag();
    if (current === null || this.busy() || !this.canWrite()) {
      return;
    }
    this.busy.set(true);
    this.errorText.set(null);
    try {
      await writeFlag(
        this.configuration,
        this.scope().tenantId,
        FLAG,
        !current.on,
        current.version,
        this.i18n.t('customers.feedback.reason.default'),
      );
      this.flag.set(await readFlag(this.configuration, this.scope().tenantId, FLAG));
    } catch (error) {
      this.errorText.set(describePolicyError(this.i18n, error));
      if (isStale(error)) {
        this.flag.set(await readFlag(this.configuration, this.scope().tenantId, FLAG));
      }
    } finally {
      this.busy.set(false);
    }
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    this.errorText.set(null);
    this.notice.set(null);
    try {
      const [policy, flag] = await Promise.all([
        this.api.recoveryPolicy(this.policyTarget()),
        readFlag(this.configuration, this.scope().tenantId, FLAG),
      ]);
      this.adopt(policy);
      this.flag.set(flag);
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

  private adopt(policy: RecoveryPolicy): void {
    this.policy.set(policy);
    this.autoOpen.set(policy.autoOpenMaxRating);
    this.firstResponse.set(policy.firstResponseMinutes);
    this.resolution.set(policy.resolutionMinutes);
    this.alertOnOpen.set(policy.alertOnOpen);
    this.alertOnBreach.set(policy.alertOnBreach);
  }
}
