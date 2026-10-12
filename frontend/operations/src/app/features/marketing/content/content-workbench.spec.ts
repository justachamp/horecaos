import { Component, input, output, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { beforeEach, describe, expect, it } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { ConfirmDialog } from '../../../shared/ui/confirm-dialog';
import { RuleList } from '../../../shared/ui/rule-list';
import {
  ContentApi,
  ContentItemFilter,
  ContentItemPage,
  ContentItemSummary,
  ContentItemView,
  ContentKind,
  ContentPlacement,
} from './content-api';
import { ContentEditor } from './content-editor';
import { ContentWorkbench } from './content-workbench';
import { startInstant } from './content-schedule';

@Component({ selector: 'q-content-editor', template: '<span data-testid="stub-editor"></span>' })
class StubEditor {
  readonly item = input<ContentItemView | null>(null);
  readonly newKind = input<ContentKind | null>(null);
  readonly newPlacement = input<ContentPlacement | null>(null);
  readonly videoEntitled = input(false);
  readonly saved = output<ContentItemView>();
  readonly closed = output<void>();
  readonly reloadRequested = output<void>();
  readonly dirtyChange = output<boolean>();
}

const summary = (id: string, overrides: Partial<ContentItemSummary> = {}): ContentItemSummary => ({
  id,
  kind: 'BANNER',
  placement: 'HOME_CAROUSEL',
  internalName: `name-${id}`,
  displayTitle: `Title ${id}`,
  slug: null,
  state: 'PUBLISHED',
  visibility: 'LIVE',
  sortKey: 1,
  version: 1,
  liveRevisionNo: 1,
  startsAt: null,
  endsAt: null,
  channelCodes: [],
  hasUnpublishedChanges: false,
  updatedAt: '2026-10-01T07:00:00Z',
  problems: [],
  ...overrides,
});

const detail = (id: string): ContentItemView =>
  ({
    id,
    kind: 'BANNER',
    placement: 'HOME_CAROUSEL',
    version: 2,
    displayTitle: `Title ${id}`,
  }) as ContentItemView;

class FakeApi {
  readonly lists: ContentItemFilter[] = [];
  readonly orders: { placement: string; ids: readonly string[]; token: string }[] = [];
  readonly opened: string[] = [];
  page: () => Promise<ContentItemPage> = () =>
    Promise.resolve({
      items: [
        summary('a'),
        summary('b', { visibility: 'SCHEDULED', startsAt: startInstant('2026-11-01') }),
        summary('c'),
      ],
      nextCursor: null,
      orderToken: 'tok-1',
    });
  orderResult: () => Promise<{ ids: readonly string[]; orderToken: string }> = () =>
    Promise.resolve({ ids: [], orderToken: 'tok-2' });

  list(_scope: unknown, filter: ContentItemFilter): Promise<ContentItemPage> {
    this.lists.push(filter);
    return this.page();
  }
  get(_scope: unknown, id: string): Promise<ContentItemView> {
    this.opened.push(id);
    return Promise.resolve(detail(id));
  }
  order(_scope: unknown, placement: string, ids: readonly string[], token: string) {
    this.orders.push({ placement, ids, token });
    return this.orderResult();
  }
}

const settle = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

async function setup(
  inputs: Record<string, unknown> = {},
  api = new FakeApi(),
): Promise<{
  fixture: ReturnType<typeof TestBed.createComponent<ContentWorkbench>>;
  api: FakeApi;
  root: HTMLElement;
  changes: number[];
}> {
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
  TestBed.overrideComponent(ContentWorkbench, {
    remove: { imports: [ContentEditor] },
    add: { imports: [StubEditor] },
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ContentWorkbench);
  const defaults = { kinds: ['BANNER', 'PROMO_CARD'], placement: 'HOME_CAROUSEL' };
  for (const [name, value] of Object.entries({ ...defaults, ...inputs })) {
    fixture.componentRef.setInput(name, value);
  }
  const changes: number[] = [];
  fixture.componentInstance.changed.subscribe(() => changes.push(1));
  fixture.detectChanges();
  await settle();
  await settle();
  fixture.detectChanges();
  return { fixture, api, root: fixture.nativeElement as HTMLElement, changes };
}

const ruleList = (fixture: ComponentFixture<ContentWorkbench>): RuleList =>
  fixture.debugElement.query(By.directive(RuleList)).componentInstance as RuleList;
const stub = (fixture: ComponentFixture<ContentWorkbench>): StubEditor | null =>
  (fixture.debugElement.query(By.directive(StubEditor))?.componentInstance as
    StubEditor | undefined) ?? null;

describe('ContentWorkbench (ADR 0157, Front ends)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  describe('an ordered placement', () => {
    it('lists the placement’s items in order on the flat sortable list, with no enable switch', async () => {
      const { fixture, api, root } = await setup();

      expect(api.lists).toEqual([
        { placement: 'HOME_CAROUSEL', kind: undefined, state: undefined, limit: 200 },
      ]);
      const list = ruleList(fixture);
      expect(list.showToggle()).toBe(false);
      expect(list.items().map((i) => i.id)).toEqual(['a', 'b', 'c']);
      expect(root.querySelectorAll('.rule-list__toggle')).toHaveLength(0);
    });

    it('says in one line what each item is, what it is doing now and when it runs', async () => {
      const { fixture } = await setup();

      expect(ruleList(fixture).items()[0].description).toBe('Banner · Live');
      expect(ruleList(fixture).items()[1].description).toContain('Scheduled');
      expect(ruleList(fixture).items()[1].description).toContain('from 01.11.2026');
    });

    it('counts what is live against the platform’s ceiling for the placement', async () => {
      const { root } = await setup();

      expect(root.querySelector('[data-testid="content-limit"]')?.textContent).toContain(
        'Live: 2 of at most 10',
      );
    });

    it('marks unpublished changes and things to fix on the row', async () => {
      const api = new FakeApi();
      api.page = () =>
        Promise.resolve({
          items: [
            summary('a', {
              hasUnpublishedChanges: true,
              problems: [{ field: 'x', code: 'CONTENT_TARGET_NOT_FOUND', message: '' }],
            }),
          ],
          nextCursor: null,
          orderToken: 't',
        });
      const { fixture } = await setup({}, api);

      const description = ruleList(fixture).items()[0].description!;
      expect(description).toContain('Unpublished changes');
      expect(description).toContain('1 to fix');
    });

    it('shows only the kinds this list is for', async () => {
      const api = new FakeApi();
      api.page = () =>
        Promise.resolve({
          items: [summary('a'), summary('g', { kind: 'PRODUCT_GROUP' })],
          nextCursor: null,
          orderToken: 't',
        });
      const { fixture } = await setup({}, api);

      expect(
        ruleList(fixture)
          .items()
          .map((i) => i.id),
      ).toEqual(['a']);
    });

    it('sends the whole new order with the token the list returned, and keeps the answer’s token', async () => {
      const { fixture, api, changes } = await setup();

      ruleList(fixture).reorder.emit(['c', 'a', 'b']);
      await settle();
      fixture.detectChanges();

      expect(api.orders).toEqual([
        { placement: 'HOME_CAROUSEL', ids: ['c', 'a', 'b'], token: 'tok-1' },
      ]);
      expect(
        ruleList(fixture)
          .items()
          .map((i) => i.id),
      ).toEqual(['c', 'a', 'b']);
      expect(changes).toHaveLength(1);

      ruleList(fixture).reorder.emit(['a', 'c', 'b']);
      await settle();
      expect(api.orders[1].token).toBe('tok-2');
    });

    it('reads the list again and says so when someone else moved the order first', async () => {
      const { fixture, api, root } = await setup();
      api.orderResult = () =>
        Promise.reject(
          new ApiError('STALE_VERSION', 409, { status: 409, expected: 1, actual: 2 }, null),
        );

      ruleList(fixture).reorder.emit(['c', 'a', 'b']);
      await settle();
      await settle();
      fixture.detectChanges();

      expect(root.querySelector('[data-testid="content-list-error"]')?.textContent).toContain(
        'changed the order while you were dragging',
      );
      expect(api.lists).toHaveLength(2);
      expect(
        ruleList(fixture)
          .items()
          .map((i) => i.id),
      ).toEqual(['a', 'b', 'c']);
    });
  });

  describe('opening and creating', () => {
    it('opens the chosen item in the editor', async () => {
      const { fixture, api } = await setup();

      ruleList(fixture).selected.emit('b');
      await settle();
      fixture.detectChanges();

      expect(api.opened).toEqual(['b']);
      expect(stub(fixture)?.item()?.id).toBe('b');
      expect(stub(fixture)?.newKind()).toBeNull();
    });

    it('offers a New button for each kind and opens a blank editor for it in this placement', async () => {
      const { fixture, root } = await setup();
      expect(root.querySelector('[data-testid="content-new-BANNER"]')).toBeTruthy();
      expect(root.querySelector('[data-testid="content-new-PROMO_CARD"]')).toBeTruthy();

      (root.querySelector('[data-testid="content-new-PROMO_CARD"]') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(stub(fixture)?.item()).toBeNull();
      expect(stub(fixture)?.newKind()).toBe('PROMO_CARD');
      expect(stub(fixture)?.newPlacement()).toBe('HOME_CAROUSEL');
    });

    it('hides the New buttons for a list that only orders', async () => {
      const { root } = await setup({ canCreate: false });

      expect(root.querySelector('[data-testid="content-new-BANNER"]')).toBeNull();
    });

    it('moves to the saved item once a new one is created, and refreshes the list and the health read', async () => {
      const { fixture, api, root, changes } = await setup();
      (root.querySelector('[data-testid="content-new-BANNER"]') as HTMLButtonElement).click();
      fixture.detectChanges();

      stub(fixture)!.saved.emit(detail('new-1'));
      await settle();
      await settle();
      fixture.detectChanges();

      expect(stub(fixture)?.item()?.id).toBe('new-1');
      expect(stub(fixture)?.newKind()).toBeNull();
      expect(api.lists).toHaveLength(2);
      expect(changes).toHaveLength(1);
    });

    it('opens an item it is told to focus, as the health panel does', async () => {
      const { fixture, api } = await setup({ focusItemId: 'c' });
      await settle();
      fixture.detectChanges();

      expect(api.opened).toContain('c');
      expect(stub(fixture)?.item()?.id).toBe('c');
    });

    it('closes the editor from its own button', async () => {
      const { fixture } = await setup();
      ruleList(fixture).selected.emit('a');
      await settle();
      fixture.detectChanges();

      stub(fixture)!.closed.emit();
      fixture.detectChanges();

      expect(stub(fixture)).toBeNull();
    });

    it('asks before leaving unsaved edits, and stays when told to keep editing', async () => {
      const { fixture, api } = await setup();
      ruleList(fixture).selected.emit('a');
      await settle();
      fixture.detectChanges();
      stub(fixture)!.dirtyChange.emit(true);

      ruleList(fixture).selected.emit('b');
      await settle();
      fixture.detectChanges();
      expect(fixture.debugElement.query(By.directive(ConfirmDialog))).toBeTruthy();
      expect(api.opened).toEqual(['a']);

      (
        fixture.debugElement.query(By.directive(ConfirmDialog)).componentInstance as ConfirmDialog
      ).cancel.emit();
      fixture.detectChanges();
      expect(fixture.debugElement.query(By.directive(ConfirmDialog))).toBeNull();
      expect(stub(fixture)?.item()?.id).toBe('a');
    });

    it('moves on once the author agrees to lose their edits', async () => {
      const { fixture, api } = await setup();
      ruleList(fixture).selected.emit('a');
      await settle();
      fixture.detectChanges();
      stub(fixture)!.dirtyChange.emit(true);
      ruleList(fixture).selected.emit('b');
      await settle();
      fixture.detectChanges();

      (
        fixture.debugElement.query(By.directive(ConfirmDialog)).componentInstance as ConfirmDialog
      ).confirm.emit();
      await settle();
      fixture.detectChanges();

      expect(api.opened).toEqual(['a', 'b']);
      expect(stub(fixture)?.item()?.id).toBe('b');
    });

    it('does not ask when nothing is unsaved', async () => {
      const { fixture, api } = await setup();
      ruleList(fixture).selected.emit('a');
      await settle();
      fixture.detectChanges();
      ruleList(fixture).selected.emit('b');
      await settle();
      fixture.detectChanges();

      expect(fixture.debugElement.query(By.directive(ConfirmDialog))).toBeNull();
      expect(api.opened).toEqual(['a', 'b']);
    });

    it('asks the editor’s own reload to read the open item again', async () => {
      const { fixture, api } = await setup();
      ruleList(fixture).selected.emit('a');
      await settle();
      fixture.detectChanges();

      stub(fixture)!.reloadRequested.emit();
      await settle();

      expect(api.opened).toEqual(['a', 'a']);
    });
  });

  describe('an editorial list', () => {
    const entries = (n: number, from = 0): ContentItemSummary[] =>
      Array.from({ length: n }, (_, i) =>
        summary(`n${from + i}`, {
          kind: 'NEWS',
          placement: null,
          slug: `post-${from + i}`,
          visibility: null,
          state: 'DRAFT',
        }),
      );

    it('lists entries, not a sortable placement, and pages them by the server’s cursor', async () => {
      const api = new FakeApi();
      api.page = () => Promise.resolve({ items: entries(2), nextCursor: 'c1', orderToken: null });
      const { fixture, root } = await setup({ kinds: ['NEWS'], placement: null }, api);

      expect(fixture.debugElement.query(By.directive(RuleList))).toBeNull();
      expect(root.querySelectorAll('[data-testid="content-entry"]')).toHaveLength(2);
      expect(api.lists[0]).toMatchObject({ kind: 'NEWS', limit: 30 });
      expect(root.querySelector('[data-testid="content-limit"]')).toBeNull();

      api.page = () =>
        Promise.resolve({ items: entries(1, 2), nextCursor: null, orderToken: null });
      (root.querySelector('[data-testid="content-more"]') as HTMLButtonElement).click();
      await settle();
      fixture.detectChanges();

      expect(api.lists[1]).toMatchObject({ cursor: 'c1' });
      expect(root.querySelectorAll('[data-testid="content-entry"]')).toHaveLength(3);
      expect(root.querySelector('[data-testid="content-more"]')).toBeNull();
    });

    it('shows each entry’s address and opens it', async () => {
      const api = new FakeApi();
      api.page = () => Promise.resolve({ items: entries(1), nextCursor: null, orderToken: null });
      const { fixture, root } = await setup({ kinds: ['NEWS'], placement: null }, api);

      expect(root.querySelector('[data-testid="content-entry"]')?.textContent).toContain('post-0');
      (root.querySelector('[data-testid="content-entry"]') as HTMLButtonElement).click();
      await settle();
      fixture.detectChanges();

      expect(api.opened).toEqual(['n0']);
    });
  });

  describe('archive', () => {
    it('lists the archived items on request, as a plain read-only list', async () => {
      const { fixture, api, root } = await setup();

      (root.querySelector('[data-testid="content-toggle-archived"]') as HTMLButtonElement).click();
      await settle();
      await settle();
      fixture.detectChanges();

      expect(api.lists[1]).toMatchObject({ state: 'ARCHIVED' });
      expect(fixture.debugElement.query(By.directive(RuleList))).toBeNull();
      expect(root.querySelector('[data-testid="content-new-BANNER"]')).toBeNull();
    });
  });

  describe('a principal without content.read', () => {
    it('is told which capability is missing instead of seeing a broken list', async () => {
      const api = new FakeApi();
      api.page = () => Promise.reject(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null));
      const { root } = await setup({}, api);

      expect(root.querySelector('[data-testid="q-denied-state"]')).toBeTruthy();
      expect(root.querySelector('[data-testid="q-denied-state-capability"]')?.textContent).toBe(
        'content.read',
      );
    });
  });
});
