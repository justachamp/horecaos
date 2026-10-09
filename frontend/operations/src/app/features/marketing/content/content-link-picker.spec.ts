import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { Combobox } from '../../../shared/ui/combobox';
import { By } from '@angular/platform-browser';
import { LinkValue } from './content-document';
import { ContentLinkPicker } from './content-link-picker';
import { ContentReferences } from './content-references';

const CATEGORIES = [
  { id: 'c1', label: 'Pizza' },
  { id: 'c2', label: 'Drinks' },
];
const ALLOWED = new Set(['shop.example.uz']);

const fakeRefs = {
  products: (q: string) =>
    [{ id: 'p1', label: 'Margherita' }].filter((o) =>
      o.label.toLowerCase().includes(q.toLowerCase()),
    ),
  categories: (q: string) =>
    CATEGORIES.filter((o) => o.label.toLowerCase().includes(q.toLowerCase())),
  promotions: () => [{ id: 'm1', label: 'Lunch deal' }],
  productLabel: (id: string) => (id === 'p1' ? 'Margherita' : id),
  categoryLabel: (id: string) => CATEGORIES.find((c) => c.id === id)?.label ?? id,
  promotionLabel: (id: string) => (id === 'm1' ? 'Lunch deal' : id),
  hostAllowed: (url: string) => {
    try {
      return ALLOWED.has(new URL(url).hostname);
    } catch {
      return false;
    }
  },
};

function setup(link: LinkValue): {
  fixture: ReturnType<typeof TestBed.createComponent<ContentLinkPicker>>;
  emitted: LinkValue[];
  root: HTMLElement;
} {
  TestBed.configureTestingModule({
    providers: [{ provide: ContentReferences, useValue: fakeRefs }],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentLinkPicker);
  fixture.componentRef.setInput('link', link);
  const emitted: LinkValue[] = [];
  fixture.componentInstance.linkChange.subscribe((l) => emitted.push(l));
  fixture.detectChanges();
  return { fixture, emitted, root: fixture.nativeElement as HTMLElement };
}

const NONE: LinkValue = { type: 'NONE', targetId: null, url: '' };

describe('ContentLinkPicker (ADR 0157, Decision 3: a closed union of link types)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('offers exactly the six link types', () => {
    const { root } = setup(NONE);
    const options = Array.from(
      root.querySelectorAll<HTMLOptionElement>('[data-testid="content-link-type"] option'),
    ).map((o) => o.value);

    expect(options).toEqual(['NONE', 'PRODUCT', 'CATEGORY', 'CART', 'PROMOTION', 'EXTERNAL']);
  });

  it('drops what the old type named when the type changes: a category id is not a product id', () => {
    const { root, emitted } = setup({ type: 'CATEGORY', targetId: 'c1', url: '' });
    const select = root.querySelector<HTMLSelectElement>('[data-testid="content-link-type"]')!;

    select.value = 'PRODUCT';
    select.dispatchEvent(new Event('change'));

    expect(emitted).toEqual([{ type: 'PRODUCT', targetId: null, url: '' }]);
  });

  it('shows a search for a dish, a category and a promotion, and nothing for the cart or none', () => {
    for (const type of ['PRODUCT', 'CATEGORY', 'PROMOTION'] as const) {
      TestBed.resetTestingModule();
      const { fixture } = setup({ type, targetId: null, url: '' });
      expect(fixture.debugElement.query(By.directive(Combobox))).toBeTruthy();
    }
    for (const type of ['CART', 'NONE'] as const) {
      TestBed.resetTestingModule();
      const { fixture, root } = setup({ type, targetId: null, url: '' });
      expect(fixture.debugElement.query(By.directive(Combobox))).toBeNull();
      expect(root.querySelector('[data-testid="content-link-url"]')).toBeNull();
    }
  });

  it('chooses a target from the search and shows its name', () => {
    const { fixture, emitted } = setup({ type: 'CATEGORY', targetId: null, url: '' });

    (
      fixture.debugElement.query(By.directive(Combobox)).componentInstance as Combobox
    ).optionSelected.emit(CATEGORIES[1]);

    expect(emitted).toEqual([{ type: 'CATEGORY', targetId: 'c2', url: '' }]);
  });

  it('names a chosen target and lets the author change it', () => {
    const { root, emitted } = setup({ type: 'CATEGORY', targetId: 'c1', url: '' });

    expect(root.querySelector('[data-testid="content-link-chosen"]')?.textContent).toContain(
      'Chosen: Pizza',
    );
    (root.querySelector('[data-testid="content-link-clear"]') as HTMLButtonElement).click();

    expect(emitted).toEqual([{ type: 'CATEGORY', targetId: null, url: '' }]);
  });

  it('reports a typed address', () => {
    const { root, emitted } = setup({ type: 'EXTERNAL', targetId: null, url: '' });
    const input = root.querySelector<HTMLInputElement>('[data-testid="content-link-url"]')!;

    input.value = 'https://shop.example.uz/menu';
    input.dispatchEvent(new Event('input'));

    expect(emitted).toEqual([
      { type: 'EXTERNAL', targetId: null, url: 'https://shop.example.uz/menu' },
    ]);
  });

  it('refuses an address that is not https while it is typed', () => {
    const { root } = setup({ type: 'EXTERNAL', targetId: null, url: 'http://shop.example.uz' });

    expect(root.querySelector('[data-testid="content-link-not-https"]')).toBeTruthy();
    expect(root.querySelector('[data-testid="content-link-host"]')).toBeNull();
  });

  it('warns about a host that is not on the allowlist, and says nothing about one that is', () => {
    const outside = setup({
      type: 'EXTERNAL',
      targetId: null,
      url: 'https://elsewhere.example.org/a',
    });
    expect(outside.root.querySelector('[data-testid="content-link-host"]')).toBeTruthy();
    expect(outside.root.querySelector('[data-testid="content-link-not-https"]')).toBeNull();

    TestBed.resetTestingModule();
    const inside = setup({ type: 'EXTERNAL', targetId: null, url: 'https://shop.example.uz/a' });
    expect(inside.root.querySelector('[data-testid="content-link-host"]')).toBeNull();
    expect(inside.root.querySelector('[data-testid="content-link-not-https"]')).toBeNull();
  });
});
