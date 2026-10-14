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
import { TPipe } from '../../../core/i18n/t.pipe';
import { DeniedState } from '../../../shared/ui/denied-state';
import { describeApiError } from '../../orders/order-errors';
import { OrderedBranch, StorefrontMerchandisingApi } from './storefront-merchandising-api';

/**
 * The order the storefront lists the brand's branches in (gap-map row `6.8`; ADR 0157 Release 4,
 * built by ADR 0183 D17).
 *
 * A customer who shares a location is shown the nearest branch first and this order only breaks a
 * tie in distance; a customer who shares none is shown the branches in exactly this order. A branch
 * left out is *unplaced*: it sorts last, by name. With nothing placed the storefront lists the
 * branches by name, as it did before this screen existed.
 *
 * The screen edits a draft and saves the whole order at once, because the platform replaces the
 * order in one step: a half-applied order cannot exist.
 */
@Component({
  selector: 'q-storefront-branch-order',
  imports: [DeniedState, TPipe],
  templateUrl: './storefront-branch-order.html',
  styleUrl: './storefront-branch-order.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StorefrontBranchOrder implements OnInit {
  private readonly api = inject(StorefrontMerchandisingApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly saved = signal(false);
  protected readonly busy = signal(false);

  private readonly branches = signal<readonly OrderedBranch[]>([]);
  /** The placed branches as the platform holds them, first to last. */
  private readonly stored = computed(() =>
    this.branches()
      .filter((branch) => branch.storefrontSortOrder !== null)
      .sort((a, b) => (a.storefrontSortOrder ?? 0) - (b.storefrontSortOrder ?? 0))
      .map((branch) => branch.locationId),
  );
  /** What the manager has arranged so far; equal to {@link stored} until they touch something. */
  private readonly draft = signal<readonly string[] | null>(null);

  protected readonly placedIds = computed(() => this.draft() ?? this.stored());
  protected readonly placed = computed(() => {
    const byId = new Map(this.branches().map((branch) => [branch.locationId, branch] as const));
    return this.placedIds()
      .map((id) => byId.get(id))
      .filter((branch): branch is OrderedBranch => branch !== undefined);
  });
  protected readonly unplaced = computed(() => {
    const placed = new Set(this.placedIds());
    return this.branches().filter((branch) => !placed.has(branch.locationId));
  });
  protected readonly dirty = computed(() => {
    const draft = this.draft();
    const stored = this.stored();
    return (
      draft !== null && (draft.length !== stored.length || draft.some((id, i) => id !== stored[i]))
    );
  });

  async ngOnInit(): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null) {
      this.loading.set(false);
      return;
    }
    try {
      this.branches.set(await this.api.branches(scope));
    } catch (failure) {
      this.fail(failure, true);
    } finally {
      this.loading.set(false);
    }
  }

  protected place(branch: OrderedBranch): void {
    this.draft.set([...this.placedIds(), branch.locationId]);
    this.saved.set(false);
  }

  protected unplace(branch: OrderedBranch): void {
    this.draft.set(this.placedIds().filter((id) => id !== branch.locationId));
    this.saved.set(false);
  }

  protected move(branch: OrderedBranch, by: -1 | 1): void {
    const ids = [...this.placedIds()];
    const from = ids.indexOf(branch.locationId);
    const to = from + by;
    if (from < 0 || to < 0 || to >= ids.length) {
      return;
    }
    ids.splice(to, 0, ...ids.splice(from, 1));
    this.draft.set(ids);
    this.saved.set(false);
  }

  protected reset(): void {
    this.draft.set(null);
    this.error.set(null);
  }

  protected async save(): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null || this.busy() || !this.dirty()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.saved.set(false);
    try {
      this.branches.set(await this.api.setBranchOrder(scope, this.placedIds()));
      this.draft.set(null);
      this.saved.set(true);
    } catch (failure) {
      this.fail(failure, false);
    } finally {
      this.busy.set(false);
    }
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
