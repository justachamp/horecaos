import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import {
  ContentApi,
  ContentItemView,
  ContentRevisionSummary,
  ContentRevisionView,
} from './content-api';
import { ContentHistory } from './content-history';

const SCOPE = { tenantId: 't', brandId: 'b' };

const SUMMARY = (revisionNo: number, live = false): ContentRevisionSummary => ({
  revisionNo,
  contentHash: `h${revisionNo}`,
  channelCodes: [],
  startsAt: null,
  endsAt: null,
  promotionId: null,
  publishedBy: 'anna',
  publishedAt: '2026-10-08T07:00:00Z',
  live,
});

const REVISION = (
  revisionNo: number,
  title: string,
  channels: string[] = [],
): ContentRevisionView => ({
  revisionNo,
  contentHash: `h${revisionNo}`,
  document: { schemaVersion: 1, texts: { ru: { title } } },
  channelCodes: channels,
  startsAt: null,
  endsAt: null,
  promotionId: null,
  locationId: null,
  publishedBy: 'anna',
  publishedAt: '2026-10-08T07:00:00Z',
});

class FakeApi {
  summaries: readonly ContentRevisionSummary[] = [SUMMARY(3, true), SUMMARY(2), SUMMARY(1)];
  readonly revisionsByNo = new Map<number, ContentRevisionView>([
    [1, REVISION(1, 'Первая')],
    [2, REVISION(2, 'Вторая')],
    [3, REVISION(3, 'Третья', ['web'])],
  ]);
  readonly fetched: number[] = [];
  readonly restored: { revisionNo: number; version: number }[] = [];
  restoreResult: () => Promise<ContentItemView> = () =>
    Promise.resolve({ id: 'item', version: 9 } as ContentItemView);
  listFails = false;

  revisions(): Promise<readonly ContentRevisionSummary[]> {
    return this.listFails ? Promise.reject(new Error('down')) : Promise.resolve(this.summaries);
  }
  revision(_s: unknown, _i: string, no: number): Promise<ContentRevisionView> {
    this.fetched.push(no);
    return Promise.resolve(this.revisionsByNo.get(no)!);
  }
  republish(_s: unknown, _i: string, no: number, version: number): Promise<ContentItemView> {
    this.restored.push({ revisionNo: no, version });
    return this.restoreResult();
  }
}

async function setup(): Promise<{
  fixture: ReturnType<typeof TestBed.createComponent<ContentHistory>>;
  api: FakeApi;
  root: HTMLElement;
}> {
  const api = new FakeApi();
  TestBed.configureTestingModule({
    providers: [
      { provide: ContentApi, useValue: api },
      { provide: CurrentBrand, useValue: { scope: signal(SCOPE) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentHistory);
  fixture.componentRef.setInput('itemId', 'item');
  fixture.componentRef.setInput('version', 8);
  fixture.detectChanges();
  await settle();
  fixture.detectChanges();
  return { fixture, api, root: fixture.nativeElement as HTMLElement };
}

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));
const rows = (root: HTMLElement): HTMLButtonElement[] =>
  Array.from(root.querySelectorAll<HTMLButtonElement>('[data-testid="content-history-row"]'));

describe('ContentHistory (ADR 0157, Decision 2: immutable revisions)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists every version newest first and marks the one that is live', async () => {
    const { root } = await setup();

    expect(rows(root).map((r) => r.textContent?.includes('Version'))).toEqual([true, true, true]);
    expect(rows(root)[0].querySelector('[data-testid="content-history-live"]')).toBeTruthy();
    expect(rows(root)[1].querySelector('[data-testid="content-history-live"]')).toBeNull();
  });

  it('says so when nothing was ever published', async () => {
    const api = new FakeApi();
    api.summaries = [];
    TestBed.configureTestingModule({
      providers: [
        { provide: ContentApi, useValue: api },
        { provide: CurrentBrand, useValue: { scope: signal(SCOPE) } },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(ContentHistory);
    fixture.componentRef.setInput('itemId', 'item');
    fixture.componentRef.setInput('version', 1);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="content-history-empty"]'),
    ).toBeTruthy();
  });

  it('shows what changed between a version and the one before it', async () => {
    const { fixture, root } = await setup();

    rows(root)[1].click();
    await settle();
    fixture.detectChanges();

    const text = root.querySelector('[data-testid="content-history-detail"]')?.textContent ?? '';
    expect(text).toContain('texts.ru.title');
    expect(text).toContain('Первая');
    expect(text).toContain('Вторая');
  });

  it('also reports a change to the channels around the document', async () => {
    const { fixture, root } = await setup();

    rows(root)[0].click();
    await settle();
    fixture.detectChanges();

    expect(root.querySelector('[data-testid="content-history-detail"]')?.textContent).toContain(
      'channelCodes',
    );
  });

  it('can compare with the live version instead', async () => {
    const { fixture, root, api } = await setup();
    rows(root)[2].click();
    await settle();
    fixture.detectChanges();
    // version 1 has no earlier version: it is shown as all new
    expect(root.querySelector('[data-testid="content-history-detail"]')?.textContent).toContain(
      'Первая',
    );

    (
      root.querySelector('[data-testid="content-history-against-live"]') as HTMLInputElement
    ).click();
    await settle();
    fixture.detectChanges();

    expect(api.fetched).toContain(3);
    expect(root.querySelector('[data-testid="content-history-detail"]')?.textContent).toContain(
      'Третья',
    );
  });

  it('puts a version into the draft at the item’s current version, and reports the item', async () => {
    const { fixture, root, api } = await setup();
    const restored: ContentItemView[] = [];
    fixture.componentInstance.restored.subscribe((i) => restored.push(i));
    rows(root)[1].click();
    await settle();
    fixture.detectChanges();

    (root.querySelector('[data-testid="content-history-restore"]') as HTMLButtonElement).click();
    await settle();

    expect(api.restored).toEqual([{ revisionNo: 2, version: 8 }]);
    expect(restored).toHaveLength(1);
  });

  it('names a version whose images were released instead of failing silently', async () => {
    const { fixture, root, api } = await setup();
    api.restoreResult = () =>
      Promise.reject(
        new ApiError(
          'UNPROCESSABLE_STATE',
          422,
          { status: 422, errors: [{ field: 'revision', code: 'CONTENT_REVISION_MEDIA_RELEASED' }] },
          null,
        ),
      );
    rows(root)[2].click();
    await settle();
    fixture.detectChanges();

    (root.querySelector('[data-testid="content-history-restore"]') as HTMLButtonElement).click();
    await settle();
    fixture.detectChanges();

    expect(root.querySelector('[data-testid="content-history-error"]')?.textContent).toContain(
      'images were released',
    );
  });

  it('offers no restore on an archived item', async () => {
    const { fixture, root } = await setup();
    fixture.componentRef.setInput('readOnly', true);
    rows(root)[1].click();
    await settle();
    fixture.detectChanges();

    expect(root.querySelector('[data-testid="content-history-restore"]')).toBeNull();
  });

  it('says so when the history cannot be read', async () => {
    const api = new FakeApi();
    api.listFails = true;
    TestBed.configureTestingModule({
      providers: [
        { provide: ContentApi, useValue: api },
        { provide: CurrentBrand, useValue: { scope: signal(SCOPE) } },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(ContentHistory);
    fixture.componentRef.setInput('itemId', 'item');
    fixture.componentRef.setInput('version', 1);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="content-history-error"]')
        ?.textContent,
    ).toContain('Could not read the history');
  });
});
