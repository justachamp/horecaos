import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { CurrentBrand } from '../../../core/auth/current-brand';
import { LocaleSet } from '../../../core/i18n/locale-set';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ContentApi } from '../content/content-api';
import { ContentPreview } from '../content/content-preview';
import { ContentReferences } from '../content/content-references';
import { ContentWorkbench } from '../content/content-workbench';
import { StorefrontBranchOrder } from './storefront-branch-order';
import { StorefrontSwitcher } from './storefront-switcher';

type StorefrontTab = 'groups' | 'carousel' | 'preview' | 'switcher' | 'branches';

const TAB_KEYS: Readonly<Record<StorefrontTab, MessageKey>> = {
  groups: 'marketing.storefront.tab.groups',
  carousel: 'marketing.storefront.tab.carousel',
  preview: 'marketing.storefront.tab.preview',
  switcher: 'marketing.storefront.tab.switcher',
  branches: 'marketing.storefront.tab.branches',
};

/**
 * Marketing, Storefront (gap-map row `6.8`; ADR 0157, Decisions 5 and 12): what a channel's home
 * screen shows and in what order -- the merchandiser's decision, not a front-end code change.
 *
 * - **Home groups**: titled rails of dishes, either hand-picked in an order or a whole category with
 *   an order and a limit. Their order on the home screen is set on the flat sortable list.
 * - **The offer carousel** is the home carousel that banners and promo cards share: here you order
 *   it (authoring is under Content). It is *content* the brand chose for everyone, not ADR 0112's
 *   personalised offer, which stays a separate, per-guest read.
 * - **Preview**: the whole home document built from drafts, by the same assembler as production.
 * - **Brand switcher and layout** (ADR 0183 D17, which built them without waiting for the trigger
 *   ADR 0157 named): the strip of the tenant's other brands, and the choice between the standard
 *   category navigation and the marketplace one. Both are quiet until used: with no entry and the
 *   standard layout a storefront is exactly what it was.
 * - **Branch order**: the order the storefront lists the brand's branches in when the customer has
 *   shared no location, and the tie-break after distance when they have.
 */
@Component({
  selector: 'q-storefront-page',
  imports: [
    ContentPreview,
    ContentWorkbench,
    RouterLink,
    StorefrontBranchOrder,
    StorefrontSwitcher,
    TPipe,
  ],
  providers: [ContentReferences],
  templateUrl: './storefront-page.html',
  styleUrl: './storefront-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StorefrontPage implements OnInit {
  private readonly api = inject(ContentApi);
  private readonly brand = inject(CurrentBrand);
  private readonly localeSet = inject(LocaleSet);
  private readonly refs = inject(ContentReferences);

  protected readonly tab = signal<StorefrontTab>('groups');
  protected readonly tabs: readonly StorefrontTab[] = [
    'groups',
    'carousel',
    'preview',
    'switcher',
    'branches',
  ];
  protected readonly videoEntitled = signal(false);
  protected readonly locales = computed(() => this.localeSet.locales());
  protected readonly defaultLocale = computed(() => this.localeSet.defaultLocale());

  async ngOnInit(): Promise<void> {
    await Promise.all([this.brand.ensureLoaded(), this.localeSet.ensureLoaded()]);
    const scope = this.brand.scope();
    if (scope === null) {
      return;
    }
    await Promise.all([
      this.refs.load(scope),
      this.api.policies(scope).then(
        (policies) => this.videoEntitled.set(policies.videoEntitled),
        () => undefined,
      ),
    ]);
  }

  protected tabLabel(tab: StorefrontTab): MessageKey {
    return TAB_KEYS[tab];
  }
}
