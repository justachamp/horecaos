import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderActionResponse } from './order-actions';
import { OrderDetailActions } from './order-detail-actions';
import { LabelledAction } from './order-row-actions';

const ADVANCE: OrderActionResponse = { action: 'ADVANCE', targetStatus: 'PREPARING' };
const AMEND: OrderActionResponse = { action: 'AMEND' };
const CANCEL: OrderActionResponse = { action: 'CANCEL' };

interface Inputs {
  primaryItem: LabelledAction | null;
  overflowItems: readonly LabelledAction[];
  busy: boolean;
  amendBlockedReason: string | null;
  overflowOpen: boolean;
}

function render(overrides: Partial<Inputs> = {}) {
  const inputs: Inputs = {
    primaryItem: { action: ADVANCE, label: 'Start preparing' },
    overflowItems: [
      { action: AMEND, label: 'Amend order' },
      { action: CANCEL, label: 'Cancel order' },
    ],
    busy: false,
    amendBlockedReason: null,
    overflowOpen: true,
    ...overrides,
  };
  const fixture = TestBed.createComponent(OrderDetailActions);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const byTestId = (id: string) => host.querySelector<HTMLButtonElement>(`[data-testid="${id}"]`);
  return { fixture, host, byTestId };
}

describe('OrderDetailActions', () => {
  beforeEach(() => {
    TestBed.inject(I18n).setLocale('en');
  });

  it('draws the primary action with the words it was given', () => {
    const { byTestId } = render();

    expect(byTestId('order-detail-primary-action')?.textContent?.trim()).toBe('Start preparing');
  });

  it('draws no primary button and no menu when the order offers nothing', () => {
    const { byTestId } = render({ primaryItem: null, overflowItems: [] });

    expect(byTestId('order-detail-primary-action')).toBeNull();
    expect(byTestId('order-detail-overflow-trigger')).toBeNull();
  });

  it('keeps the menu shut until the pane opens it', () => {
    const { byTestId } = render({ overflowOpen: false });

    expect(byTestId('order-detail-overflow-trigger')?.getAttribute('aria-expanded')).toBe('false');
    expect(byTestId('order-detail-overflow-menu')).toBeNull();
  });

  it('lists the overflow actions by their test ids when the menu is open', () => {
    const { byTestId } = render();

    expect(byTestId('order-detail-action-AMEND')?.textContent?.trim()).toBe('Amend order');
    expect(byTestId('order-detail-action-CANCEL')?.textContent?.trim()).toBe('Cancel order');
  });

  it('disables every button while the pane is busy', () => {
    const { byTestId } = render({ busy: true });

    expect(byTestId('order-detail-primary-action')?.disabled).toBe(true);
    expect(byTestId('order-detail-overflow-trigger')?.disabled).toBe(true);
  });

  it('blocks AMEND with its reason, wherever it sits, and only AMEND', () => {
    const inMenu = render({ amendBlockedReason: 'The till has not confirmed the order' });
    expect(inMenu.byTestId('order-detail-action-AMEND')?.disabled).toBe(true);
    expect(inMenu.byTestId('order-detail-action-AMEND')?.title).toBe(
      'The till has not confirmed the order',
    );
    expect(inMenu.byTestId('order-detail-action-CANCEL')?.disabled).toBe(false);

    const primary = render({
      primaryItem: { action: AMEND, label: 'Amend order' },
      amendBlockedReason: 'The till has not confirmed the order',
    });
    expect(primary.byTestId('order-detail-primary-action')?.disabled).toBe(true);
  });

  it('reports the action a click chose and the menu toggle', () => {
    const { fixture, byTestId } = render();
    const seen: string[] = [];
    fixture.componentInstance.actionRequested.subscribe((action) => seen.push(action.action));
    fixture.componentInstance.overflowToggled.subscribe(() => seen.push('toggle'));

    byTestId('order-detail-primary-action')?.click();
    byTestId('order-detail-overflow-trigger')?.click();
    byTestId('order-detail-action-CANCEL')?.click();

    expect(seen).toEqual(['ADVANCE', 'toggle', 'CANCEL']);
  });
});
