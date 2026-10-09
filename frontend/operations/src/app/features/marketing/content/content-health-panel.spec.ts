import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { ContentApi, UnhealthyItem } from './content-api';
import { ContentHealthPanel } from './content-health-panel';

const ITEMS: UnhealthyItem[] = [
  {
    id: 'a',
    kind: 'BANNER',
    placement: 'HOME_CAROUSEL',
    displayTitle: 'Spring sale',
    visibility: 'LIVE',
    problems: [
      { field: 'link.targetId', code: 'CONTENT_TARGET_NOT_FOUND', message: '' },
      { field: 'link.url', code: 'CONTENT_LINK_HOST_NOT_ALLOWED', message: '' },
    ],
  },
  {
    id: 'b',
    kind: 'PROMO_CARD',
    placement: 'PROMO_GRID',
    displayTitle: 'Lunch',
    visibility: 'LIVE',
    problems: [{ field: 'promotionId', code: 'CONTENT_PROMOTION_NOT_LIVE', message: '' }],
  },
];

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

async function setup(health: () => Promise<readonly UnhealthyItem[]>) {
  TestBed.configureTestingModule({
    providers: [
      { provide: ContentApi, useValue: { health } },
      { provide: CurrentBrand, useValue: { scope: signal({ tenantId: 't', brandId: 'b' }) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentHealthPanel);
  const opened: UnhealthyItem[] = [];
  const counts: number[] = [];
  fixture.componentInstance.open.subscribe((i) => opened.push(i));
  fixture.componentInstance.counted.subscribe((n) => counts.push(n));
  fixture.detectChanges();
  await settle();
  fixture.detectChanges();
  return { fixture, root: fixture.nativeElement as HTMLElement, opened, counts };
}

describe('ContentHealthPanel (ADR 0157: the operator’s view of what the storefront degrades quietly)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists each live item with something wrong, each problem as its own sentence', async () => {
    const { root, counts } = await setup(() => Promise.resolve(ITEMS));

    const rows = Array.from(root.querySelectorAll('[data-testid="content-health-row"]'));
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Spring sale');
    expect(rows[0].textContent).toContain('not on the brand’s published menu');
    expect(rows[0].textContent).toContain('not on the list of allowed links');
    expect(rows[1].textContent).toContain('promotion this card is bound to is not live');
    expect(counts).toEqual([2]);
  });

  it('opens the item that was chosen', async () => {
    const { root, opened } = await setup(() => Promise.resolve(ITEMS));

    (root.querySelectorAll('[data-testid="content-health-row"]')[1] as HTMLButtonElement).click();

    expect(opened).toEqual([ITEMS[1]]);
  });

  it('says everything is as made when nothing is wrong', async () => {
    const { root, counts } = await setup(() => Promise.resolve([]));

    expect(root.querySelector('[data-testid="content-health-ok"]')).toBeTruthy();
    expect(counts).toEqual([0]);
  });

  it('does not claim health when the read itself failed', async () => {
    const { root, counts } = await setup(() =>
      Promise.reject(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null)),
    );

    expect(root.querySelector('[data-testid="content-health-ok"]')).toBeNull();
    expect(root.querySelector('[data-testid="content-health-error"]')).toBeTruthy();
    expect(counts).toEqual([]);
  });

  it('reads again on request and reports the new count', async () => {
    let answer: UnhealthyItem[] = ITEMS;
    const { fixture, counts } = await setup(() => Promise.resolve(answer));
    answer = [];

    await fixture.componentInstance.refresh();

    expect(counts).toEqual([2, 0]);
  });
});
