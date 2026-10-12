import { ChangeDetectionStrategy, Component, OnInit, inject, output, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { DeniedState } from '../../../shared/ui/denied-state';
import { describeApiError } from '../../orders/order-errors';
import { ContentApi, ContentPolicies } from './content-api';

type EditorialFlags = ContentPolicies['editorial'];
type EditorialFeature = keyof EditorialFlags;

const FEATURES: readonly EditorialFeature[] = ['news', 'gallery', 'recipes', 'vacancies'];

const FEATURE_KEYS: Readonly<Record<EditorialFeature, MessageKey>> = {
  news: 'marketing.content.policy.kind.news',
  gallery: 'marketing.content.policy.kind.gallery',
  recipes: 'marketing.content.policy.kind.recipes',
  vacancies: 'marketing.content.policy.kind.vacancies',
};

/**
 * The brand's two content policies and what the plan allows (ADR 0157, Entitlements, policy and
 * configuration keys).
 *
 * `content.publishing` may only tighten: a brand can require every one of its languages to have text
 * before anything is published. `content.editorial` says which of the four editorial kinds the brand
 * runs, all off by default -- and it counts only when the plan includes the editorial tier, which is
 * the platform owner's switch and is shown here as a fact, not a control. Video is the same: a plan
 * fact, off until the platform owner turns it on. Each save presents the policy version last read
 * (a colleague's save is a reload, not an overwrite).
 */
@Component({
  selector: 'q-content-policies',
  imports: [DeniedState, TPipe],
  templateUrl: './content-policy-panel.html',
  styleUrl: './content-policy-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentPolicyPanel implements OnInit {
  private readonly api = inject(ContentApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);

  /** The policies as the platform last answered, so the page can show or hide the editorial kinds and video. */
  readonly policiesChanged = output<ContentPolicies>();

  protected readonly policies = signal<ContentPolicies | null>(null);
  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly saved = signal(false);
  protected readonly busy = signal(false);
  protected readonly features = FEATURES;
  /**
   * Bumped when a save is refused, so the switches are drawn afresh: a checkbox the author clicked
   * keeps its own state in the browser, and a value that did not change in the model would not
   * change it back.
   */
  protected readonly round = signal(0);

  async ngOnInit(): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null) {
      this.loading.set(false);
      return;
    }
    try {
      this.accept(await this.api.policies(scope));
    } catch (failure) {
      this.fail(failure, true);
    } finally {
      this.loading.set(false);
    }
  }

  protected featureKey(feature: EditorialFeature): MessageKey {
    return FEATURE_KEYS[feature];
  }

  protected async setRequireAll(required: boolean): Promise<void> {
    const current = this.policies();
    await this.save((scope) =>
      this.api.setPublishingPolicy(scope, current?.publishingVersion ?? 0, required),
    );
  }

  protected async setFeature(feature: EditorialFeature, on: boolean): Promise<void> {
    const current = this.policies();
    if (current === null) {
      return;
    }
    await this.save((scope) =>
      this.api.setEditorialPolicy(scope, current.editorialVersion, {
        ...current.editorial,
        [feature]: on,
      }),
    );
  }

  private async save(
    write: (scope: NonNullable<ReturnType<CurrentBrand['scope']>>) => Promise<ContentPolicies>,
  ): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.saved.set(false);
    try {
      this.accept(await write(scope));
      this.saved.set(true);
    } catch (failure) {
      this.fail(failure, false);
      // A refused save must not leave the switch showing what was not saved.
      try {
        this.accept(await this.api.policies(scope));
      } catch {
        // The message above already says something went wrong.
      }
      this.round.update((n) => n + 1);
    } finally {
      this.busy.set(false);
    }
  }

  private accept(policies: ContentPolicies): void {
    this.policies.set(policies);
    this.policiesChanged.emit(policies);
  }

  private fail(failure: unknown, reading: boolean): void {
    if (failure instanceof ApiError) {
      if (failure.code === 'INSUFFICIENT_CAPABILITY' && reading) {
        this.denied.set(true);
        return;
      }
      this.error.set(describeApiError(failure, (key, values) => this.i18n.t(key, values)));
      return;
    }
    this.error.set(this.i18n.t('marketing.content.editor.failed'));
  }
}
