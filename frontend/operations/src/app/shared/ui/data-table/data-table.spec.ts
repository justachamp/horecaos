import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { DataTable, QCellDef } from './data-table';
import { BulkAction, RowAction } from './data-table-types';

interface Row {
  readonly id: string;
  readonly name: string;
}

const ROWS: readonly Row[] = [
  { id: 'r1', name: 'Плов' },
  { id: 'r2', name: 'Лагман' },
  { id: 'r3', name: 'Шурпа' },
];

const COLUMNS = [
  { key: 'name', header: 'Name' },
  { key: 'id', header: 'ID' },
];

@Component({
  selector: 'q-test-host',
  imports: [DataTable, QCellDef],
  template: `
    <q-data-table
      [viewId]="viewId()"
      [scopeKey]="scopeKey()"
      [columns]="columns"
      [rows]="rows()"
      [rowId]="rowIdFn"
      [selectable]="true"
      [bulkActions]="bulkActions"
      [rowActions]="rowActions"
      [hasMore]="hasMore()"
      [loading]="loading()"
      [pagingMode]="pagingMode()"
      (bulkAction)="lastBulkAction.set($event)"
      (rowAction)="lastRowAction.set($event)"
      (loadMore)="loadMoreCount.set(loadMoreCount() + 1)"
      (rowClick)="lastRowClick.set($event)"
    >
      <ng-template qCell="name" let-row>{{ row.name }}</ng-template>
      <ng-template qCell="id" let-row>{{ row.id }}</ng-template>
      <span emptyState>Nothing here</span>
    </q-data-table>
  `,
})
class TestHost {
  readonly viewId = signal<string | null>(null);
  readonly scopeKey = signal<string | null>(null);
  readonly rows = signal<readonly Row[]>(ROWS);
  readonly rowIdFn = (row: Row): string => row.id;
  readonly columns = COLUMNS;
  readonly bulkActions: readonly BulkAction[] = [{ id: 'archive', label: 'Archive' }];
  readonly rowActions: readonly RowAction<Row>[] = [{ id: 'open', label: 'Open' }];
  readonly hasMore = signal(false);
  readonly loading = signal(false);
  readonly pagingMode = signal<'paged' | 'infinite'>('paged');
  readonly lastBulkAction = signal<unknown>(null);
  readonly lastRowAction = signal<unknown>(null);
  readonly lastRowClick = signal<Row | null>(null);
  readonly loadMoreCount = signal(0);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DataTable', () => {
  let fixture: ComponentFixture<TestHost>;
  let host: TestHost;

  beforeEach(async () => {
    window.localStorage.clear();
    await TestBed.configureTestingModule({ imports: [TestHost] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(TestHost);
    host = fixture.componentInstance;
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('renders one row per item through the projected qCell templates', () => {
    const rows = fixture.nativeElement.querySelectorAll('[data-testid="dt-row"]');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('Плов');
  });

  // ------------------------------------------------------------ selection

  it('selects a row, shows the bulk-action bar, and reports the selection on a bulk action', async () => {
    const checkbox = fixture.nativeElement.querySelectorAll(
      '[data-testid="dt-row-select"]',
    )[0] as HTMLInputElement;
    checkbox.click();
    fixture.detectChanges();

    const bulkBar = fixture.nativeElement.querySelector('[data-testid="dt-bulk-bar"]');
    expect(bulkBar).toBeTruthy();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="dt-bulk-action-archive"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(host.lastBulkAction()).toEqual({ actionId: 'archive', rowIds: ['r1'] });
  });

  it('clears the selection and hides the bulk bar', () => {
    const checkbox = fixture.nativeElement.querySelectorAll(
      '[data-testid="dt-row-select"]',
    )[0] as HTMLInputElement;
    checkbox.click();
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="dt-bulk-clear"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="dt-bulk-bar"]')).toBeFalsy();
  });

  it('selects every row on the page with the header checkbox', () => {
    (
      fixture.nativeElement.querySelector('[data-testid="dt-select-all"]') as HTMLInputElement
    ).click();
    fixture.detectChanges();

    const checkboxes = [
      ...fixture.nativeElement.querySelectorAll('[data-testid="dt-row-select"]'),
    ] as HTMLInputElement[];
    expect(checkboxes.every((box) => box.checked)).toBe(true);
  });

  // ---------------------------------------------------------- row action menu

  // The row menu is `q-action-menu` (ADR 0101) since wave133-p03's fix-up —
  // its trigger/list/item test ids are its own, not `dt-row-*`.

  it('opens a row’s action menu and reports the chosen action for that row', () => {
    const toggle = fixture.nativeElement.querySelectorAll(
      '[data-testid="q-action-menu-trigger"]',
    )[1] as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="q-action-menu-item-open"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(host.lastRowAction()).toEqual({ actionId: 'open', row: ROWS[1] });
  });

  it('closes an open row menu on a pointer press outside it', () => {
    const toggle = fixture.nativeElement.querySelector(
      '[data-testid="q-action-menu-trigger"]',
    ) as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="q-action-menu-list"]')).toBeTruthy();

    document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="q-action-menu-list"]')).toBeFalsy();
  });

  it('does not treat opening the row menu as a click on the row itself', () => {
    const toggle = fixture.nativeElement.querySelectorAll(
      '[data-testid="q-action-menu-trigger"]',
    )[0] as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="q-action-menu-list"]')).toBeTruthy();
    expect(host.lastRowClick()).toBeNull();
  });

  // -------------------------------------------------------------- column chooser

  it('hides a column’s cells once unchecked in the chooser', () => {
    (
      fixture.nativeElement.querySelector('[data-testid="dt-chooser-toggle"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('[data-testid="dt-cell-id"]').length).toBe(3);

    (
      fixture.nativeElement.querySelector('[data-testid="dt-chooser-item-id"]') as HTMLInputElement
    ).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('[data-testid="dt-cell-id"]').length).toBe(0);
    expect(fixture.nativeElement.querySelectorAll('[data-testid="dt-cell-name"]').length).toBe(3);
  });

  // -------------------------------------------------------- persisted filters

  it('persists filters under viewId and restores them the next time the same view is mounted', async () => {
    host.viewId.set('catalog.products');
    fixture.detectChanges();
    await flushMicrotasks();

    const table = fixture.debugElement.children[0].componentInstance as DataTable<Row>;
    table.filters.set({ status: 'DRAFT' });
    await flushMicrotasks();
    fixture.detectChanges();

    // Tear down the whole fixture — the closest a unit test gets to a page
    // reload — and mount a fresh one against the same (real) localStorage.
    fixture.destroy();
    const reloaded = TestBed.createComponent(TestHost);
    reloaded.componentInstance.viewId.set('catalog.products');
    reloaded.detectChanges();
    await flushMicrotasks();
    reloaded.detectChanges();

    const reloadedTable = reloaded.debugElement.children[0].componentInstance as DataTable<Row>;
    expect(reloadedTable.filters()).toEqual({ status: 'DRAFT' });
  });

  it('does not leak one view’s filters into a different viewId', async () => {
    host.viewId.set('orders.queue');
    fixture.detectChanges();
    await flushMicrotasks();
    const table = fixture.debugElement.children[0].componentInstance as DataTable<Row>;
    table.filters.set({ status: 'LATE' });
    await flushMicrotasks();

    const other = TestBed.createComponent(TestHost);
    other.componentInstance.viewId.set('catalog.products');
    other.detectChanges();
    await flushMicrotasks();
    other.detectChanges();

    const otherTable = other.debugElement.children[0].componentInstance as DataTable<Row>;
    expect(otherTable.filters()).toBeNull();
  });

  // --------------------------------------------- scopeKey (tenant/brand/location isolation)

  it('does not leak one scope’s filters or saved views into another scope on the same screen', async () => {
    host.viewId.set('kitchen.stopList');
    host.scopeKey.set('t1:b1:loc-A');
    fixture.detectChanges();
    await flushMicrotasks();
    const table = fixture.debugElement.children[0].componentInstance as DataTable<Row>;
    table.filters.set({ status: 'ON_STOP' });
    await flushMicrotasks();

    // A shared terminal, or a support session, switched to a different
    // location — same screen (`viewId`), same real `localStorage`.
    const otherLocation = TestBed.createComponent(TestHost);
    otherLocation.componentInstance.viewId.set('kitchen.stopList');
    otherLocation.componentInstance.scopeKey.set('t1:b1:loc-B');
    otherLocation.detectChanges();
    await flushMicrotasks();
    otherLocation.detectChanges();

    const otherTable = otherLocation.debugElement.children[0].componentInstance as DataTable<Row>;
    expect(otherTable.filters()).toBeNull();

    // Back to the first location on a fresh mount: its own filters are still there.
    const backToFirst = TestBed.createComponent(TestHost);
    backToFirst.componentInstance.viewId.set('kitchen.stopList');
    backToFirst.componentInstance.scopeKey.set('t1:b1:loc-A');
    backToFirst.detectChanges();
    await flushMicrotasks();
    backToFirst.detectChanges();

    const firstTable = backToFirst.debugElement.children[0].componentInstance as DataTable<Row>;
    expect(firstTable.filters()).toEqual({ status: 'ON_STOP' });
  });

  it('reloads the new scope’s own filters when scopeKey changes on an already-mounted table', async () => {
    host.viewId.set('kitchen.stopList');
    host.scopeKey.set('t1:b1:loc-A');
    fixture.detectChanges();
    await flushMicrotasks();
    const table = fixture.debugElement.children[0].componentInstance as DataTable<Row>;
    table.filters.set({ status: 'ON_STOP' });
    await flushMicrotasks();

    // The operator switches location without leaving the screen — the table
    // stays mounted, but must stop showing location A's filters immediately.
    host.scopeKey.set('t1:b1:loc-B');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(table.filters()).toBeNull();
  });

  it('does not show one scope’s saved view under a different scope', async () => {
    host.viewId.set('kitchen.stopList');
    host.scopeKey.set('t1:b1:loc-A');
    fixture.detectChanges();
    await flushMicrotasks();

    const table = fixture.debugElement.children[0].componentInstance as DataTable<Row>;
    table.filters.set({ status: 'ON_STOP' });
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-testid="dt-views-toggle"]')!
      .click();
    fixture.detectChanges();
    const nameInput = fixture.nativeElement.querySelector(
      '[data-testid="dt-view-name-input"]',
    ) as HTMLInputElement;
    nameInput.value = 'On stop';
    nameInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector('[data-testid="dt-view-save"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.q-data-table__view-item'),
    ).toHaveLength(1);

    const otherLocation = TestBed.createComponent(TestHost);
    otherLocation.componentInstance.viewId.set('kitchen.stopList');
    otherLocation.componentInstance.scopeKey.set('t1:b1:loc-B');
    otherLocation.detectChanges();
    await flushMicrotasks();
    otherLocation.detectChanges();
    (otherLocation.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-testid="dt-views-toggle"]')!
      .click();
    otherLocation.detectChanges();

    expect(
      (otherLocation.nativeElement as HTMLElement).querySelectorAll('.q-data-table__view-item'),
    ).toHaveLength(0);
  });

  // --------------------------------------------------- cursor paging + infinite scroll

  it('shows a load-more button in paged mode and emits loadMore on click', () => {
    host.hasMore.set(true);
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector(
      '[data-testid="dt-load-more"]',
    ) as HTMLButtonElement;
    expect(button).toBeTruthy();
    button.click();

    expect(host.loadMoreCount()).toBe(1);
  });

  it('emits loadMore from a scroll near the bottom in infinite mode, over the same hasMore source, without a button', () => {
    host.hasMore.set(true);
    host.pagingMode.set('infinite');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="dt-load-more"]')).toBeFalsy();

    const scrollEl = fixture.nativeElement.querySelector(
      '[data-testid="dt-scroll"]',
    ) as HTMLElement;
    Object.defineProperty(scrollEl, 'scrollHeight', { value: 1000, configurable: true });
    Object.defineProperty(scrollEl, 'clientHeight', { value: 400, configurable: true });
    Object.defineProperty(scrollEl, 'scrollTop', { value: 590, configurable: true }); // 1000-590-400=10 <= 48px threshold

    scrollEl.dispatchEvent(new Event('scroll'));

    expect(host.loadMoreCount()).toBe(1);
  });

  it('does not emit loadMore from a scroll far from the bottom', () => {
    host.hasMore.set(true);
    host.pagingMode.set('infinite');
    fixture.detectChanges();

    const scrollEl = fixture.nativeElement.querySelector(
      '[data-testid="dt-scroll"]',
    ) as HTMLElement;
    Object.defineProperty(scrollEl, 'scrollHeight', { value: 1000, configurable: true });
    Object.defineProperty(scrollEl, 'clientHeight', { value: 400, configurable: true });
    Object.defineProperty(scrollEl, 'scrollTop', { value: 0, configurable: true });

    scrollEl.dispatchEvent(new Event('scroll'));

    expect(host.loadMoreCount()).toBe(0);
  });

  it('does not emit loadMore from a scroll once there is no more to load', () => {
    host.hasMore.set(false);
    host.pagingMode.set('infinite');
    fixture.detectChanges();

    const scrollEl = fixture.nativeElement.querySelector(
      '[data-testid="dt-scroll"]',
    ) as HTMLElement;
    Object.defineProperty(scrollEl, 'scrollHeight', { value: 1000, configurable: true });
    Object.defineProperty(scrollEl, 'clientHeight', { value: 400, configurable: true });
    Object.defineProperty(scrollEl, 'scrollTop', { value: 590, configurable: true });

    scrollEl.dispatchEvent(new Event('scroll'));

    expect(host.loadMoreCount()).toBe(0);
  });
});
