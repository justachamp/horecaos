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
import { RouterLink } from '@angular/router';

import { BrandScope } from '../../../core/api/catalog-paths';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ConfigurationApi, ConfigurationResolutionView } from '../../settings/configuration-api';
import { FlagState, booleanOf, numberOf, readFlag, stringOf, writeFlag } from './flag-support';
import { describePolicyError, isForbidden, isStale, toInt } from './policy-support';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';
export type Visibility = 'NONE' | 'RATING_ONLY' | 'RATING_AND_COMMENTS';

const FLAG = 'feature.feedback_settings';
const KEY_VISIBILITY = 'feedback.public_visibility';
const KEY_MINIMUM = 'feedback.public_minimum_reviews';
const KEY_APPROVAL = 'feedback.comments_require_approval';
const VISIBILITIES: readonly Visibility[] = ['NONE', 'RATING_ONLY', 'RATING_AND_COMMENTS'];
const MAXIMUM_MINIMUM = 1000;

/** What a key resolved to at the edited level, and the version a write there is checked against. */
interface Loaded {
  readonly visibility: Visibility;
  readonly minimum: number;
  readonly requireApproval: boolean;
  readonly versions: Readonly<Record<string, number | null>>;
}

/**
 * What the public may see of a brand's reviews (ADR 0159, Decisions 5 and 6): nothing, the rating
 * only, or the rating and approved comments; how many reviews a brand needs before an average is
 * shown; and whether a moderator approves each comment first. Ordinary ADR 0030 values, so the
 * panel writes them through the shared configuration API, one reasoned write per changed key.
 *
 * <p>Nothing is public until a tenant chooses. The average always counts every review, whatever
 * the setting says about comments: the page says so, because "hide the bad ones" is the first
 * thing a tenant will try to do here and it is not what this does.
 */
@Component({
  selector: 'q-visibility-panel',
  imports: [TPipe, RouterLink],
  templateUrl: './visibility-panel.html',
  styleUrl: './policy-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VisibilityPanel {
  private readonly configuration = inject(ConfigurationApi);
  private readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<BrandScope>();
  readonly target = input<'TENANT' | 'BRAND'>('TENANT');
  readonly canWrite = input(false);

  protected readonly state = signal<LoadState>('loading');
  protected readonly flag = signal<FlagState | null>(null);
  protected readonly errorText = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly visibility = signal<Visibility>('NONE');
  protected readonly minimum = signal(10);
  protected readonly requireApproval = signal(true);
  protected readonly reason = signal('');

  private readonly loaded = signal<Loaded | null>(null);

  protected readonly visibilities = VISIBILITIES;
  protected readonly canModerate = computed(() => this.capabilities.has('REVIEW_MODERATE'));

  protected readonly minimumValid = computed(
    () =>
      Number.isInteger(this.minimum()) && this.minimum() >= 0 && this.minimum() <= MAXIMUM_MINIMUM,
  );

  protected readonly changed = computed(() => {
    const base = this.loaded();
    return (
      base !== null &&
      (this.visibility() !== base.visibility ||
        this.minimum() !== base.minimum ||
        this.requireApproval() !== base.requireApproval)
    );
  });

  protected readonly canSave = computed(
    () =>
      this.canWrite() &&
      !this.busy() &&
      this.changed() &&
      this.minimumValid() &&
      this.reason().trim() !== '',
  );

  constructor() {
    effect(() => {
      this.target();
      this.scope();
      untracked(() => void this.load());
    });
  }

  protected visibilityKey(value: Visibility): MessageKey {
    return `customers.feedback.visibility.${value}`;
  }

  protected onVisibility(value: string): void {
    this.visibility.set(
      VISIBILITIES.includes(value as Visibility) ? (value as Visibility) : 'NONE',
    );
  }

  protected onMinimum(value: string): void {
    this.minimum.set(toInt(value, this.minimum()));
  }

  protected async save(): Promise<void> {
    const base = this.loaded();
    if (base === null || !this.canSave()) {
      return;
    }
    this.busy.set(true);
    this.errorText.set(null);
    this.notice.set(null);
    const reason = this.reason().trim();
    try {
      if (this.visibility() !== base.visibility) {
        await this.write(KEY_VISIBILITY, base, { stringValue: this.visibility() }, reason);
      }
      if (this.minimum() !== base.minimum) {
        await this.write(KEY_MINIMUM, base, { integerValue: this.minimum() }, reason);
      }
      if (this.requireApproval() !== base.requireApproval) {
        await this.write(KEY_APPROVAL, base, { booleanValue: this.requireApproval() }, reason);
      }
      this.reason.set('');
      await this.load();
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

  private write(
    code: string,
    base: Loaded,
    value: { stringValue?: string; integerValue?: number; booleanValue?: boolean },
    reason: string,
  ): Promise<unknown> {
    const brandLevel = this.target() === 'BRAND';
    return this.configuration.setValue(this.scope().tenantId, code, {
      scopeType: brandLevel ? 'BRAND' : 'TENANT',
      brandId: brandLevel ? this.scope().brandId : null,
      explicitNull: false,
      ...value,
      expectedVersion: base.versions[code] ?? null,
      reason,
    });
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    this.errorText.set(null);
    this.notice.set(null);
    const tenantId = this.scope().tenantId;
    const brandLevel = this.target() === 'BRAND';
    const read = (code: string): Promise<ConfigurationResolutionView> =>
      this.configuration.resolution(
        tenantId,
        code,
        brandLevel ? 'BRAND' : 'TENANT',
        brandLevel ? this.scope().brandId : null,
      );
    try {
      const [visibility, minimum, approval, flag] = await Promise.all([
        read(KEY_VISIBILITY),
        read(KEY_MINIMUM),
        read(KEY_APPROVAL),
        readFlag(this.configuration, tenantId, FLAG),
      ]);
      const loaded: Loaded = {
        visibility: (VISIBILITIES.find((v) => v === stringOf(visibility)) ?? 'NONE') as Visibility,
        minimum: numberOf(minimum) ?? 10,
        requireApproval: booleanOf(approval) ?? true,
        versions: {
          [KEY_VISIBILITY]: visibility.currentVersionAtScope,
          [KEY_MINIMUM]: minimum.currentVersionAtScope,
          [KEY_APPROVAL]: approval.currentVersionAtScope,
        },
      };
      this.loaded.set(loaded);
      this.visibility.set(loaded.visibility);
      this.minimum.set(loaded.minimum);
      this.requireApproval.set(loaded.requireApproval);
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
}
