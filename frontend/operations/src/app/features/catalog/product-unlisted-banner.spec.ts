import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ListingNotice, ProductUnlistedBanner, UnlistedRow } from './product-unlisted-banner';

const ROWS: readonly UnlistedRow[] = [
  { variantId: 'v1', count: 3, label: 'Large' },
  { variantId: 'v2', count: 1, label: 'Small' },
];

function render(
  overrides: { pending?: ReadonlySet<string>; notices?: Record<string, ListingNotice> } = {},
) {
  TestBed.resetTestingModule();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ProductUnlistedBanner);
  fixture.componentRef.setInput('rows', ROWS);
  fixture.componentRef.setInput('pendingVariantIds', overrides.pending ?? new Set<string>());
  fixture.componentRef.setInput('notices', overrides.notices ?? {});
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const byTestId = (id: string) => host.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  return { fixture, host, byTestId };
}

describe('ProductUnlistedBanner', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists each variant with how many branches do not list it', () => {
    const { byTestId } = render();

    expect(byTestId('editor-unlisted-variant-v1')?.textContent).toContain(
      'Large — not listed at 3 branch(es)',
    );
    expect(byTestId('editor-unlisted-variant-v2')?.textContent).toContain(
      'Small — not listed at 1 branch(es)',
    );
  });

  it('offers to list at every branch, and reports which variant was asked for', () => {
    const { fixture, byTestId } = render();
    const asked: string[] = [];
    fixture.componentInstance.listRequested.subscribe((id) => asked.push(id));

    expect(byTestId('editor-list-missing-branches-v2')?.textContent?.trim()).toBe(
      'List at every branch',
    );
    byTestId('editor-list-missing-branches-v2')?.click();

    expect(asked).toEqual(['v2']);
  });

  it('holds only the button of the variant whose request is in flight', () => {
    const { byTestId } = render({ pending: new Set(['v1']) });

    const busy = byTestId('editor-list-missing-branches-v1') as HTMLButtonElement;
    expect(busy.disabled).toBe(true);
    expect(busy.textContent?.trim()).toBe('Listing…');
    expect((byTestId('editor-list-missing-branches-v2') as HTMLButtonElement).disabled).toBe(false);
  });

  it('says what a partial listing left over, and when the recount could not be read', () => {
    const { byTestId } = render({
      notices: {
        v1: { kind: 'partial', listed: 2, candidate: 3 },
        v2: { kind: 'recountFailed' },
      },
    });

    expect(byTestId('editor-listing-notice-v1')?.textContent).toContain('2');
    expect(byTestId('editor-listing-notice-v1')?.textContent).toContain('3');
    expect(byTestId('editor-listing-notice-v2')?.getAttribute('role')).toBe('status');
    expect(byTestId('editor-listing-notice-v2')?.textContent?.trim()).not.toBe('');
  });

  it('draws no notice for a variant the last click fully resolved', () => {
    const { byTestId } = render({ notices: { v1: { kind: 'recountFailed' } } });

    expect(byTestId('editor-listing-notice-v2')).toBeNull();
  });
});
