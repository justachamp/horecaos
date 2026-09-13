import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { DataGrid } from './data-grid';
import {
  DataGridBatchResult,
  DataGridColumn,
  DataGridRowEdit,
  DataGridSaveFn,
} from './data-grid-types';

interface Row {
  readonly id: string;
  readonly name: string;
  readonly priceMinor: string;
}

const ROWS: readonly Row[] = [
  { id: 'r1', name: 'Плов', priceMinor: '25000' },
  { id: 'r2', name: 'Лагман', priceMinor: '22000' },
  { id: 'r3', name: 'Шурпа', priceMinor: '20000' },
];

const COLUMNS: readonly DataGridColumn<Row>[] = [
  { key: 'name', header: 'Name', editable: false, getValue: (row) => row.name },
  {
    key: 'price',
    header: 'Price',
    editable: true,
    numeric: true,
    getValue: (row) => row.priceMinor,
  },
];

@Component({
  selector: 'q-test-host',
  imports: [DataGrid],
  template: `
    <q-data-grid
      [columns]="columns"
      [rows]="rows()"
      [rowId]="rowIdFn"
      [saveFn]="saveFn()"
      (saved)="lastSaved.set($event)"
    />
  `,
})
class TestHost {
  readonly rows = signal<readonly Row[]>(ROWS);
  readonly rowIdFn = (row: Row): string => row.id;
  readonly columns = COLUMNS;
  readonly saveFn = signal<DataGridSaveFn | null>(null);
  readonly lastSaved = signal<DataGridBatchResult | null>(null);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

/**
 * By `data-row`/`data-col` attribute, not by flat DOM position — under
 * `q-data-grid`'s CDK-virtualized branch (past `VIRTUALIZE_THRESHOLD` rows)
 * only the rendered window's rows appear in the DOM at all, so a positional
 * `querySelectorAll(...)[ri * columns + ci]` would silently pick the wrong
 * cell (or none) the moment virtualization is active. The row/col attributes
 * are the same in both branches, so this one helper covers both.
 */
function cellAt(fixture: ComponentFixture<TestHost>, ri: number, ci: number): HTMLElement {
  return fixture.nativeElement.querySelector(
    `[data-testid="dg-cell"][data-row="${ri}"][data-col="${ci}"]`,
  ) as HTMLElement;
}

function manyRows(count: number): readonly Row[] {
  return Array.from({ length: count }, (_, i) => ({
    id: `r${i}`,
    name: `Row ${i}`,
    priceMinor: String(1000 + i),
  }));
}

function mousedown(el: HTMLElement, options: MouseEventInit = {}): void {
  el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, ...options }));
}

function keydown(el: HTMLElement, key: string, options: KeyboardEventInit = {}): void {
  el.dispatchEvent(
    new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...options }),
  );
}

describe('DataGrid', () => {
  let fixture: ComponentFixture<TestHost>;
  let host: TestHost;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [TestHost] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(TestHost);
    host = fixture.componentInstance;
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  function scroller(): HTMLElement {
    return fixture.nativeElement.querySelector('[data-testid="dg-scroll"]') as HTMLElement;
  }

  // -------------------------------------------------------------- inline edit

  it('edits a cell inline and marks it dirty without touching the source row', () => {
    const priceCell = cellAt(fixture, 0, 1);
    priceCell.dispatchEvent(new Event('dblclick', { bubbles: true }));
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector(
      '[data-testid="dg-input"]',
    ) as HTMLInputElement;
    expect(input).toBeTruthy();
    input.value = '30000';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    expect(cellAt(fixture, 0, 1).textContent?.trim()).toBe('30000');
    expect(cellAt(fixture, 0, 1).className).toContain('q-data-grid__cell--dirty');
    expect(host.rows()[0].priceMinor).toBe('25000'); // the source row is untouched until a save lands
    expect(
      fixture.nativeElement.querySelector('[data-testid="dg-dirty-count"]').textContent,
    ).toContain('1');
  });

  it('never opens an editor on a column marked non-editable', () => {
    const nameCell = cellAt(fixture, 0, 0);
    nameCell.dispatchEvent(new Event('dblclick', { bubbles: true }));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="dg-input"]')).toBeFalsy();
  });

  // -------------------------------------------------------------- keyboard nav

  it('moves the active cell with the arrow keys', () => {
    mousedown(cellAt(fixture, 0, 1));
    fixture.detectChanges();
    expect(cellAt(fixture, 0, 1).className).toContain('q-data-grid__cell--active');

    keydown(scroller(), 'ArrowDown');
    fixture.detectChanges();
    expect(cellAt(fixture, 1, 1).className).toContain('q-data-grid__cell--active');
    expect(cellAt(fixture, 0, 1).className).not.toContain('q-data-grid__cell--active');

    keydown(scroller(), 'ArrowLeft');
    fixture.detectChanges();
    expect(cellAt(fixture, 1, 0).className).toContain('q-data-grid__cell--active');
  });

  it('clamps at the grid edge rather than moving off it', () => {
    mousedown(cellAt(fixture, 0, 0));
    fixture.detectChanges();

    keydown(scroller(), 'ArrowUp');
    keydown(scroller(), 'ArrowLeft');
    fixture.detectChanges();

    expect(cellAt(fixture, 0, 0).className).toContain('q-data-grid__cell--active');
  });

  it('starts editing on Enter and commits-and-moves-down on the next Enter', () => {
    mousedown(cellAt(fixture, 0, 1));
    fixture.detectChanges();

    keydown(scroller(), 'Enter');
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector(
      '[data-testid="dg-input"]',
    ) as HTMLInputElement;
    input.value = '31000';
    input.dispatchEvent(new Event('input'));

    keydown(scroller(), 'Enter');
    fixture.detectChanges();

    expect(cellAt(fixture, 0, 1).textContent?.trim()).toBe('31000');
    expect(cellAt(fixture, 1, 1).className).toContain('q-data-grid__cell--active');
  });

  // ------------------------------------------------------------------ fill-down

  it('fills a range down a column from the topmost selected row’s value', () => {
    // Edit row 0's price first, so there is a distinctive value to fill down.
    cellAt(fixture, 0, 1).dispatchEvent(new Event('dblclick', { bubbles: true }));
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector(
      '[data-testid="dg-input"]',
    ) as HTMLInputElement;
    input.value = '99000';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    // Anchor at row 0, extend the selection to row 2 with a shift-click.
    mousedown(cellAt(fixture, 0, 1));
    fixture.detectChanges();
    mousedown(cellAt(fixture, 2, 1), { shiftKey: true });
    fixture.detectChanges();

    keydown(scroller(), 'd', { ctrlKey: true });
    fixture.detectChanges();

    expect(cellAt(fixture, 0, 1).textContent?.trim()).toBe('99000');
    expect(cellAt(fixture, 1, 1).textContent?.trim()).toBe('99000');
    expect(cellAt(fixture, 2, 1).textContent?.trim()).toBe('99000');
    expect(
      fixture.nativeElement.querySelector('[data-testid="dg-dirty-count"]').textContent,
    ).toContain('3');
  });

  it('does nothing on fill-down across two different columns', () => {
    mousedown(cellAt(fixture, 0, 0));
    fixture.detectChanges();
    mousedown(cellAt(fixture, 2, 1), { shiftKey: true });
    fixture.detectChanges();

    keydown(scroller(), 'd', { ctrlKey: true });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="dg-toolbar"]')).toBeFalsy();
  });

  // -------------------------------------------------------------- batched save

  it('reports a per-row failure without losing the other rows’ successes', async () => {
    const saveFn = vi.fn((edits: readonly DataGridRowEdit[]): Observable<DataGridBatchResult> =>
      of({
        items: edits.map((edit) =>
          edit.rowId === 'r1'
            ? { rowId: 'r1', status: 'APPLIED' as const }
            : { rowId: edit.rowId, status: 'FAILED' as const, problemCode: 'PRICE_BELOW_COST' },
        ),
      }),
    );
    host.saveFn.set(saveFn);
    fixture.detectChanges();

    // Dirty both r1 and r2's price cells.
    for (const ri of [0, 1]) {
      cellAt(fixture, ri, 1).dispatchEvent(new Event('dblclick', { bubbles: true }));
      fixture.detectChanges();
      const input = fixture.nativeElement.querySelector(
        '[data-testid="dg-input"]',
      ) as HTMLInputElement;
      input.value = String(50000 + ri);
      input.dispatchEvent(new Event('input'));
      input.dispatchEvent(new Event('blur'));
      fixture.detectChanges();
    }
    expect(
      fixture.nativeElement.querySelector('[data-testid="dg-dirty-count"]').textContent,
    ).toContain('2');

    (fixture.nativeElement.querySelector('[data-testid="dg-save"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(saveFn).toHaveBeenCalledWith([
      { rowId: 'r1', changes: { price: '50000' } },
      { rowId: 'r2', changes: { price: '50001' } },
    ]);

    // r1 applied: its pending edit is gone, so the cell reverts to the
    // (unchanged) source row until the host re-fetches — this component
    // never rewrites `rows` itself.
    expect(cellAt(fixture, 0, 1).className).not.toContain('q-data-grid__cell--dirty');
    expect(cellAt(fixture, 0, 1).textContent?.trim()).toBe('25000');

    // r2 failed: its edit and its problem are still visible, not silently dropped.
    expect(cellAt(fixture, 1, 1).className).toContain('q-data-grid__cell--dirty');
    expect(cellAt(fixture, 1, 1).textContent?.trim()).toBe('50001');
    const row1 = fixture.nativeElement.querySelectorAll('[data-testid="dg-row"]')[1] as HTMLElement;
    expect(row1.getAttribute('data-row-error')).toBe('PRICE_BELOW_COST');

    expect(
      fixture.nativeElement.querySelector('[data-testid="dg-dirty-count"]').textContent,
    ).toContain('1');
    expect(host.lastSaved()?.items.length).toBe(2);
  });

  it('surfaces a whole-request save failure to the operator without losing the pending edit', async () => {
    const failingSaveFn = vi.fn(() => throwError(() => new Error('network')));
    host.saveFn.set(failingSaveFn);
    fixture.detectChanges();

    cellAt(fixture, 0, 1).dispatchEvent(new Event('dblclick', { bubbles: true }));
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector(
      '[data-testid="dg-input"]',
    ) as HTMLInputElement;
    input.value = '50000';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[data-testid="dg-save"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    // A rejected batch is not the same as a successful one that happened to
    // apply nothing — the pending edit must survive it untouched.
    expect(cellAt(fixture, 0, 1).className).toContain('q-data-grid__cell--dirty');
    expect(cellAt(fixture, 0, 1).textContent?.trim()).toBe('50000');
    expect(
      fixture.nativeElement.querySelector('[data-testid="dg-dirty-count"]').textContent,
    ).toContain('1');
    expect(host.lastSaved()).toBeNull();

    // The toolbar leaves its saving state...
    const saveButton = fixture.nativeElement.querySelector(
      '[data-testid="dg-save"]',
    ) as HTMLButtonElement;
    expect(saveButton.disabled).toBe(false);
    expect(saveButton.textContent?.trim()).toBe('Save');

    // ...but "it failed" must not look like "nothing happened": an explicit,
    // dismissible error is shown.
    const alert = fixture.nativeElement.querySelector('[data-testid="dg-save-error"]');
    expect(alert).toBeTruthy();
    expect(alert?.textContent).toContain('failed');

    // Retrying with a working saveFn clears the error and applies the edit.
    const retrySaveFn = vi.fn(() =>
      of<DataGridBatchResult>({ items: [{ rowId: 'r1', status: 'APPLIED' as const }] }),
    );
    host.saveFn.set(retrySaveFn);
    fixture.detectChanges();
    saveButton.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="dg-save-error"]')).toBeFalsy();
    expect(cellAt(fixture, 0, 1).className).not.toContain('q-data-grid__cell--dirty');
  });

  it('dismisses the save-error alert without touching the pending edits', async () => {
    const failingSaveFn = vi.fn(() => throwError(() => new Error('network')));
    host.saveFn.set(failingSaveFn);
    fixture.detectChanges();

    cellAt(fixture, 0, 1).dispatchEvent(new Event('dblclick', { bubbles: true }));
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector(
      '[data-testid="dg-input"]',
    ) as HTMLInputElement;
    input.value = '50000';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('[data-testid="dg-save"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="dg-save-error"]')).toBeTruthy();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="q-inline-alert-dismiss"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="dg-save-error"]')).toBeFalsy();
    expect(cellAt(fixture, 0, 1).className).toContain('q-data-grid__cell--dirty');
  });

  it('discards every pending edit and error at once', async () => {
    cellAt(fixture, 0, 1).dispatchEvent(new Event('dblclick', { bubbles: true }));
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector(
      '[data-testid="dg-input"]',
    ) as HTMLInputElement;
    input.value = '1';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="dg-discard"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="dg-toolbar"]')).toBeFalsy();
    expect(cellAt(fixture, 0, 1).textContent?.trim()).toBe('25000');
  });

  // ------------------------------------------------------------ virtualization

  it('keyboard-navigates and fills down correctly once virtualization kicks in past 150 rows', async () => {
    // A fresh component mounted directly at 200 rows — not the shared
    // `beforeEach` fixture, which renders once at 3 (non-virtualized) rows
    // first, since a real browser's `cdk-virtual-scroll-viewport` measures
    // and scrolls in a way jsdom cannot reproduce reliably. What this test
    // *can* prove without simulating real scrolling is that the CDK branch
    // itself — `*cdkVirtualFor`, its `index`, and the shared `rowTpl` — wires
    // navigation and fill-down the same way the plain-list branch does, over
    // rows deep enough into a 200-row set that a naive positional lookup
    // (rather than this file's `[data-row]`/`[data-col]`-based `cellAt`)
    // would already be picking the wrong element.
    fixture = TestBed.createComponent(TestHost);
    host = fixture.componentInstance;
    host.rows.set(manyRows(200));
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    // Past VIRTUALIZE_THRESHOLD, the CDK viewport renders, not the plain list.
    expect(fixture.nativeElement.querySelector('[data-testid="dg-viewport"]')).toBeTruthy();

    const top = 10;
    const bottom = 12;

    // Arrow-key nav within the virtualized branch.
    mousedown(cellAt(fixture, top, 1));
    fixture.detectChanges();
    keydown(scroller(), 'ArrowDown');
    fixture.detectChanges();
    keydown(scroller(), 'ArrowDown');
    fixture.detectChanges();
    expect(cellAt(fixture, bottom, 1).className).toContain('q-data-grid__cell--active');
    expect(cellAt(fixture, top, 1).className).not.toContain('q-data-grid__cell--active');

    // Fill-down across the same virtualized range.
    cellAt(fixture, top, 1).dispatchEvent(new Event('dblclick', { bubbles: true }));
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector(
      '[data-testid="dg-input"]',
    ) as HTMLInputElement;
    input.value = '77000';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();

    mousedown(cellAt(fixture, top, 1));
    fixture.detectChanges();
    mousedown(cellAt(fixture, bottom, 1), { shiftKey: true });
    fixture.detectChanges();
    keydown(scroller(), 'd', { ctrlKey: true });
    fixture.detectChanges();

    expect(cellAt(fixture, top, 1).textContent?.trim()).toBe('77000');
    expect(cellAt(fixture, top + 1, 1).textContent?.trim()).toBe('77000');
    expect(cellAt(fixture, bottom, 1).textContent?.trim()).toBe('77000');
  });
});
