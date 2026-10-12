import { Component, input, output, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it } from 'vitest';

import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import {
  ContentApi,
  ContentKind,
  ContentPlacement,
  ContentPolicies,
  UnhealthyItem,
} from './content-api';
import { ContentHealthPanel } from './content-health-panel';
import { ContentHostsPanel } from './content-hosts-panel';
import { ContentPage } from './content-page';
import { ContentPolicyPanel } from './content-policy-panel';
import { ContentReferences } from './content-references';
import { ContentWorkbench } from './content-workbench';

@Component({ selector: 'q-content-workbench', template: '' })
class StubWorkbench {
  readonly kinds = input<readonly ContentKind[]>([]);
  readonly placement = input<ContentPlacement | null>(null);
  readonly focusItemId = input<string | null>(null);
  readonly videoEntitled = input(false);
  readonly canCreate = input(true);
  readonly changed = output<void>();
}
@Component({ selector: 'q-content-hosts', template: '' })
class StubHosts {
  readonly changed = output<void>();
}
@Component({ selector: 'q-content-policies', template: '' })
class StubPolicies {
  readonly policiesChanged = output<ContentPolicies>();
}
@Component({ selector: 'q-content-health', template: '' })
class StubHealth {
  readonly open = output<UnhealthyItem>();
  readonly counted = output<number>();
}

const POLICIES: ContentPolicies = {
  publishing: { requireAllBrandLocales: false },
  publishingVersion: 1,
  editorial: { news: true, gallery: false, recipes: false, vacancies: true },
  editorialVersion: 1,
  editorialEntitled: true,
  videoEntitled: true,
};

class FakeApi {
  healthCalls = 0;
  unhealthy: UnhealthyItem[] = [];
  policies = (): Promise<ContentPolicies> => Promise.resolve(POLICIES);
  health(): Promise<readonly UnhealthyItem[]> {
    this.healthCalls += 1;
    return Promise.resolve(this.unhealthy);
  }
}

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

async function setup(api = new FakeApi()): Promise<{
  fixture: ComponentFixture<ContentPage>;
  api: FakeApi;
  refs: { reloadHosts: number; loaded: number };
  root: HTMLElement;
}> {
  const refs = { reloadHosts: 0, loaded: 0 };
  TestBed.configureTestingModule({
    providers: [
      { provide: ContentApi, useValue: api },
      {
        provide: CurrentBrand,
        useValue: {
          scope: signal({ tenantId: 't', brandId: 'b' }),
          ensureLoaded: () => Promise.resolve(),
        },
      },
    ],
  });
  TestBed.overrideComponent(ContentPage, {
    remove: {
      imports: [ContentWorkbench, ContentHostsPanel, ContentPolicyPanel, ContentHealthPanel],
    },
    add: { imports: [StubWorkbench, StubHosts, StubPolicies, StubHealth] },
  });
  TestBed.overrideComponent(ContentPage, {
    set: {
      providers: [
        {
          provide: ContentReferences,
          useValue: {
            load: () => {
              refs.loaded += 1;
              return Promise.resolve();
            },
            reloadHosts: () => {
              refs.reloadHosts += 1;
              return Promise.resolve();
            },
          },
        },
      ],
    },
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentPage);
  fixture.detectChanges();
  await settle();
  await settle();
  fixture.detectChanges();
  return { fixture, api, refs, root: fixture.nativeElement as HTMLElement };
}

const workbench = (fixture: ComponentFixture<ContentPage>): StubWorkbench =>
  fixture.debugElement.query(By.directive(StubWorkbench)).componentInstance as StubWorkbench;
const views = (root: HTMLElement): string[] =>
  Array.from(root.querySelectorAll('[data-testid^="content-view-"]')).map((b) =>
    b.getAttribute('data-testid')!.replace('content-view-', ''),
  );
const click = (root: HTMLElement, id: string): void =>
  (root.querySelector(`[data-testid="${id}"]`) as HTMLButtonElement).click();

describe('ContentPage (gap-map rows 6.7 and 6.7a; ADR 0157)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('reads the brand’s references once, on opening', async () => {
    const { refs } = await setup();

    expect(refs.loaded).toBe(1);
  });

  it('offers the four home lists, and an editorial list only when the plan and the brand both allow it', async () => {
    const { root } = await setup();

    expect(views(root)).toEqual(['carousel', 'grid', 'stories', 'popup', 'news', 'vacancy']);
  });

  it('offers no editorial list when the plan does not include the tier, whatever the brand chose', async () => {
    const api = new FakeApi();
    api.policies = () => Promise.resolve({ ...POLICIES, editorialEntitled: false });
    const { root } = await setup(api);

    expect(views(root)).toEqual(['carousel', 'grid', 'stories', 'popup']);
  });

  it('offers none when the policies cannot be read', async () => {
    const api = new FakeApi();
    api.policies = () => Promise.reject(new Error('403'));
    const { root } = await setup(api);

    expect(views(root)).toEqual(['carousel', 'grid', 'stories', 'popup']);
  });

  it('starts on the home carousel, which holds banners and promo cards', async () => {
    const { fixture } = await setup();

    expect(workbench(fixture).kinds()).toEqual(['BANNER', 'PROMO_CARD']);
    expect(workbench(fixture).placement()).toBe('HOME_CAROUSEL');
  });

  it('gives each list its own kinds and placement', async () => {
    const { fixture, root } = await setup();

    click(root, 'content-view-grid');
    fixture.detectChanges();
    expect([workbench(fixture).kinds(), workbench(fixture).placement()]).toEqual([
      ['PROMO_CARD'],
      'PROMO_GRID',
    ]);

    click(root, 'content-view-popup');
    fixture.detectChanges();
    expect([workbench(fixture).kinds(), workbench(fixture).placement()]).toEqual([
      ['POPUP'],
      'ENTRY_MODAL',
    ]);

    click(root, 'content-view-news');
    fixture.detectChanges();
    expect([workbench(fixture).kinds(), workbench(fixture).placement()]).toEqual([['NEWS'], null]);
  });

  it('tells the lists whether the plan allows video', async () => {
    const { fixture } = await setup();

    expect(workbench(fixture).videoEntitled()).toBe(true);
  });

  it('shows how many things need attention, and goes to them', async () => {
    const api = new FakeApi();
    api.unhealthy = [
      {
        id: 'a',
        kind: 'BANNER',
        placement: 'HOME_CAROUSEL',
        displayTitle: 'x',
        visibility: 'LIVE',
        problems: [],
      },
    ];
    const { fixture, root } = await setup(api);

    expect(root.querySelector('[data-testid="content-health-badge"]')?.textContent).toContain(
      '1 need attention',
    );
    click(root, 'content-health-badge');
    fixture.detectChanges();

    expect(fixture.debugElement.query(By.directive(StubHealth))).toBeTruthy();
  });

  it('shows no badge when nothing needs attention', async () => {
    const { root } = await setup();

    expect(root.querySelector('[data-testid="content-health-badge"]')).toBeNull();
  });

  it('opens an unhealthy item in the list it belongs to', async () => {
    const { fixture, root } = await setup();
    click(root, 'content-page-tab-health');
    fixture.detectChanges();

    (
      fixture.debugElement.query(By.directive(StubHealth)).componentInstance as StubHealth
    ).open.emit({
      id: 'card-9',
      kind: 'PROMO_CARD',
      placement: 'PROMO_GRID',
      displayTitle: 'Lunch',
      visibility: 'LIVE',
      problems: [],
    });
    fixture.detectChanges();

    expect(workbench(fixture).placement()).toBe('PROMO_GRID');
    expect(workbench(fixture).focusItemId()).toBe('card-9');
    expect(
      root.querySelector('[data-testid="content-page-tab-items"]')?.getAttribute('aria-selected'),
    ).toBe('true');
  });

  it('reads the health again when something is saved, published or reordered', async () => {
    const { fixture, api } = await setup();
    const before = api.healthCalls;

    workbench(fixture).changed.emit();
    await settle();

    expect(api.healthCalls).toBe(before + 1);
  });

  it('gives the link pickers the new list of sites when the allowlist changes', async () => {
    const { fixture, refs, root } = await setup();
    click(root, 'content-page-tab-links');
    fixture.detectChanges();

    (
      fixture.debugElement.query(By.directive(StubHosts)).componentInstance as StubHosts
    ).changed.emit();
    await settle();

    expect(refs.reloadHosts).toBe(1);
  });

  it('drops the list of a kind that has just been switched off and falls back to the carousel', async () => {
    const { fixture, root } = await setup();
    click(root, 'content-view-news');
    click(root, 'content-page-tab-settings');
    fixture.detectChanges();

    (
      fixture.debugElement.query(By.directive(StubPolicies)).componentInstance as StubPolicies
    ).policiesChanged.emit({
      ...POLICIES,
      editorial: { news: false, gallery: false, recipes: false, vacancies: true },
    });
    click(root, 'content-page-tab-items');
    fixture.detectChanges();

    expect(views(root)).toEqual(['carousel', 'grid', 'stories', 'popup', 'vacancy']);
    expect(workbench(fixture).placement()).toBe('HOME_CAROUSEL');
  });
});
