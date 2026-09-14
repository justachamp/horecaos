import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { TableToken, TableTokenPosition, TableTokenView } from './table-token';

const TABLE: TableTokenView = {
  tableId: 't-1',
  code: 'T1',
  displayName: 'Table 1',
  seats: 4,
  status: 'ACTIVE',
  layoutX: 100,
  layoutY: 80,
  qrIssued: false,
};

function pointerEvent(
  type: 'pointerdown' | 'pointermove' | 'pointerup',
  x: number,
  y: number,
): PointerEvent {
  return new PointerEvent(type, { bubbles: true, clientX: x, clientY: y, pointerId: 1 });
}

describe('TableToken', () => {
  let fixture: ComponentFixture<TableToken>;

  function render(overrides: Partial<TableTokenView> = {}, disabled = false): HTMLElement {
    fixture = TestBed.createComponent(TableToken);
    fixture.componentRef.setInput('table', { ...TABLE, ...overrides });
    fixture.componentRef.setInput('disabled', disabled);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders at its table’s layoutX/layoutY', () => {
    const host = render();
    const token = host.querySelector<HTMLElement>('[data-testid="table-token-t-1"]')!;
    expect(token.style.transform).toBe('translate(100px, 80px)');
  });

  it('drags to a new position and emits the final absolute coordinates exactly once, on release', () => {
    const host = render();
    const token = host.querySelector<HTMLElement>('[data-testid="table-token-t-1"]')!;

    const emitted: TableTokenPosition[] = [];
    fixture.componentInstance.positionChange.subscribe((position) => emitted.push(position));

    token.dispatchEvent(pointerEvent('pointerdown', 200, 200));
    fixture.detectChanges();
    // Mid-drag: the token follows the pointer immediately, but nothing has
    // been emitted yet — a caller must not see a stream of intermediate values.
    token.dispatchEvent(pointerEvent('pointermove', 230, 215));
    fixture.detectChanges();
    expect(token.style.transform).toBe('translate(130px, 95px)');
    expect(emitted).toHaveLength(0);

    token.dispatchEvent(pointerEvent('pointermove', 260, 240));
    fixture.detectChanges();
    token.dispatchEvent(pointerEvent('pointerup', 260, 240));
    fixture.detectChanges();

    // Moved (260-200, 240-200) = (+60, +40) from the pointerdown origin,
    // added to the table's starting layoutX/layoutY (100, 80).
    expect(emitted).toEqual([{ x: 160, y: 120 }]);
  });

  it('does nothing when disabled', () => {
    const host = render({}, true);
    const token = host.querySelector<HTMLElement>('[data-testid="table-token-t-1"]')!;

    const emitted: TableTokenPosition[] = [];
    fixture.componentInstance.positionChange.subscribe((position) => emitted.push(position));

    token.dispatchEvent(pointerEvent('pointerdown', 200, 200));
    token.dispatchEvent(pointerEvent('pointermove', 260, 240));
    token.dispatchEvent(pointerEvent('pointerup', 260, 240));
    fixture.detectChanges();

    expect(emitted).toHaveLength(0);
    expect(token.style.transform).toBe('translate(100px, 80px)');
  });

  it('shows a QR badge only when a token has been issued', () => {
    expect(
      render({ qrIssued: false }).querySelector('[data-testid="table-token-qr-badge"]'),
    ).toBeNull();
    expect(
      render({ qrIssued: true }).querySelector('[data-testid="table-token-qr-badge"]'),
    ).not.toBeNull();
  });
});
