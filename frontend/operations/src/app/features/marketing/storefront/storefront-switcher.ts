import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { DeniedState } from '../../../shared/ui/denied-state';
import { describeApiError } from '../../orders/order-errors';
import { ContentApi, ContentPolicies, StorefrontLayout } from '../content/content-api';
import {
  StorefrontMerchandisingApi,
  SwitcherCandidate,
  SwitcherEntry,
} from './storefront-merchandising-api';

const LAYOUTS: readonly StorefrontLayout[] = ['STANDARD', 'MARKETPLACE'];

const LAYOUT_KEYS: Readonly<Record<StorefrontLayout, MessageKey>> = {
  STANDARD: 'marketing.storefront.layout.standard',
  MARKETPLACE: 'marketing.storefront.layout.marketplace',
};

type EntryStatus = 'shown' | 'hidden' | 'noHostname' | 'inactive';

const STATUS_KEYS: Readonly<Record<EntryStatus, MessageKey>> = {
  shown: 'marketing.storefront.switcher.status.shown',
  hidden: 'marketing.storefront.switcher.status.hidden',
  noHostname: 'marketing.storefront.switcher.status.noHostname',
  inactive: 'marketing.storefront.switcher.status.inactive',
};

function candidateKey(candidate: { brandId: string; channelId: string }): string {
  return `${candidate.brandId}:${candidate.channelId}`;
}

/**
 * The brand switcher and the category layout (gap-map row `6.8`; ADR 0157 Release 4, built by ADR
 * 0183 D17).
 *
 * - **The switcher** lists the tenant's other brands a visitor can reach from this storefront. An
 *   entry names a brand and one of its *website channels*, never an address: where a visitor is sent
 *   is that website's verified hostname, read again on every request, so there is nothing here to
 *   type and nothing that can go stale. An entry that would not be shown today says why (hidden, no
 *   verified address, brand not active).
 * - **The layout** is a policy with a default: Standard is today's category navigation; Marketplace
 *   is the compact strip and the all-categories overlay. Nothing sets it until someone chooses, and a
 *   save presents the policy version last read (a colleague's save is a reload, not an overwrite).
 */
@Component({
  selector: 'q-storefront-switcher',
  imports: [DeniedState, TPipe],
  templateUrl: './storefront-switcher.html',
  styleUrl: './storefront-switcher.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StorefrontSwitcher implements OnInit {
  private readonly merchandising = inject(StorefrontMerchandisingApi);
  private readonly content = inject(ContentApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);

  protected readonly layouts = LAYOUTS;
  protected readonly entries = signal<readonly SwitcherEntry[]>([]);
  protected readonly candidates = signal<readonly SwitcherCandidate[]>([]);
  protected readonly policies = signal<ContentPolicies | null>(null);
  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly saved = signal<MessageKey | null>(null);
  protected readonly busy = signal(false);
  protected readonly pick = signal('');
  /** Bumped when a layout save is refused, so the radios are drawn afresh from the model. */
  protected readonly round = signal(0);

  /** The brands and websites not yet in the switcher: an entry can be added once. */
  protected readonly available = computed(() => {
    const taken = new Set(
      this.entries().map((entry) => `${entry.targetBrandId}:${entry.channelId}`),
    );
    return this.candidates().filter((candidate) => !taken.has(candidateKey(candidate)));
  });

  async ngOnInit(): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null) {
      this.loading.set(false);
      return;
    }
    try {
      await this.refresh(scope);
    } catch (failure) {
      this.fail(failure, true);
    } finally {
      this.loading.set(false);
    }
  }

  protected layoutKey(layout: StorefrontLayout): MessageKey {
    return LAYOUT_KEYS[layout];
  }

  protected candidateKey(candidate: SwitcherCandidate): string {
    return candidateKey(candidate);
  }

  protected status(entry: SwitcherEntry): MessageKey {
    let status: EntryStatus = 'shown';
    if (entry.state === 'HIDDEN') {
      status = 'hidden';
    } else if (entry.hostname === null) {
      status = 'noHostname';
    } else if (!entry.shown) {
      status = 'inactive';
    }
    return STATUS_KEYS[status];
  }

  protected async setLayout(layout: StorefrontLayout): Promise<void> {
    const current = this.policies();
    if (current === null || current.layout === layout) {
      return;
    }
    await this.act(
      (scope) => this.content.setLayoutPolicy(scope, current.layoutVersion, layout),
      (updated) => this.policies.set(updated),
      'marketing.storefront.layout.saved',
      true,
    );
  }

  protected async add(): Promise<void> {
    const chosen = this.available().find((candidate) => candidateKey(candidate) === this.pick());
    if (chosen === undefined) {
      return;
    }
    await this.act(
      (scope) => this.merchandising.addSwitcherEntry(scope, chosen.brandId, chosen.channelId),
      () => this.pick.set(''),
      'marketing.storefront.switcher.added',
    );
  }

  protected async toggle(entry: SwitcherEntry): Promise<void> {
    await this.act(
      (scope) =>
        this.merchandising.setSwitcherEntryState(
          scope,
          entry,
          entry.state === 'ACTIVE' ? 'HIDDEN' : 'ACTIVE',
        ),
      () => undefined,
      'marketing.storefront.switcher.updated',
    );
  }

  protected async remove(entry: SwitcherEntry): Promise<void> {
    await this.act(
      (scope) => this.merchandising.removeSwitcherEntry(scope, entry.id),
      () => undefined,
      'marketing.storefront.switcher.removed',
    );
  }

  protected async move(entry: SwitcherEntry, by: -1 | 1): Promise<void> {
    const ids = this.entries().map((candidate) => candidate.id);
    const from = ids.indexOf(entry.id);
    const to = from + by;
    if (from < 0 || to < 0 || to >= ids.length) {
      return;
    }
    ids.splice(to, 0, ...ids.splice(from, 1));
    await this.act(
      (scope) => this.merchandising.reorderSwitcher(scope, ids),
      () => undefined,
      'marketing.storefront.switcher.reordered',
    );
  }

  private async act<T>(
    write: (scope: BrandScope) => Promise<T>,
    after: (result: T) => void,
    done: MessageKey,
    layout = false,
  ): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.saved.set(null);
    try {
      after(await write(scope));
      this.saved.set(done);
    } catch (failure) {
      this.fail(failure, false);
    }
    try {
      // Whatever happened, show what the platform now holds: a refused write must not leave the
      // screen showing a state that was not saved, and a stale version is cured by reading again.
      await this.refresh(scope);
    } catch {
      // The message above already says something went wrong.
    }
    if (layout) {
      this.round.update((n) => n + 1);
    }
    this.busy.set(false);
  }

  private async refresh(scope: BrandScope): Promise<void> {
    const [entries, candidates, policies] = await Promise.all([
      this.merchandising.switcherEntries(scope),
      this.merchandising.switcherCandidates(scope),
      this.content.policies(scope),
    ]);
    this.entries.set(entries);
    this.candidates.set(candidates);
    this.policies.set(policies);
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
