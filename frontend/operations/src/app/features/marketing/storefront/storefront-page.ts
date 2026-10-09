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

type StorefrontTab = 'groups' | 'carousel' | 'preview' | 'switcher';

const TAB_KEYS: Readonly<Record<StorefrontTab, MessageKey>> = {
  groups: 'marketing.storefront.tab.groups',
  carousel: 'marketing.storefront.tab.carousel',
  preview: 'marketing.storefront.tab.preview',
  switcher: 'marketing.storefront.tab.switcher',
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
 * - **Brand switcher and marketplace layout** are not built. The record decides that they wait until
 *   a tenant runs two brands on one storefront and asks; no layout field exists before then.
 */
@Component({
  selector: 'q-storefront-page',
  imports: [ContentPreview, ContentWorkbench, RouterLink, TPipe],
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
  protected readonly tabs: readonly StorefrontTab[] = ['groups', 'carousel', 'preview', 'switcher'];
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
