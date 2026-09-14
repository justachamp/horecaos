import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { TableTokenView } from '../table-token/table-token';
import { FloorPlanCanvas, TableMovedEvent } from './floor-plan-canvas';

const TABLES: readonly TableTokenView[] = [
  {
    tableId: 't-1',
    code: 'T1',
    displayName: 'Table 1',
    seats: 4,
    status: 'ACTIVE',
    layoutX: 100,
    layoutY: 80,
    qrIssued: false,
  },
  {
    tableId: 't-2',
    code: 'T2',
    displayName: 'Table 2',
    seats: 2,
    status: 'ACTIVE',
    layoutX: 300,
    layoutY: 200,
    qrIssued: true,
  },
];

function pointerEvent(
  type: 'pointerdown' | 'pointermove' | 'pointerup',
  x: number,
  y: number,
): PointerEvent {
  return new PointerEvent(type, { bubbles: true, clientX: x, clientY: y, pointerId: 1 });
}

describe('FloorPlanCanvas', () => {
  let fixture: ComponentFixture<FloorPlanCanvas>;

  function render(): HTMLElement {
    fixture = TestBed.createComponent(FloorPlanCanvas);
    fixture.componentRef.setInput('tables', TABLES);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders one token per table, positioned at its own layoutX/layoutY', () => {
    const host = render();
    expect(host.querySelectorAll('.token[data-testid^="table-token-"]')).toHaveLength(2);
    const first = host.querySelector<HTMLElement>('[data-testid="table-token-t-1"]')!;
    expect(first.style.transform).toBe('translate(100px, 80px)');
  });

  it('dragging a token to a new spot emits tableMoved with that table’s id and the dropped coordinates', () => {
    const host = render();
    const emitted: TableMovedEvent[] = [];
    fixture.componentInstance.tableMoved.subscribe((event) => emitted.push(event));

    const token = host.querySelector<HTMLElement>('[data-testid="table-token-t-2"]')!;
    token.dispatchEvent(pointerEvent('pointerdown', 100, 100));
    token.dispatchEvent(pointerEvent('pointermove', 150, 160));
    token.dispatchEvent(pointerEvent('pointerup', 150, 160));
    fixture.detectChanges();

    expect(emitted).toEqual([{ tableId: 't-2', layoutX: 350, layoutY: 260 }]);
  });

  it('clamps a drop to stay inside the canvas rather than letting a table off the drawn floor', () => {
    fixture = TestBed.createComponent(FloorPlanCanvas);
    fixture.componentRef.setInput('tables', TABLES);
    fixture.componentRef.setInput('width', 200);
    fixture.componentRef.setInput('height', 150);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    const emitted: TableMovedEvent[] = [];
    fixture.componentInstance.tableMoved.subscribe((event) => emitted.push(event));

    const token = host.querySelector<HTMLElement>('[data-testid="table-token-t-1"]')!;
    token.dispatchEvent(pointerEvent('pointerdown', 0, 0));
    // Dragged far past the canvas's own 200×150 bound.
    token.dispatchEvent(pointerEvent('pointermove', 5000, 5000));
    token.dispatchEvent(pointerEvent('pointerup', 5000, 5000));
    fixture.detectChanges();

    expect(emitted).toEqual([{ tableId: 't-1', layoutX: 144, layoutY: 94 }]);
  });

  it('emits tableSelected on pointerdown, before any drag', () => {
    const host = render();
    const selected: string[] = [];
    fixture.componentInstance.tableSelected.subscribe((id) => selected.push(id));

    host
      .querySelector<HTMLElement>('[data-testid="table-token-t-1"]')!
      .dispatchEvent(pointerEvent('pointerdown', 10, 10));

    expect(selected).toEqual(['t-1']);
  });

  it('disables every token when the canvas itself is disabled (e.g. while a move is saving)', () => {
    fixture = TestBed.createComponent(FloorPlanCanvas);
    fixture.componentRef.setInput('tables', TABLES);
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    const emitted: TableMovedEvent[] = [];
    fixture.componentInstance.tableMoved.subscribe((event) => emitted.push(event));

    const token = host.querySelector<HTMLElement>('[data-testid="table-token-t-1"]')!;
    token.dispatchEvent(pointerEvent('pointerdown', 10, 10));
    token.dispatchEvent(pointerEvent('pointermove', 80, 80));
    token.dispatchEvent(pointerEvent('pointerup', 80, 80));
    fixture.detectChanges();

    expect(emitted).toHaveLength(0);
  });
});
