import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { PropagationBinding, StopsApi, WouldPushItem, WouldPushPage } from './stop-scope-api';
import { StopPropagationBanner } from './stop-propagation-banner';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function binding(overrides: Partial<PropagationBinding>): PropagationBinding {
  return {
    bindingId: 'b-1',
    providerType: 'YANDEX_EDA',
    displayName: 'Yandex Eda',
    mode: 'AUTOMATIC',
    inSync: 0,
    pending: 0,
    uncertain: 0,
    rejectedUnmapped: 0,
    unconfirmed: 0,
    unconfirmedItems: [],
    ...overrides,
  };
}

function decision(overrides: Partial<WouldPushItem>): WouldPushItem {
  return {
    variantId: 'v-1',
    dishName: 'Plov',
    externalItemReference: 'ext-001',
    wouldPushAvailable: true,
    wouldPushAt: '2026-10-20T08:00:00Z',
    confirmedAvailable: null,
    ...overrides,
  };
}

function page(items: readonly WouldPushItem[], nextCursor: string | null = null): WouldPushPage {
  return { items, nextCursor };
}

async function settle(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

@Component({
  imports: [StopPropagationBanner],
  template: `<q-stop-propagation-banner [refreshKey]="key()" />`,
})
class Host {
  readonly key = signal(0);
}

describe('StopPropagationBanner (ADR 0141)', () => {
  let fixture: ComponentFixture<Host>;

  async function render(
    propagation: ReturnType<typeof vi.fn>,
    wouldPush: ReturnType<typeof vi.fn> = vi.fn(),
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
        { provide: StopsApi, useValue: { propagation, wouldPush } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function lines(host: HTMLElement): string[] {
    return [...host.querySelectorAll('[data-testid="stop-propagation-line"]')].map((line) =>
      (line.textContent ?? '').replace(/\s+/g, ' ').trim(),
    );
  }

  it('says a provider with no stop API is not propagated automatically — never a quiet "in sync"', async () => {
    const host = await render(
      vi.fn().mockResolvedValue([binding({ mode: 'MANUAL', displayName: 'Wolt' })]),
    );

    expect(lines(host)).toEqual([
      'Wolt: not propagated automatically — update the partner portal by hand',
    ]);
    expect(host.querySelector('[data-mode="MANUAL"]')?.className).toContain(
      'propagation__line--attention',
    );
  });

  it('says how many items a live marketplace has not confirmed, and since when', async () => {
    const host = await render(
      vi.fn().mockResolvedValue([
        binding({
          pending: 5,
          uncertain: 2,
          unconfirmed: 7,
          oldestUnconfirmedSince: '2026-10-01T09:32:00Z',
        }),
      ]),
    );

    const [line] = lines(host);
    expect(line).toContain('Yandex Eda: 7 items not confirmed since');
    expect(line).toContain('update in the partner portal');
    expect(host.querySelector('[data-mode="AUTOMATIC"]')?.className).toContain(
      'propagation__line--attention',
    );
  });

  it('reads "in sync" only for a live binding with nothing unconfirmed, and says paused when suspended', async () => {
    const host = await render(
      vi
        .fn()
        .mockResolvedValue([
          binding({ displayName: 'Uzum', inSync: 12 }),
          binding({ bindingId: 'b-2', displayName: 'Express24', mode: 'SUSPENDED' }),
        ]),
    );

    expect(lines(host)).toEqual([
      'Uzum: in sync',
      'Express24: updates to the partner are paused — update the partner portal by hand',
    ]);
  });

  it('says a binding in dry run has sent nothing, and how many dishes it would have sent', async () => {
    const host = await render(
      vi.fn().mockResolvedValue([
        binding({
          mode: 'DRY_RUN',
          displayName: 'Uzum Tezkor',
          pending: 4,
          unconfirmed: 4,
          wouldPush: 3,
        }),
      ]),
    );

    const [line] = lines(host);
    expect(line).toContain('Uzum Tezkor: dry run');
    expect(line).toContain('3 items would be sent');
    expect(line).toContain('nothing has been sent');
    expect(line).toContain('Compare with the partner portal');
    expect(host.querySelector('[data-mode="DRY_RUN"]')?.className).toContain(
      'propagation__line--attention',
    );
  });

  describe('the whole list of a dry run (ADR 0180 Decision 3)', () => {
    const dryRun = (wouldPush: number) =>
      vi
        .fn()
        .mockResolvedValue([
          binding({
            mode: 'DRY_RUN',
            displayName: 'Uzum Tezkor',
            unconfirmed: wouldPush,
            wouldPush,
          }),
        ]);

    function items(host: HTMLElement): string[] {
      return [...host.querySelectorAll('[data-testid="stop-propagation-would-push-item"]')].map(
        (item) => (item.textContent ?? '').replace(/\s+/g, ' ').trim(),
      );
    }

    async function click(host: HTMLElement, testId: string): Promise<void> {
      host.querySelector<HTMLElement>(`[data-testid="${testId}"]`)?.click();
      fixture.detectChanges();
      await settle();
      fixture.detectChanges();
    }

    it('offers the list beside the count and shows every dish by name, with what it would be set to', async () => {
      const wouldPush = vi.fn().mockResolvedValue(
        page([
          decision({
            dishName: 'Plov',
            externalItemReference: 'ext-001',
            wouldPushAvailable: false,
          }),
          decision({
            variantId: 'v-2',
            dishName: 'Samsa',
            externalItemReference: 'ext-002',
            confirmedAvailable: true,
          }),
        ]),
      );
      const host = await render(dryRun(2), wouldPush);
      expect(items(host)).toEqual([]);
      expect(host.querySelector('[data-testid="stop-propagation-would-push"]')).toBeNull();
      expect(
        host.querySelector('[data-testid="stop-propagation-would-push-toggle"]')?.textContent,
      ).toContain('Show the 2 dishes');

      await click(host, 'stop-propagation-would-push-toggle');

      expect(wouldPush).toHaveBeenCalledWith(SCOPE, 'b-1', undefined);
      const rows = items(host);
      expect(rows).toHaveLength(2);
      expect(rows[0]).toContain('Plov');
      expect(rows[0]).toContain('would be set to stopped');
      expect(rows[0]).toContain('Partner item ext-001');
      expect(rows[0]).toContain('what the partner holds is not known');
      expect(rows[1]).toContain('Samsa');
      expect(rows[1]).toContain('would be set to available');
      expect(rows[1]).toContain('last known at the partner: available');
    });

    it('pages: a further page is asked for with the cursor and appended, and the button goes with the last page', async () => {
      const wouldPush = vi
        .fn()
        .mockResolvedValueOnce(page([decision({ externalItemReference: 'ext-001' })], 'ext-001'))
        .mockResolvedValueOnce(
          page([
            decision({ variantId: 'v-2', dishName: 'Samsa', externalItemReference: 'ext-002' }),
          ]),
        );
      const host = await render(dryRun(2), wouldPush);
      await click(host, 'stop-propagation-would-push-toggle');
      expect(items(host)).toHaveLength(1);

      await click(host, 'stop-propagation-would-push-more');

      expect(wouldPush).toHaveBeenLastCalledWith(SCOPE, 'b-1', 'ext-001');
      expect(items(host).map((row) => row.split(' ')[0])).toEqual(['Plov', 'Samsa']);
      expect(host.querySelector('[data-testid="stop-propagation-would-push-more"]')).toBeNull();
    });

    it('names a dish that no longer resolves by what it is and still shows the partner reference', async () => {
      const host = await render(
        dryRun(1),
        vi
          .fn()
          .mockResolvedValue(page([decision({ dishName: null, externalItemReference: 'ext-9' })])),
      );

      await click(host, 'stop-propagation-would-push-toggle');

      expect(items(host)[0]).toContain('A dish that is no longer in the menu');
      expect(items(host)[0]).toContain('Partner item ext-9');
    });

    it('says the list could not be loaded, and closes with the same button', async () => {
      const host = await render(dryRun(2), vi.fn().mockRejectedValue(new Error('offline')));

      await click(host, 'stop-propagation-would-push-toggle');

      expect(
        host.querySelector('[data-testid="stop-propagation-would-push-failed"]'),
      ).not.toBeNull();

      await click(host, 'stop-propagation-would-push-toggle');
      expect(host.querySelector('[data-testid="stop-propagation-would-push"]')).toBeNull();
    });

    it('offers no list for a binding that is not rehearsing or has nothing recorded', async () => {
      const live = await render(
        vi.fn().mockResolvedValue([binding({ pending: 3, unconfirmed: 3 })]),
      );
      expect(live.querySelector('[data-testid="stop-propagation-would-push-toggle"]')).toBeNull();
      TestBed.resetTestingModule();

      const empty = await render(dryRun(0));
      expect(empty.querySelector('[data-testid="stop-propagation-would-push-toggle"]')).toBeNull();
    });
  });

  it('keeps an open stale-channel incident in view beside the count, and shows none once it has closed', async () => {
    const open = await render(
      vi.fn().mockResolvedValue([
        binding({
          pending: 2,
          unconfirmed: 2,
          oldestUnconfirmedSince: '2026-10-01T09:32:00Z',
          incidentOpenSince: '2026-10-01T10:02:00Z',
        }),
      ]),
    );
    const incident = open.querySelector('[data-testid="stop-propagation-incident"]');
    expect(incident?.textContent).toContain('Yandex Eda: the partner has not confirmed since');
    expect(incident?.textContent).toContain('closes by itself');
    TestBed.resetTestingModule();

    const closed = await render(
      vi.fn().mockResolvedValue([binding({ inSync: 3, incidentOpenSince: null })]),
    );
    expect(closed.querySelector('[data-testid="stop-propagation-incident"]')).toBeNull();
  });

  it('renders nothing for a branch with no marketplace, or when the read fails', async () => {
    const none = await render(vi.fn().mockResolvedValue([]));
    expect(none.querySelector('[data-testid="stop-propagation"]')).toBeNull();
    TestBed.resetTestingModule();

    const failed = await render(vi.fn().mockRejectedValue(new Error('network')));
    expect(failed.querySelector('[data-testid="stop-propagation"]')).toBeNull();
  });

  it('re-reads when the page bumps its refresh key', async () => {
    const propagation = vi.fn().mockResolvedValue([binding({ inSync: 1 })]);
    await render(propagation);
    expect(propagation).toHaveBeenCalledTimes(1);

    fixture.componentInstance.key.set(1);
    fixture.detectChanges();
    await settle();

    expect(propagation).toHaveBeenCalledTimes(2);
  });
});
