import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ConnectionState } from '../../core/realtime/realtime-client';
import { TabCounts } from './order-counts';
import { OrderQueueToolbar } from './order-queue-toolbar';
import { ORDER_TABS, ORDER_TAB_DEFINITIONS, OrderTabId } from './order-tabs';

const TABS = ORDER_TABS.map((id) => ORDER_TAB_DEFINITIONS[id]);

function counts(overrides: Partial<TabCounts> = {}): TabCounts {
  return {
    attention: 0,
    new: 0,
    preparing: 0,
    delivering: 0,
    completed: 0,
    cancelled: 0,
    all: 0,
    ...overrides,
  };
}

interface Inputs {
  activeTab: OrderTabId;
  tabCounts: TabCounts;
  connectionState: ConnectionState;
  updatedStamp: string | null;
  refreshing: boolean;
}

function render(overrides: Partial<Inputs> = {}) {
  const inputs: Inputs = {
    activeTab: 'attention',
    tabCounts: counts(),
    connectionState: 'open',
    updatedStamp: null,
    refreshing: false,
    ...overrides,
  };
  const fixture = TestBed.createComponent(OrderQueueToolbar);
  fixture.componentRef.setInput('tabs', TABS);
  fixture.componentRef.setInput('lastUpdatedAt', null);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const tabs = () => [...host.querySelectorAll<HTMLButtonElement>('[role="tab"]')];
  return { fixture, host, tabs };
}

describe('OrderQueueToolbar', () => {
  beforeEach(() => {
    TestBed.inject(I18n).setLocale('en');
  });

  it('draws the seven tabs and marks only the active one', () => {
    const { tabs } = render({ activeTab: 'preparing' });

    expect(tabs()).toHaveLength(7);
    expect(tabs().map((tab) => tab.getAttribute('aria-selected'))).toEqual([
      'false',
      'false',
      'true',
      'false',
      'false',
      'false',
      'false',
    ]);
    expect(tabs().filter((tab) => tab.classList.contains('tab--active'))).toHaveLength(1);
  });

  it('shows a count only where there is something to count, and flags the attention count', () => {
    const { tabs } = render({ tabCounts: counts({ attention: 3, new: 0, preparing: 12 }) });

    const badges = tabs().map((tab) => tab.querySelector('.tab__count'));
    expect(badges[0]?.textContent?.trim()).toBe('3');
    expect(badges[0]?.classList.contains('tab__count--attention')).toBe(true);
    expect(badges[1]).toBeNull();
    expect(badges[2]?.textContent?.trim()).toBe('12');
    expect(badges[2]?.classList.contains('tab__count--attention')).toBe(false);
  });

  it('reports the tab a click chose', () => {
    const { fixture, tabs } = render();
    const chosen: OrderTabId[] = [];
    fixture.componentInstance.tabSelected.subscribe((tab) => chosen.push(tab));

    tabs()[3].click();

    expect(chosen).toEqual(['delivering']);
  });

  it('shows the update stamp only once there is one, pulsing while a refresh runs', () => {
    const empty = render();
    expect(empty.host.querySelector('.order-queue__stamp')).toBeNull();

    const stamped = render({ updatedStamp: '14:05', refreshing: true });
    const stamp = stamped.host.querySelector('.order-queue__stamp');
    expect(stamp?.textContent?.trim()).toBe('14:05');
    expect(stamp?.classList.contains('order-queue__stamp--pulse')).toBe(true);
  });

  it('asks for a refresh when the button is pressed', () => {
    const { fixture, host } = render();
    let requests = 0;
    fixture.componentInstance.refreshRequested.subscribe(() => requests++);

    host.querySelector<HTMLButtonElement>('.order-queue__refresh')?.click();

    expect(requests).toBe(1);
  });

  it('says so when the live connection is lost', () => {
    const open = render({ connectionState: 'open' });
    expect(open.host.querySelector('q-connection-state-banner')?.textContent?.trim()).toBe('');

    const lost = render({ connectionState: 'unavailable' });
    expect(lost.host.querySelector('q-connection-state-banner')?.textContent?.trim()).not.toBe('');
  });
});
