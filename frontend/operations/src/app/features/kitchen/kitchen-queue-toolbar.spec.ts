import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { KITCHEN_TABS, KITCHEN_TAB_DEFINITIONS, KitchenTabId } from './kitchen-ticket';
import { KitchenQueueToolbar } from './kitchen-queue-toolbar';

const TABS = KITCHEN_TABS.map((id) => KITCHEN_TAB_DEFINITIONS[id]);

interface Inputs {
  activeTab: KitchenTabId;
  tabCounts: Readonly<Record<KitchenTabId, number | null>>;
  canToggleService: boolean;
  serviceClosed: boolean;
  serviceModeLabel: string;
  togglingService: boolean;
}

function render(overrides: Partial<Inputs> = {}) {
  TestBed.resetTestingModule();
  TestBed.inject(I18n).setLocale('en');
  const inputs: Inputs = {
    activeTab: 'all',
    tabCounts: { all: 7, delivery: 4, pickup: 0, dineIn: null, aggregator: 3 },
    canToggleService: true,
    serviceClosed: false,
    serviceModeLabel: 'Open',
    togglingService: false,
    ...overrides,
  };
  const fixture = TestBed.createComponent(KitchenQueueToolbar);
  fixture.componentRef.setInput('tabs', TABS);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const tabs = () => [...host.querySelectorAll<HTMLButtonElement>('.tab')];
  const toggle = () =>
    host.querySelector<HTMLButtonElement>('[data-testid="kitchen-service-toggle"]');
  return { fixture, host, tabs, toggle };
}

describe('KitchenQueueToolbar', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('draws a tab per fulfilment kind and marks only the active one', () => {
    const { tabs } = render({ activeTab: 'pickup' });

    expect(tabs()).toHaveLength(5);
    expect(tabs().map((tab) => tab.classList.contains('tab--active'))).toEqual([
      false,
      false,
      true,
      false,
      false,
    ]);
  });

  it('shows each tab’s count, and an ellipsis while the board has not been read', () => {
    const { tabs } = render();

    expect(tabs().map((tab) => tab.querySelector('.tab__count')?.textContent?.trim())).toEqual([
      '7',
      '4',
      '0',
      '…',
      '3',
    ]);
  });

  it('reports the tab a click chose', () => {
    const { fixture, tabs } = render();
    const chosen: KitchenTabId[] = [];
    fixture.componentInstance.tabSelected.subscribe((tab) => chosen.push(tab));

    tabs()[3].click();

    expect(chosen).toEqual(['dineIn']);
  });

  it('offers a counter sale and reports the click', () => {
    const { fixture, host } = render();
    let requests = 0;
    fixture.componentInstance.counterSaleRequested.subscribe(() => requests++);

    host.querySelector<HTMLButtonElement>('[data-testid="kitchen-counter-sale"]')?.click();

    expect(requests).toBe(1);
  });

  it('offers the service toggle only once the service state is known', () => {
    expect(render({ canToggleService: false }).toggle()).toBeNull();

    const { toggle } = render();
    expect(toggle()?.textContent?.trim()).toBe('Open');
  });

  it('draws the toggle in the closed colours when the service is closed', () => {
    expect(render().toggle()?.classList.contains('kitchen__service-toggle--closed')).toBe(false);

    const closed = render({ serviceClosed: true, serviceModeLabel: 'Closed' });
    expect(closed.toggle()?.classList.contains('kitchen__service-toggle--closed')).toBe(true);
  });

  it('holds the toggle while it flips, and reports the click', () => {
    expect(render({ togglingService: true }).toggle()?.disabled).toBe(true);

    const { fixture, toggle } = render();
    let requests = 0;
    fixture.componentInstance.serviceToggleRequested.subscribe(() => requests++);
    toggle()?.click();

    expect(requests).toBe(1);
  });
});
