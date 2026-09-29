import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { OrderTableChip, OrderTableView, orderTableCodes } from './order-table-chip';

const SEATED: OrderTableView = {
  sessionId: 'session-1',
  tables: [{ tableId: 'table-7', code: 'T7', displayName: 'Table 7' }],
};

const JOINED: OrderTableView = {
  sessionId: 'session-2',
  tables: [
    { tableId: 'table-7', code: 'T7', displayName: 'Table 7' },
    { tableId: 'table-8', code: 'T8', displayName: 'Table 8' },
  ],
};

function render(
  table: OrderTableView | null | undefined,
  locale: 'en' | 'ru' | 'uz-Latn' = 'en',
): HTMLElement {
  const fixture = TestBed.createComponent(OrderTableChip);
  TestBed.inject(I18n).setLocale(locale);
  fixture.componentRef.setInput('table', table);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('orderTableCodes', () => {
  it('is null for no table, so a caller can branch on it', () => {
    expect(orderTableCodes(null)).toBeNull();
    expect(orderTableCodes(undefined)).toBeNull();
    expect(orderTableCodes({ sessionId: 's', tables: [] })).toBeNull();
  });

  it('joins a party pushed together in the order the tables were joined', () => {
    expect(orderTableCodes(SEATED)).toBe('T7');
    expect(orderTableCodes(JOINED)).toBe('T7 + T8');
  });
});

describe('OrderTableChip', () => {
  it('renders the table code in the operator language', () => {
    expect(
      render(SEATED)
        .querySelector('[data-testid="order-table-chip"]')
        ?.textContent?.trim(),
    ).toBe('Table T7');
    expect(
      render(SEATED, 'ru')
        .querySelector('[data-testid="order-table-chip"]')
        ?.textContent?.trim(),
    ).toBe('Стол T7');
    expect(
      render(SEATED, 'uz-Latn')
        .querySelector('[data-testid="order-table-chip"]')
        ?.textContent?.trim(),
    ).toBe('Stol T7');
  });

  it('shows every table of a joined party and names them in the hover title', () => {
    const chip = render(JOINED).querySelector('[data-testid="order-table-chip"]');

    expect(chip?.textContent?.trim()).toBe('Table T7 + T8');
    expect(chip?.getAttribute('title')).toBe('Table 7, Table 8');
  });

  it('renders nothing at all for an order with no table', () => {
    expect(render(null).querySelector('[data-testid="order-table-chip"]')).toBeNull();
    expect(render(undefined).querySelector('[data-testid="order-table-chip"]')).toBeNull();
    expect(
      render({ sessionId: 's', tables: [] }).querySelector('[data-testid="order-table-chip"]'),
    ).toBeNull();
  });
});
