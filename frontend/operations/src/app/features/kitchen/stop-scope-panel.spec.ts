import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError, ProblemDetails } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { MenuSetsApi } from '../catalog/menu-sets-api';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { StopsApi } from './stop-scope-api';
import { StopApplied, StopScopePanel } from './stop-scope-panel';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const OK = {
  groupId: 'g1',
  requestedCount: 2,
  appliedCount: 2,
  failedCount: 0,
  items: [],
};

async function settle(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('StopScopePanel (ADR 0141)', () => {
  let fixture: ComponentFixture<StopScopePanel>;
  let stop: ReturnType<typeof vi.fn>;
  let applied: StopApplied[];

  async function render(
    options: {
      brandWide?: boolean;
      variantIds?: readonly string[];
      stop?: ReturnType<typeof vi.fn>;
    } = {},
  ): Promise<HTMLElement> {
    stop = options.stop ?? vi.fn().mockResolvedValue(OK);
    applied = [];
    await TestBed.configureTestingModule({
      imports: [StopScopePanel],
      providers: [
        {
          provide: CurrentLocation,
          useValue: { scope: signal<LocationScope | null>(SCOPE) },
        },
        {
          provide: SessionCapabilities,
          useValue: {
            has: (capability: string) =>
              capability === 'INVENTORY_STOP_MANAGE' && !!options.brandWide,
          },
        },
        { provide: StopsApi, useValue: { stop } },
        {
          provide: SalesChannelsApi,
          useValue: {
            list: vi.fn().mockResolvedValue([
              { id: 'c-web', displayName: 'Storefront', status: 'ACTIVE' },
              { id: 'c-old', displayName: 'Retired', status: 'ARCHIVED' },
            ]),
          },
        },
        {
          provide: MenuSetsApi,
          useValue: {
            list: vi.fn().mockResolvedValue([
              { menuId: 'm-lunch', name: 'Lunch', status: 'ACTIVE', version: 1 },
              { menuId: 'm-old', name: 'Old', status: 'ARCHIVED', version: 1 },
            ]),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(StopScopePanel);
    fixture.componentRef.setInput('variantIds', options.variantIds ?? ['v1', 'v2']);
    fixture.componentInstance.applied.subscribe((event) => applied.push(event));
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function choose(host: HTMLElement, testId: string, value: string): void {
    const select = host.querySelector(`[data-testid="${testId}"]`) as HTMLSelectElement;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  async function apply(host: HTMLElement): Promise<void> {
    (host.querySelector('[data-testid="scope-apply"]') as HTMLButtonElement).click();
    await settle();
    fixture.detectChanges();
  }

  beforeEach(() => {
    window.localStorage.clear();
  });

  it('stops at this branch by default, needs a reason, and sends only the fields the console always sent', async () => {
    const host = await render();
    const applyButton = host.querySelector('[data-testid="scope-apply"]') as HTMLButtonElement;
    expect(applyButton.disabled).toBe(true);

    choose(host, 'scope-reason', 'EQUIPMENT');
    expect(applyButton.disabled).toBe(false);
    await apply(host);

    expect(stop).toHaveBeenCalledTimes(1);
    const [scope, request, atThisBranch] = stop.mock.calls[0];
    expect(scope).toEqual(SCOPE);
    // Exactly the keys, so a field the server would read as a missing primitive (Jackson 3)
    // or an unexpected null is absent rather than present-and-undefined.
    expect(request).toEqual({
      variantIds: ['v1', 'v2'],
      scope: 'LOCATION',
      reasonCode: 'EQUIPMENT',
    });
    expect(Object.keys(request)).toEqual(['variantIds', 'scope', 'reasonCode']);
    expect(atThisBranch).toBe(true);
    expect(applied).toHaveLength(1);
    expect(host.querySelector('[data-testid="scope-message"]')?.textContent).toContain(
      '2 items updated',
    );
  });

  it('offers the brand-wide scopes only to someone who holds inventory.stop.manage', async () => {
    const without = await render({ brandWide: false });
    expect(without.querySelector('[data-testid="scope-brand"]')).toBeNull();
    expect(without.querySelector('[data-testid="scope-menu"]')).toBeNull();
    expect(without.querySelector('[data-testid="scope-location"]')).not.toBeNull();
    expect(without.querySelector('[data-testid="scope-channel"]')).not.toBeNull();
  });

  it('stops the whole brand through the brand route', async () => {
    const host = await render({ brandWide: true });
    (host.querySelector('[data-testid="scope-brand"]') as HTMLInputElement).click();
    fixture.detectChanges();
    choose(host, 'scope-reason', 'RECALL');
    await apply(host);

    const [, request, atThisBranch] = stop.mock.calls[0];
    expect(request).toEqual({ variantIds: ['v1', 'v2'], scope: 'BRAND', reasonCode: 'RECALL' });
    expect(atThisBranch).toBe(false);
  });

  it('a channel stop lists only active channels, defaults to this branch, and goes everywhere when unticked', async () => {
    const host = await render({ brandWide: true });
    (host.querySelector('[data-testid="scope-channel"]') as HTMLInputElement).click();
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();

    const options = [...host.querySelectorAll('[data-testid="scope-channel-select"] option')].map(
      (option) => option.textContent?.trim(),
    );
    expect(options).toContain('Storefront');
    expect(options).not.toContain('Retired');

    choose(host, 'scope-reason', 'OUT_OF_STOCK');
    const applyButton = host.querySelector('[data-testid="scope-apply"]') as HTMLButtonElement;
    expect(applyButton.disabled).toBe(true); // no channel chosen yet

    choose(host, 'scope-channel-select', 'c-web');
    await apply(host);
    expect(stop.mock.calls[0][1]).toEqual({
      variantIds: ['v1', 'v2'],
      scope: 'CHANNEL',
      reasonCode: 'OUT_OF_STOCK',
      channelId: 'c-web',
    });
    expect(stop.mock.calls[0][2]).toBe(true);

    const here = host.querySelector('[data-testid="scope-channel-here"]') as HTMLInputElement;
    here.checked = false;
    here.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await apply(host);
    expect(stop.mock.calls[1][2]).toBe(false);
  });

  it('a menu stop lists the brand’s unarchived menus and names the menu', async () => {
    const host = await render({ brandWide: true });
    (host.querySelector('[data-testid="scope-menu"]') as HTMLInputElement).click();
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();

    const options = [...host.querySelectorAll('[data-testid="scope-menu-select"] option')].map(
      (option) => option.textContent?.trim(),
    );
    expect(options).toContain('Lunch');
    expect(options).not.toContain('Old');

    choose(host, 'scope-reason', 'RECALL');
    choose(host, 'scope-menu-select', 'm-lunch');
    await apply(host);
    expect(stop.mock.calls[0][1]).toEqual({
      variantIds: ['v1', 'v2'],
      scope: 'MENU',
      reasonCode: 'RECALL',
      menuId: 'm-lunch',
    });
    expect(stop.mock.calls[0][2]).toBe(false);
  });

  it('"end of the trading day" and a set time become the fields the server reads; a past time cannot be applied', async () => {
    const host = await render();
    choose(host, 'scope-reason', 'EQUIPMENT');

    choose(host, 'scope-duration', 'END_OF_DAY');
    await apply(host);
    expect(stop.mock.calls[0][1]).toMatchObject({ untilEndOfTradingDay: true });
    expect(stop.mock.calls[0][1]).not.toHaveProperty('endsAt');

    choose(host, 'scope-duration', 'UNTIL');
    const until = host.querySelector('[data-testid="scope-until"]') as HTMLInputElement;
    const applyButton = host.querySelector('[data-testid="scope-apply"]') as HTMLButtonElement;
    expect(applyButton.disabled).toBe(true); // a set time with no time

    until.value = '2000-01-01T10:00';
    until.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(applyButton.disabled).toBe(true); // in the past

    const future = new Date(Date.now() + 2 * 3_600_000);
    const local = new Date(future.getTime() - future.getTimezoneOffset() * 60_000)
      .toISOString()
      .slice(0, 16);
    until.value = local;
    until.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(applyButton.disabled).toBe(false);
    await apply(host);
    expect(typeof stop.mock.calls[1][1].endsAt).toBe('string');
    expect(stop.mock.calls[1][1]).not.toHaveProperty('untilEndOfTradingDay');
  });

  it('says new scoped stops are paused when the platform answers STOPS_FROZEN, and applies nothing', async () => {
    const problem = {
      status: 409,
      code: 'RESOURCE_CONFLICT',
      conflict: 'STOPS_FROZEN',
    } as ProblemDetails;
    const host = await render({
      stop: vi.fn().mockRejectedValue(new ApiError('RESOURCE_CONFLICT', 409, problem, null)),
    });
    choose(host, 'scope-reason', 'EQUIPMENT');
    await apply(host);

    expect(host.querySelector('[data-testid="scope-message"]')?.textContent).toContain('paused');
    expect(applied).toHaveLength(0);
  });

  it('refuses a selection over the 200 cap before the round trip', async () => {
    const many = Array.from({ length: 201 }, (_, index) => `v${index}`);
    const host = await render({ variantIds: many });
    choose(host, 'scope-reason', 'EQUIPMENT');

    expect((host.querySelector('[data-testid="scope-apply"]') as HTMLButtonElement).disabled).toBe(
      true,
    );
    expect(host.textContent).toContain('at most 200');
    expect(stop).not.toHaveBeenCalled();
  });
});
