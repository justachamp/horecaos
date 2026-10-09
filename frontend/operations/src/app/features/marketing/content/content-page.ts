import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { CurrentBrand } from '../../../core/auth/current-brand';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import {
  ContentApi,
  ContentKind,
  ContentPlacement,
  ContentPolicies,
  UnhealthyItem,
} from './content-api';
import { ContentHealthPanel } from './content-health-panel';
import { ContentHostsPanel } from './content-hosts-panel';
import { ContentPolicyPanel } from './content-policy-panel';
import { ContentReferences } from './content-references';
import { ContentWorkbench } from './content-workbench';
import { KIND_KEYS, PLACEMENT_KEYS } from './content-texts';

type PageTab = 'items' | 'links' | 'settings' | 'health';

/** One list of the page: a placement that is ordered, or an editorial kind. */
export interface ContentView {
  readonly id: string;
  readonly kinds: readonly ContentKind[];
  readonly placement: ContentPlacement | null;
  readonly label: MessageKey;
}

/** The lists of the content page that exist for every brand (Release 1 and 2). */
export const HOME_VIEWS: readonly ContentView[] = [
  {
    id: 'carousel',
    kinds: ['BANNER', 'PROMO_CARD'],
    placement: 'HOME_CAROUSEL',
    label: PLACEMENT_KEYS['HOME_CAROUSEL'],
  },
  {
    id: 'grid',
    kinds: ['PROMO_CARD'],
    placement: 'PROMO_GRID',
    label: PLACEMENT_KEYS['PROMO_GRID'],
  },
  {
    id: 'stories',
    kinds: ['STORY_GROUP'],
    placement: 'STORY_RAIL',
    label: PLACEMENT_KEYS['STORY_RAIL'],
  },
  {
    id: 'popup',
    kinds: ['POPUP'],
    placement: 'ENTRY_MODAL',
    label: PLACEMENT_KEYS['ENTRY_MODAL'],
  },
];

/** The editorial kinds, the policy flag that switches each on and the list's label (Release 5). */
const EDITORIAL_VIEWS: readonly {
  kind: ContentKind;
  flag: keyof ContentPolicies['editorial'];
}[] = [
  { kind: 'NEWS', flag: 'news' },
  { kind: 'GALLERY_ALBUM', flag: 'gallery' },
  { kind: 'RECIPE', flag: 'recipes' },
  { kind: 'VACANCY', flag: 'vacancies' },
];

const TAB_KEYS: Readonly<Record<PageTab, MessageKey>> = {
  items: 'marketing.content.page.tab.items',
  links: 'marketing.content.page.tab.links',
  settings: 'marketing.content.page.tab.settings',
  health: 'marketing.content.page.tab.health',
};

/**
 * Marketing, Content (gap-map rows `6.7` and `6.7a`; ADR 0157): what a brand puts in front of its
 * customers beside the menu.
 *
 * Banners and promo cards in the home carousel, promo cards in the grid, stories, the entry pop-up
 * and -- only when the plan includes the editorial tier **and** the brand has switched the kind on
 * -- news, photo albums, recipes and vacancy listings. Each is a list with an editor beside it
 * ({@link ContentWorkbench}). Beside the lists: the sites an external link may point at, the two
 * content policies, and the health read of what customers are being shown right now.
 *
 * Nothing here reaches a customer until it is published, and the page never predicts a capability:
 * a principal without one is told which it is missing by the server's own refusal.
 */
@Component({
  selector: 'q-content-page',
  imports: [ContentHealthPanel, ContentHostsPanel, ContentPolicyPanel, ContentWorkbench, TPipe],
  providers: [ContentReferences],
  templateUrl: './content-page.html',
  styleUrl: './content-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentPage implements OnInit {
  private readonly api = inject(ContentApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly refs = inject(ContentReferences);

  protected readonly tab = signal<PageTab>('items');
  protected readonly tabs: readonly PageTab[] = ['items', 'links', 'settings', 'health'];
  protected readonly policies = signal<ContentPolicies | null>(null);
  protected readonly unhealthy = signal(0);
  protected readonly activeView = signal('carousel');
  protected readonly focusItemId = signal<string | null>(null);

  /** The home lists, then each editorial kind that the plan and the brand both allow. */
  protected readonly views = computed<readonly ContentView[]>(() => {
    const policies = this.policies();
    const editorial = EDITORIAL_VIEWS.filter(
      (entry) => policies !== null && policies.editorialEntitled && policies.editorial[entry.flag],
    ).map((entry): ContentView => ({
      id: entry.kind.toLowerCase(),
      kinds: [entry.kind],
      placement: null,
      label: KIND_KEYS[entry.kind],
    }));
    return [...HOME_VIEWS, ...editorial];
  });

  protected readonly view = computed(
    () => this.views().find((candidate) => candidate.id === this.activeView()) ?? this.views()[0],
  );

  async ngOnInit(): Promise<void> {
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    if (scope === null) {
      return;
    }
    await Promise.all([this.refs.load(scope), this.readPolicies(), this.refreshHealth()]);
  }

  protected tabLabel(tab: PageTab): MessageKey {
    return TAB_KEYS[tab];
  }

  protected chooseView(id: string): void {
    this.focusItemId.set(null);
    this.activeView.set(id);
  }

  protected onPolicies(policies: ContentPolicies): void {
    this.policies.set(policies);
    // Switching a kind off must not leave the page on a list that no longer exists.
    if (!this.views().some((candidate) => candidate.id === this.activeView())) {
      this.activeView.set('carousel');
    }
  }

  /** The hosts changed: the link pickers must see the new list. */
  protected async onHostsChanged(): Promise<void> {
    const scope = this.brand.scope();
    if (scope !== null) {
      await this.refs.reloadHosts(scope);
    }
    await this.refreshHealth();
  }

  /** From the health list: show the item in the list it belongs to. */
  protected openUnhealthy(item: UnhealthyItem): void {
    const target = this.views().find((candidate) =>
      item.placement !== null
        ? candidate.placement === item.placement && candidate.kinds.includes(item.kind)
        : candidate.kinds.includes(item.kind),
    );
    this.activeView.set(target?.id ?? 'carousel');
    this.focusItemId.set(item.id);
    this.tab.set('items');
  }

  protected async refreshHealth(): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null) {
      return;
    }
    try {
      this.unhealthy.set((await this.api.health(scope)).length);
    } catch {
      // The count is a convenience; the health tab says why it could not be read.
    }
  }

  private async readPolicies(): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null) {
      return;
    }
    try {
      this.policies.set(await this.api.policies(scope));
    } catch {
      // Without them the editorial lists simply do not appear and video is not offered.
    }
  }
}
