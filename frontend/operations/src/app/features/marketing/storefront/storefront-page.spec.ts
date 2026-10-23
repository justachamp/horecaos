import { Component, input, output, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { LocaleSet } from '../../../core/i18n/locale-set';
import { ContentApi, ContentKind, ContentPlacement, ContentPolicies } from '../content/content-api';
import { ContentPreview } from '../content/content-preview';
import { ContentReferences } from '../content/content-references';
import { ContentWorkbench } from '../content/content-workbench';
import { StorefrontBranchOrder } from './storefront-branch-order';
import { StorefrontPage } from './storefront-page';
import { StorefrontSwitcher } from './storefront-switcher';

@Component({ selector: 'q-content-workbench', template: '' })
class StubWorkbench {
  readonly kinds = input<readonly ContentKind[]>([]);
  readonly placement = input<ContentPlacement | null>(null);
  readonly focusItemId = input<string | null>(null);
  readonly videoEntitled = input(false);
  readonly canCreate = input(true);
  readonly changed = output<void>();
}
@Component({ selector: 'q-storefront-switcher', template: '<p data-testid="stub-switcher"></p>' })
class StubSwitcher {}
@Component({
  selector: 'q-storefront-branch-order',
  template: '<p data-testid="stub-branches"></p>',
})
class StubBranches {}
@Component({ selector: 'q-content-preview', template: '' })
class StubPreview {
  readonly locales = input<readonly string[]>([]);
  readonly startLocale = input<string | null>(null);
  readonly highlightId = input<string | null>(null);
}

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

async function setup(
  policies: () => Promise<Partial<ContentPolicies>> = () =>
    Promise.resolve({ videoEntitled: true }),
): Promise<{ fixture: ComponentFixture<StorefrontPage>; root: HTMLElement; loaded: number[] }> {
  const loaded: number[] = [];
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: ContentApi, useValue: { policies } },
      {
        provide: CurrentBrand,
        useValue: {
          scope: signal({ tenantId: 't', brandId: 'b' }),
          ensureLoaded: () => Promise.resolve(),
        },
      },
      {
        provide: LocaleSet,
        useValue: {
          locales: signal(['ru', 'uz-Latn', 'en']),
          defaultLocale: signal('ru'),
          ensureLoaded: () => Promise.resolve(),
        },
      },
    ],
  });
  TestBed.overrideComponent(StorefrontPage, {
    remove: {
      imports: [ContentWorkbench, ContentPreview, StorefrontSwitcher, StorefrontBranchOrder],
    },
    add: { imports: [StubWorkbench, StubPreview, StubSwitcher, StubBranches] },
  });
  TestBed.overrideComponent(StorefrontPage, {
    set: {
      providers: [
        {
          provide: ContentReferences,
          useValue: {
            load: () => {
              loaded.push(1);
              return Promise.resolve();
            },
          },
        },
      ],
    },
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(StorefrontPage);
  fixture.detectChanges();
  await settle();
  await settle();
  fixture.detectChanges();
  return { fixture, root: fixture.nativeElement as HTMLElement, loaded };
}

const click = (root: HTMLElement, id: string): void =>
  (root.querySelector(`[data-testid="${id}"]`) as HTMLButtonElement).click();
const workbench = (fixture: ComponentFixture<StorefrontPage>): StubWorkbench =>
  fixture.debugElement.query(By.directive(StubWorkbench)).componentInstance as StubWorkbench;

describe('StorefrontPage (gap-map row 6.8; ADR 0157, Decisions 5 and 12)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('opens on the home groups: product groups in their own placement', async () => {
    const { fixture, loaded } = await setup();

    expect(workbench(fixture).kinds()).toEqual(['PRODUCT_GROUP']);
    expect(workbench(fixture).placement()).toBe('HOME_GROUPS');
    expect(workbench(fixture).canCreate()).toBe(true);
    expect(loaded).toHaveLength(1);
  });

  it('orders the offer carousel here but leaves writing the banners and cards to Content', async () => {
    const { fixture, root } = await setup();

    click(root, 'storefront-tab-carousel');
    fixture.detectChanges();

    expect(workbench(fixture).kinds()).toEqual(['BANNER', 'PROMO_CARD']);
    expect(workbench(fixture).placement()).toBe('HOME_CAROUSEL');
    expect(workbench(fixture).canCreate()).toBe(false);
    expect(workbench(fixture).videoEntitled()).toBe(true);
    expect(
      root.querySelector('[data-testid="storefront-content-link"]')?.getAttribute('href'),
    ).toBe('/marketing/content');
    expect(root.querySelector('[data-testid="storefront-carousel-note"]')?.textContent).toContain(
      'personal offer for one guest',
    );
  });

  it('previews the whole home screen in the brand’s languages, starting from its default', async () => {
    const { fixture, root } = await setup();

    click(root, 'storefront-tab-preview');
    fixture.detectChanges();

    const preview = fixture.debugElement.query(By.directive(StubPreview))
      .componentInstance as StubPreview;
    expect(preview.locales()).toEqual(['ru', 'uz-Latn', 'en']);
    expect(preview.startLocale()).toBe('ru');
    expect(preview.highlightId()).toBeNull();
  });

  it('opens the brand switcher and layout on its own tab, no longer a placeholder', async () => {
    const { fixture, root } = await setup();

    click(root, 'storefront-tab-switcher');
    fixture.detectChanges();

    expect(root.querySelector('[data-testid="stub-switcher"]')).not.toBeNull();
    expect(root.textContent).not.toContain('not built');
    expect(fixture.debugElement.query(By.directive(StubWorkbench))).toBeNull();
  });

  it('opens the order of branches on its own tab', async () => {
    const { fixture, root } = await setup();

    click(root, 'storefront-tab-branches');
    fixture.detectChanges();

    expect(root.querySelector('[data-testid="stub-branches"]')).not.toBeNull();
    expect(root.querySelector('[data-testid="stub-switcher"]')).toBeNull();
  });

  it('does not offer video when the plan does not include it, or cannot be read', async () => {
    const off = await setup(() => Promise.resolve({ videoEntitled: false }));
    click(off.root, 'storefront-tab-carousel');
    off.fixture.detectChanges();
    expect(workbench(off.fixture).videoEntitled()).toBe(false);

    TestBed.resetTestingModule();
    const unreadable = await setup(() => Promise.reject(new Error('403')));
    click(unreadable.root, 'storefront-tab-carousel');
    unreadable.fixture.detectChanges();
    expect(workbench(unreadable.fixture).videoEntitled()).toBe(false);
  });
});
