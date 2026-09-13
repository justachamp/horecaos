import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { MatrixGrid } from './matrix-grid';
import { MatrixBulkToggleEvent, MatrixCell } from './matrix-grid-types';

const ROWS = [
  { id: 'storefront', label: 'Website' },
  { id: 'telegram', label: 'Telegram' },
  { id: 'kiosk', label: 'Kiosk' },
];

const COLS = [
  { id: 'CASH', label: 'Cash' },
  { id: 'CLICK', label: 'Click' },
  { id: 'PAYME', label: 'Payme' },
];

const CELLS: readonly MatrixCell[] = [
  { rowId: 'storefront', colId: 'CASH', state: 'OFF' },
  { rowId: 'storefront', colId: 'CLICK', state: 'ON' },
  { rowId: 'storefront', colId: 'PAYME', state: 'ON' },
  { rowId: 'telegram', colId: 'CASH', state: 'OFF' },
  { rowId: 'telegram', colId: 'CLICK', state: 'ON' },
  { rowId: 'telegram', colId: 'PAYME', state: 'UNAVAILABLE' },
  { rowId: 'kiosk', colId: 'CASH', state: 'ON' },
  { rowId: 'kiosk', colId: 'CLICK', state: 'ON' },
  { rowId: 'kiosk', colId: 'PAYME', state: 'ON' },
];

@Component({
  selector: 'q-test-host',
  imports: [MatrixGrid],
  template: `
    <q-matrix-grid
      [rowHeaders]="rows"
      [columnHeaders]="cols"
      [cells]="cells()"
      (toggle)="lastEvent.set($event)"
    />
  `,
})
class TestHost {
  readonly rows = ROWS;
  readonly cols = COLS;
  readonly cells = signal<readonly MatrixCell[]>(CELLS);
  readonly lastEvent = signal<MatrixBulkToggleEvent | null>(null);
}

function cell(fixture: ComponentFixture<TestHost>, rowId: string, colId: string): HTMLElement {
  return fixture.nativeElement.querySelector(
    `[data-row="${rowId}"][data-col="${colId}"]`,
  ) as HTMLElement;
}

function click(el: HTMLElement, options: MouseEventInit = {}): void {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, ...options }));
}

describe('MatrixGrid', () => {
  let fixture: ComponentFixture<TestHost>;
  let host: TestHost;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [TestHost] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(TestHost);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('renders one cell per row-column pair with its state as a data attribute', () => {
    expect(fixture.nativeElement.querySelectorAll('[data-testid="mg-cell"]').length).toBe(9);
    expect(cell(fixture, 'storefront', 'CASH').getAttribute('data-state')).toBe('OFF');
    expect(cell(fixture, 'storefront', 'CLICK').getAttribute('data-state')).toBe('ON');
  });

  it('toggles a single OFF cell to ON on a plain click', () => {
    click(cell(fixture, 'storefront', 'CASH'));
    fixture.detectChanges();

    expect(host.lastEvent()).toEqual({
      changes: [{ rowId: 'storefront', colId: 'CASH', nextState: 'ON' }],
    });
  });

  it('toggles a single ON cell to OFF on a plain click', () => {
    click(cell(fixture, 'storefront', 'CLICK'));
    fixture.detectChanges();

    expect(host.lastEvent()).toEqual({
      changes: [{ rowId: 'storefront', colId: 'CLICK', nextState: 'OFF' }],
    });
  });

  // ------------------------------------------------------------- row/column bulk toggle

  it('turns a whole row ON when any of its eligible cells is OFF', () => {
    (
      fixture.nativeElement.querySelector(
        '[data-testid="mg-row-toggle-storefront"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(host.lastEvent()).toEqual({
      changes: [
        { rowId: 'storefront', colId: 'CASH', nextState: 'ON' },
        { rowId: 'storefront', colId: 'CLICK', nextState: 'ON' },
        { rowId: 'storefront', colId: 'PAYME', nextState: 'ON' },
      ],
    });
  });

  it('turns a whole column OFF once every eligible cell in it is already ON, skipping the unavailable one', () => {
    // CLICK is ON for all three rows already.
    (
      fixture.nativeElement.querySelector(
        '[data-testid="mg-col-toggle-CLICK"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(host.lastEvent()).toEqual({
      changes: [
        { rowId: 'storefront', colId: 'CLICK', nextState: 'OFF' },
        { rowId: 'telegram', colId: 'CLICK', nextState: 'OFF' },
        { rowId: 'kiosk', colId: 'CLICK', nextState: 'OFF' },
      ],
    });
  });

  it('excludes the unavailable cell from a column toggle entirely', () => {
    (
      fixture.nativeElement.querySelector(
        '[data-testid="mg-col-toggle-PAYME"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const rowIds = host.lastEvent()!.changes.map((c) => c.rowId);
    expect(rowIds).not.toContain('telegram');
    expect(rowIds).toEqual(['storefront', 'kiosk']);
  });

  // --------------------------------------------------------------- shift range-select

  it('applies the anchor cell’s target state across a shift-clicked rectangular range', () => {
    click(cell(fixture, 'storefront', 'CASH')); // anchor: OFF -> would become ON
    fixture.detectChanges();
    host.lastEvent.set(null);

    click(cell(fixture, 'kiosk', 'CLICK'), { shiftKey: true }); // range storefront..kiosk x CASH..CLICK
    fixture.detectChanges();

    const changes = host.lastEvent()!.changes;
    const key = (c: { rowId: string; colId: string }) => `${c.rowId}:${c.colId}`;
    expect(changes.every((c) => c.nextState === 'ON')).toBe(true);
    expect(new Set(changes.map(key))).toEqual(
      new Set([
        'storefront:CASH',
        'storefront:CLICK',
        'telegram:CASH',
        'telegram:CLICK',
        'kiosk:CASH',
        'kiosk:CLICK',
      ]),
    );
  });

  it('skips an unavailable cell caught inside a shift-selected range rather than refusing the whole range', () => {
    click(cell(fixture, 'storefront', 'CLICK')); // anchor: ON -> would become OFF
    fixture.detectChanges();

    click(cell(fixture, 'telegram', 'PAYME'), { shiftKey: true }); // range includes telegram/PAYME = UNAVAILABLE
    fixture.detectChanges();

    const changes = host.lastEvent()!.changes;
    expect(changes.some((c) => c.rowId === 'telegram' && c.colId === 'PAYME')).toBe(false);
    expect(changes.every((c) => c.nextState === 'OFF')).toBe(true);
    expect(changes.length).toBe(3); // storefront/CLICK, storefront/PAYME, telegram/CLICK — not telegram/PAYME
  });

  // --------------------------------------------------------- hatched unavailable cell

  it('renders the unavailable cell hatched and refuses a click on it', () => {
    const unavailable = cell(fixture, 'telegram', 'PAYME');
    expect(unavailable.className).toContain('q-matrix-grid__cell--unavailable');
    expect(unavailable.getAttribute('aria-disabled')).toBe('true');

    click(unavailable);
    fixture.detectChanges();

    expect(host.lastEvent()).toBeNull();
  });

  it('refuses a shift-range whose anchor is itself unavailable', () => {
    click(cell(fixture, 'telegram', 'PAYME')); // clicking it does nothing, so no anchor is set
    fixture.detectChanges();

    click(cell(fixture, 'kiosk', 'CASH'), { shiftKey: true });
    fixture.detectChanges();

    // No anchor was ever recorded, so a shift-click behaves like a plain
    // click on kiosk/CASH (already ON) rather than a range apply.
    expect(host.lastEvent()).toEqual({
      changes: [{ rowId: 'kiosk', colId: 'CASH', nextState: 'OFF' }],
    });
  });
});
