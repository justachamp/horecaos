import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { StopExplainerDialog } from './stop-explainer-dialog';
import { AvailabilityExplanation, ExplainedStop, StopsApi } from './stop-scope-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function stop(overrides: Partial<ExplainedStop> = {}): ExplainedStop {
  return {
    id: 's-1',
    variantId: 'v-1',
    scopeType: 'BRAND',
    source: 'OPERATOR',
    reasonCode: 'RECALL',
    endsAt: null,
    status: 'ACTIVE',
    createdAt: '2026-10-01T09:00:00Z',
    version: 1,
    ignored: false,
    ...overrides,
  };
}

function explanation(overrides: Partial<AvailabilityExplanation> = {}): AvailabilityExplanation {
  return {
    sellable: false,
    reasons: ['ON_STOP'],
    stops: [stop()],
    stopsConsulted: true,
    ...overrides,
  };
}

function channel(code: string, displayName: string): ChannelView {
  return { id: `c-${code}`, code, displayName, status: 'ACTIVE' } as ChannelView;
}

async function settle(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

@Component({
  imports: [StopExplainerDialog],
  template: `<q-stop-explainer-dialog
    variantId="v-1"
    productName="Plov"
    (closed)="closes.set(closes() + 1)"
  />`,
})
class Host {
  readonly closes = signal(0);
}

describe('StopExplainerDialog (ADR 0141, “why can’t I sell this?”)', () => {
  let fixture: ComponentFixture<Host>;

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  async function render(
    explain: ReturnType<typeof vi.fn>,
    channels: readonly ChannelView[] = [channel('UZUM', 'Uzum Tezkor'), channel('WEB', 'Website')],
    channelList: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue(channels),
  ): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [Host],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: StopsApi, useValue: { explain } },
        { provide: SalesChannelsApi, useValue: { list: channelList } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const text = (el: Element | null): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const verdict = (host: HTMLElement): string =>
    text(host.querySelector('[data-testid="explainer-verdict"]'));
  const reasons = (host: HTMLElement): string[] =>
    [...host.querySelectorAll('[data-testid="explainer-reason"]')].map((li) => text(li));
  const stopLines = (host: HTMLElement): string[] =>
    [...host.querySelectorAll('[data-testid="explainer-stop"]')].map((li) => text(li));

  it('asks about the dish on no channel first, and says it is on stop and by which stop', async () => {
    const explain = vi.fn().mockResolvedValue(explanation());
    const host = await render(explain);

    expect(explain).toHaveBeenCalledWith(SCOPE, 'v-1', undefined);
    expect(text(host.querySelector('[data-testid="explainer-dish"]'))).toBe('Plov');
    expect(verdict(host)).toBe('On stop');
    expect(
      host.querySelector('[data-testid="explainer-verdict"]')?.getAttribute('data-sellable'),
    ).toBe('false');
    expect(
      reasons(host),
      'ON_STOP is not a line of its own: the stops below are the reason',
    ).toEqual([]);
    expect(stopLines(host)).toEqual(['Whole brand · Kitchen · RECALL · until lifted']);
  });

  it('lists every stop that covers the dish, not the first, each with its own scope, source and end', async () => {
    const host = await render(
      vi.fn().mockResolvedValue(
        explanation({
          stops: [
            stop({ id: 's-1', scopeType: 'BRAND', source: 'OPERATOR', reasonCode: 'RECALL' }),
            stop({
              id: 's-2',
              scopeType: 'LOCATION',
              source: 'POS',
              reasonCode: 'POS_STOP_LIST',
              endsAt: '2026-10-01T17:00:00Z',
            }),
          ],
        }),
      ),
    );

    const lines = stopLines(host);
    expect(lines).toHaveLength(2);
    expect(lines[0]).toContain('Whole brand · Kitchen · RECALL');
    expect(lines[1]).toContain('This branch · POS · POS_STOP_LIST · until ');
  });

  it('says it is available and lists no stop when none covers it', async () => {
    const host = await render(
      vi.fn().mockResolvedValue(explanation({ sellable: true, reasons: [], stops: [] })),
    );

    expect(verdict(host)).toBe('Available');
    expect(stopLines(host)).toEqual([]);
    expect(reasons(host)).toEqual([]);
  });

  it('puts the channel question to the platform: choosing one asks again with that channel’s code', async () => {
    const explain = vi
      .fn()
      .mockResolvedValueOnce(explanation({ sellable: true, reasons: [], stops: [] }))
      .mockResolvedValueOnce(
        explanation({
          reasons: ['ON_STOP'],
          stops: [stop({ scopeType: 'CHANNEL', channelId: 'c-UZUM' })],
        }),
      );
    const host = await render(explain);
    const select = host.querySelector('[data-testid="explainer-channel"]') as HTMLSelectElement;
    expect([...select.options].map((option) => option.value)).toEqual(['', 'UZUM', 'WEB']);

    select.value = 'UZUM';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();

    expect(explain).toHaveBeenLastCalledWith(SCOPE, 'v-1', 'UZUM');
    expect(verdict(host)).toBe('On stop');
    expect(stopLines(host)[0]).toContain('One channel');
  });

  it('shows the answer to the last question asked, never an older one that arrived late', async () => {
    let releaseFirst: (value: AvailabilityExplanation) => void = () => {};
    const first = new Promise<AvailabilityExplanation>((resolve) => {
      releaseFirst = resolve;
    });
    const explain = vi
      .fn()
      .mockReturnValueOnce(first)
      .mockResolvedValueOnce(explanation({ sellable: true, reasons: [], stops: [] }));
    const host = await render(explain);
    expect(host.querySelector('[data-testid="explainer-loading"]')).not.toBeNull();

    const select = host.querySelector('[data-testid="explainer-channel"]') as HTMLSelectElement;
    select.value = 'WEB';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    expect(verdict(host)).toBe('Available');

    releaseFirst(explanation());
    await settle();
    fixture.detectChanges();

    expect(verdict(host)).toBe('Available');
  });

  it('says so when stops are switched off, and marks the stops it lists as ignored', async () => {
    const host = await render(
      vi.fn().mockResolvedValue(
        explanation({
          sellable: true,
          reasons: [],
          stops: [stop({ ignored: true })],
          stopsConsulted: false,
        }),
      ),
    );

    expect(text(host.querySelector('[data-testid="explainer-stops-off"]'))).toBe(
      'Stops are switched off',
    );
    expect(stopLines(host)[0]).toContain('(Stops are switched off)');
  });

  it('words the reasons it has a sentence for and shows any other code as itself rather than hiding it', async () => {
    const host = await render(
      vi.fn().mockResolvedValue(explanation({ reasons: ['SOLD_OUT', 'SOMETHING_NEW'], stops: [] })),
    );

    expect(reasons(host)).toEqual(['Out of stock', 'SOMETHING_NEW']);
  });

  it('says what the channel’s cut-off is when that is why', async () => {
    const host = await render(
      vi.fn().mockResolvedValue(explanation({ reasons: ['CHANNEL_STOPPED'], stops: [] })),
    );

    expect(reasons(host)).toEqual(['Remaining is at or below the channel’s cut-off']);
  });

  it('says there is no access on a 403, and shows the problem on any other failure', async () => {
    const denied = await render(
      vi.fn().mockRejectedValue(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null)),
    );
    expect(denied.querySelector('[data-testid="explainer-denied"]')).not.toBeNull();
    expect(denied.querySelector('[data-testid="explainer-verdict"]')).toBeNull();
    TestBed.resetTestingModule();

    const failed = await render(vi.fn().mockRejectedValue(new Error('network')));
    expect(failed.querySelector('[data-testid="explainer-error"]')).not.toBeNull();
    expect(failed.querySelector('[data-testid="explainer-verdict"]')).toBeNull();
  });

  it('can still be asked when the channel list cannot be read: it just offers no channel', async () => {
    const host = await render(
      vi.fn().mockResolvedValue(explanation()),
      [],
      vi.fn().mockRejectedValue(new Error('x')),
    );

    const select = host.querySelector('[data-testid="explainer-channel"]') as HTMLSelectElement;
    expect([...select.options].map((option) => option.value)).toEqual(['']);
  });

  it('closes from its own button', async () => {
    const host = await render(vi.fn().mockResolvedValue(explanation()));

    (host.querySelector('[data-testid="explainer-close"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.componentInstance.closes()).toBe(1);
  });
});
