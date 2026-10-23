import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { ContentApi, ContentPolicies, StorefrontLayout } from '../content/content-api';
import {
  StorefrontMerchandisingApi,
  SwitcherCandidate,
  SwitcherEntry,
} from './storefront-merchandising-api';
import { StorefrontSwitcher } from './storefront-switcher';

const entry = (id: string, over: Partial<SwitcherEntry> = {}): SwitcherEntry => ({
  id,
  targetBrandId: `brand-${id}`,
  targetBrandName: `Brand ${id}`,
  channelId: `channel-${id}`,
  channelCode: `WEB-${id}`,
  hostname: `${id}.example.uz`,
  sortKey: 10,
  state: 'ACTIVE',
  shown: true,
  version: 3,
  ...over,
});

const candidate = (id: string, over: Partial<SwitcherCandidate> = {}): SwitcherCandidate => ({
  brandId: `brand-${id}`,
  brandName: `Brand ${id}`,
  channelId: `channel-${id}`,
  channelCode: `WEB-${id}`,
  hostname: `${id}.example.uz`,
  ...over,
});

const policies = (layout: StorefrontLayout, layoutVersion: number): ContentPolicies => ({
  publishing: { requireAllBrandLocales: false },
  publishingVersion: 0,
  editorial: { news: false, gallery: false, recipes: false, vacancies: false },
  editorialVersion: 0,
  editorialEntitled: false,
  videoEntitled: false,
  layout,
  layoutVersion,
});

class FakeMerchandising {
  entries: readonly SwitcherEntry[] = [];
  candidates: readonly SwitcherCandidate[] = [];
  readFails: ApiError | null = null;
  writeFails: ApiError | null = null;
  readonly added: { brand: string; channel: string }[] = [];
  readonly states: { id: string; version: number; state: string }[] = [];
  readonly removed: string[] = [];
  readonly orders: (readonly string[])[] = [];

  switcherEntries(): Promise<readonly SwitcherEntry[]> {
    return this.readFails ? Promise.reject(this.readFails) : Promise.resolve(this.entries);
  }
  switcherCandidates(): Promise<readonly SwitcherCandidate[]> {
    return Promise.resolve(this.candidates);
  }
  addSwitcherEntry(_scope: unknown, brand: string, channel: string): Promise<SwitcherEntry> {
    if (this.writeFails) {
      return Promise.reject(this.writeFails);
    }
    this.added.push({ brand, channel });
    const added = entry(brand.replace('brand-', ''));
    this.entries = [...this.entries, added];
    return Promise.resolve(added);
  }
  setSwitcherEntryState(_s: unknown, target: SwitcherEntry, state: string): Promise<SwitcherEntry> {
    if (this.writeFails) {
      return Promise.reject(this.writeFails);
    }
    this.states.push({ id: target.id, version: target.version, state });
    const updated = {
      ...target,
      state: state as SwitcherEntry['state'],
      version: target.version + 1,
    };
    this.entries = this.entries.map((e) => (e.id === target.id ? updated : e));
    return Promise.resolve(updated);
  }
  removeSwitcherEntry(_s: unknown, id: string): Promise<void> {
    this.removed.push(id);
    this.entries = this.entries.filter((e) => e.id !== id);
    return Promise.resolve();
  }
  reorderSwitcher(_s: unknown, ids: readonly string[]): Promise<readonly SwitcherEntry[]> {
    this.orders.push(ids);
    this.entries = ids.map((id) => this.entries.find((e) => e.id === id) as SwitcherEntry);
    return Promise.resolve(this.entries);
  }
}

class FakeContent {
  current = policies('STANDARD', 4);
  readonly layouts: { version: number; layout: string }[] = [];
  layoutFails: ApiError | null = null;

  policies(): Promise<ContentPolicies> {
    return Promise.resolve(this.current);
  }
  setLayoutPolicy(
    _s: unknown,
    version: number,
    layout: StorefrontLayout,
  ): Promise<ContentPolicies> {
    if (this.layoutFails) {
      return Promise.reject(this.layoutFails);
    }
    this.layouts.push({ version, layout });
    this.current = policies(layout, version + 1);
    return Promise.resolve(this.current);
  }
}

interface Rig {
  fixture: ComponentFixture<StorefrontSwitcher>;
  root: HTMLElement;
  merchandising: FakeMerchandising;
  content: FakeContent;
}

async function setup(
  merchandising = new FakeMerchandising(),
  content = new FakeContent(),
): Promise<Rig> {
  TestBed.configureTestingModule({
    providers: [
      { provide: StorefrontMerchandisingApi, useValue: merchandising },
      { provide: ContentApi, useValue: content },
      { provide: CurrentBrand, useValue: { scope: signal({ tenantId: 't', brandId: 'b' }) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(StorefrontSwitcher);
  fixture.detectChanges();
  await vi.waitFor(() => {
    fixture.detectChanges();
    expect(
      fixture.nativeElement.querySelector('[data-testid="storefront-switcher"] > p'),
    ).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="switcher-entries"]')).not.toBeNull();
  });
  return { fixture, root: fixture.nativeElement as HTMLElement, merchandising, content };
}

const q = (root: HTMLElement, id: string): HTMLElement | null =>
  root.querySelector(`[data-testid="${id}"]`);
const click = (root: HTMLElement, id: string): void => (q(root, id) as HTMLElement).click();
const text = (root: HTMLElement, id: string): string => q(root, id)?.textContent?.trim() ?? '';

describe('StorefrontSwitcher (gap-map row 6.8; ADR 0183 D17)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('says plainly that the storefront is unchanged when no brand is offered', async () => {
    const { root } = await setup();

    expect(text(root, 'switcher-empty')).toContain('looks as it did before');
    expect(root.querySelector('[data-testid^="switcher-entry-"]')).toBeNull();
  });

  it('says for each entry whether a visitor would see it, and why not when they would not', async () => {
    const merchandising = new FakeMerchandising();
    merchandising.entries = [
      entry('a'),
      entry('b', { state: 'HIDDEN', shown: false }),
      entry('c', { hostname: null, shown: false }),
      entry('d', { shown: false }),
    ];
    const { root } = await setup(merchandising);

    expect(text(root, 'switcher-status-a')).toBe('Shown to customers');
    expect(text(root, 'switcher-status-b')).toBe('Hidden');
    expect(text(root, 'switcher-status-c')).toContain('no verified address yet');
    expect(text(root, 'switcher-status-d')).toContain('the brand is not active');
    expect(text(root, 'switcher-entry-a')).toContain('a.example.uz');
  });

  it('offers only the brands and websites that are not already in the strip, and adds the one chosen', async () => {
    const merchandising = new FakeMerchandising();
    merchandising.entries = [entry('a')];
    merchandising.candidates = [candidate('a'), candidate('b'), candidate('c', { hostname: null })];
    const { fixture, root } = await setup(merchandising);

    const options = Array.from(root.querySelectorAll('[data-testid="switcher-pick"] option')).map(
      (option) => option.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(options).toEqual([
      'Choose a brand…',
      'Brand b — WEB-b (b.example.uz)',
      'Brand c — WEB-c (no verified address yet)',
    ]);
    expect((q(root, 'switcher-add-submit') as HTMLButtonElement).disabled).toBe(true);

    const select = q(root, 'switcher-pick') as HTMLSelectElement;
    select.value = 'brand-b:channel-b';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    click(root, 'switcher-add-submit');

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(q(root, 'switcher-entry-b')).not.toBeNull();
    });
    expect(merchandising.added).toEqual([{ brand: 'brand-b', channel: 'channel-b' }]);
    expect(text(root, 'storefront-switcher-saved')).toBe('Brand added.');
  });

  it('tells the manager when there is no other brand to offer', async () => {
    const { root } = await setup();

    expect(text(root, 'switcher-no-candidates')).toContain('no other brand with a website');
    expect(q(root, 'switcher-pick')).toBeNull();
  });

  it('hides and shows an entry with the version it last read', async () => {
    const merchandising = new FakeMerchandising();
    merchandising.entries = [entry('a', { version: 7 })];
    const { fixture, root } = await setup(merchandising);

    click(root, 'switcher-toggle-a');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(text(root, 'switcher-status-a')).toBe('Hidden');
    });
    expect(merchandising.states).toEqual([{ id: 'a', version: 7, state: 'HIDDEN' }]);

    click(root, 'switcher-toggle-a');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(text(root, 'switcher-status-a')).toBe('Shown to customers');
    });
    expect(merchandising.states[1]).toEqual({ id: 'a', version: 8, state: 'ACTIVE' });
  });

  it('removes an entry', async () => {
    const merchandising = new FakeMerchandising();
    merchandising.entries = [entry('a'), entry('b')];
    const { fixture, root } = await setup(merchandising);

    click(root, 'switcher-remove-a');

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(q(root, 'switcher-entry-a')).toBeNull();
    });
    expect(merchandising.removed).toEqual(['a']);
    expect(q(root, 'switcher-entry-b')).not.toBeNull();
  });

  it('sends the whole new order when an entry moves, and cannot move the first up or the last down', async () => {
    const merchandising = new FakeMerchandising();
    merchandising.entries = [entry('a'), entry('b'), entry('c')];
    const { fixture, root } = await setup(merchandising);

    expect((q(root, 'switcher-up-a') as HTMLButtonElement).disabled).toBe(true);
    expect((q(root, 'switcher-down-c') as HTMLButtonElement).disabled).toBe(true);

    click(root, 'switcher-down-a');
    await vi.waitFor(() => expect(merchandising.orders).toEqual([['b', 'a', 'c']]));
    await vi.waitFor(() => {
      fixture.detectChanges();
      const ids = Array.from(root.querySelectorAll('[data-testid^="switcher-entry-"]')).map((row) =>
        row.getAttribute('data-testid'),
      );
      expect(ids).toEqual(['switcher-entry-b', 'switcher-entry-a', 'switcher-entry-c']);
    });
  });

  it('keeps the screen honest when a change is refused: it shows the reason and what is really saved', async () => {
    const merchandising = new FakeMerchandising();
    merchandising.entries = [entry('a')];
    merchandising.writeFails = new ApiError('STALE_VERSION', 412, null, null);
    const { fixture, root } = await setup(merchandising);

    click(root, 'switcher-toggle-a');

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(q(root, 'storefront-switcher-error')).not.toBeNull();
    });
    expect(text(root, 'switcher-status-a')).toBe('Shown to customers');
    expect(q(root, 'storefront-switcher-saved')).toBeNull();
  });

  it('chooses the layout with the policy version it last read and shows the saved choice', async () => {
    const { fixture, root, content } = await setup();

    expect((q(root, 'storefront-layout-STANDARD') as HTMLInputElement).checked).toBe(true);
    expect((q(root, 'storefront-layout-MARKETPLACE') as HTMLInputElement).checked).toBe(false);

    const marketplace = q(root, 'storefront-layout-MARKETPLACE') as HTMLInputElement;
    marketplace.checked = true;
    marketplace.dispatchEvent(new Event('change'));

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(text(root, 'storefront-switcher-saved')).toBe('Layout saved.');
    });
    expect(content.layouts).toEqual([{ version: 4, layout: 'MARKETPLACE' }]);
    expect((q(root, 'storefront-layout-MARKETPLACE') as HTMLInputElement).checked).toBe(true);
  });

  it('draws the radio back to what is saved when the layout save is refused', async () => {
    const content = new FakeContent();
    content.layoutFails = new ApiError('STALE_VERSION', 412, null, null);
    const { fixture, root } = await setup(new FakeMerchandising(), content);

    const marketplace = q(root, 'storefront-layout-MARKETPLACE') as HTMLInputElement;
    marketplace.checked = true;
    marketplace.dispatchEvent(new Event('change'));

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(q(root, 'storefront-switcher-error')).not.toBeNull();
    });
    expect((q(root, 'storefront-layout-STANDARD') as HTMLInputElement).checked).toBe(true);
    expect((q(root, 'storefront-layout-MARKETPLACE') as HTMLInputElement).checked).toBe(false);
  });

  it('shows the wall, not an empty list, to someone who cannot read content', async () => {
    const merchandising = new FakeMerchandising();
    merchandising.readFails = new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null);
    TestBed.configureTestingModule({
      providers: [
        { provide: StorefrontMerchandisingApi, useValue: merchandising },
        { provide: ContentApi, useValue: new FakeContent() },
        { provide: CurrentBrand, useValue: { scope: signal({ tenantId: 't', brandId: 'b' }) } },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(StorefrontSwitcher);
    fixture.detectChanges();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('q-denied-state')).not.toBeNull();
    });
    expect(fixture.nativeElement.querySelector('[data-testid="switcher-entries"]')).toBeNull();
  });
});
