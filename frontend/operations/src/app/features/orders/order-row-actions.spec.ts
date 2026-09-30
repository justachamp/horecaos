import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderActionResponse } from './order-actions';
import { OrderRowActions, RowActionItem } from './order-row-actions';

const ADVANCE: OrderActionResponse = { action: 'ADVANCE', targetStatus: 'PREPARING' };
const CANCEL: OrderActionResponse = { action: 'CANCEL' };

const INLINE: readonly RowActionItem[] = [{ action: ADVANCE, label: 'Start preparing' }];
const OVERFLOW: readonly RowActionItem[] = [{ action: CANCEL, label: 'Cancel order' }];

function render(overrides: { busy?: boolean; overflowOpen?: boolean } = {}) {
  const fixture = TestBed.createComponent(OrderRowActions);
  fixture.componentRef.setInput('inlineActions', INLINE);
  fixture.componentRef.setInput('overflowActions', OVERFLOW);
  fixture.componentRef.setInput('busy', overrides.busy ?? false);
  fixture.componentRef.setInput('overflowOpen', overrides.overflowOpen ?? false);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const byTestId = (id: string) => host.querySelector<HTMLButtonElement>(`[data-testid="${id}"]`);
  return { fixture, host, byTestId };
}

describe('OrderRowActions', () => {
  beforeEach(() => {
    TestBed.inject(I18n).setLocale('en');
  });

  it('draws each inline action with the words the caller supplied', () => {
    const { byTestId } = render();

    expect(byTestId('order-row-action-ADVANCE')?.textContent?.trim()).toBe('Start preparing');
  });

  it('keeps the overflow menu shut until the caller says it is open', () => {
    const { byTestId } = render();

    expect(byTestId('order-row-overflow-menu')).toBeNull();
    expect(byTestId('order-row-overflow-trigger')?.getAttribute('aria-expanded')).toBe('false');
  });

  it('leads the open menu with Open and Copy order number, then the policy actions', () => {
    const { host, byTestId } = render({ overflowOpen: true });

    const items = [...host.querySelectorAll('.row-actions__overflow-item')].map((item) =>
      item.textContent?.trim(),
    );
    expect(items).toEqual(['Open', 'Copy order number', 'Cancel order']);
    expect(byTestId('order-row-overflow-trigger')?.getAttribute('aria-expanded')).toBe('true');
  });

  it('disables the inline buttons and the trigger while the row is busy', () => {
    const { byTestId } = render({ busy: true });

    expect(byTestId('order-row-action-ADVANCE')?.disabled).toBe(true);
    expect(byTestId('order-row-overflow-trigger')?.disabled).toBe(true);
  });

  it('reports an inline action with the click that made it', () => {
    const { fixture, byTestId } = render();
    const reported: Array<{ action: OrderActionResponse; event: Event }> = [];
    fixture.componentInstance.actionClicked.subscribe((click) => reported.push(click));

    byTestId('order-row-action-ADVANCE')?.click();

    expect(reported).toHaveLength(1);
    expect(reported[0].action).toBe(ADVANCE);
    expect(reported[0].event.type).toBe('click');
  });

  it('reports an overflow action, Open, Copy and the trigger separately', () => {
    const { fixture, byTestId } = render({ overflowOpen: true });
    const seen: string[] = [];
    const instance = fixture.componentInstance;
    instance.actionClicked.subscribe((click) => seen.push(`action:${click.action.action}`));
    instance.overflowToggled.subscribe(() => seen.push('toggle'));
    instance.openClicked.subscribe(() => seen.push('open'));
    instance.copyClicked.subscribe(() => seen.push('copy'));

    byTestId('order-row-overflow-trigger')?.click();
    byTestId('order-row-action-OPEN')?.click();
    byTestId('order-row-action-COPY_NUMBER')?.click();
    byTestId('order-row-action-CANCEL')?.click();

    expect(seen).toEqual(['toggle', 'open', 'copy', 'action:CANCEL']);
  });
});
