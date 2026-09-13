import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { FailureInboxPanel } from './failure-inbox-panel';
import { InboxFailureSummary, IntegrationsApi, TenantCategoryCount } from './integrations-api';

/**
 * ADR 0106, gap-map row `10.8c`: a merchant's own error taxonomy and replay,
 * tenant-checked rather than the platform-wide surface's optional filter.
 */
const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const QUIET_CATEGORY: TenantCategoryCount = {
  code: 'MARKETPLACE_ORDER_INBOUND',
  retryableByTimer: true,
  requiresReconciliation: false,
  securityRelevant: false,
  outboxDeadLettered: 0,
  outboxWaiting: 0,
  inboxDeadLettered: 2,
  inboxWaiting: 1,
};

const EMPTY_CATEGORY: TenantCategoryCount = {
  ...QUIET_CATEGORY,
  code: 'PAYMENT_CALLBACK',
  inboxDeadLettered: 0,
  inboxWaiting: 0,
};

const STUCK_MESSAGE: InboxFailureSummary = {
  consumerName: 'marketplace-order-inbound',
  id: 'event-1',
  tenantId: 'tenant-1',
  eventType: 'OrderPushed',
  status: 'DEAD_LETTERED',
  attemptCount: 5,
  errorCode: 'PROVIDER_TIMEOUT',
  lastError: 'timed out',
};

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

class FakeIntegrationsApi {
  readonly failureTaxonomy = vi.fn().mockResolvedValue([]);
  readonly failureInbox = vi.fn().mockResolvedValue([]);
  readonly replayInboxMessage = vi.fn().mockResolvedValue({ changed: true, outcome: 'replayed' });
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('FailureInboxPanel', () => {
  let fixture: ComponentFixture<FailureInboxPanel>;
  let api: FakeIntegrationsApi;

  async function create(
    taxonomy: readonly TenantCategoryCount[],
    inbox: readonly InboxFailureSummary[],
  ): Promise<void> {
    api = new FakeIntegrationsApi();
    api.failureTaxonomy.mockResolvedValue(taxonomy);
    api.failureInbox.mockResolvedValue(inbox);
    await TestBed.configureTestingModule({
      imports: [FailureInboxPanel],
      providers: [
        { provide: IntegrationsApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(FailureInboxPanel);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('reads both the taxonomy and the inbox for the operator’s own tenant', async () => {
    await create([], []);

    expect(api.failureTaxonomy).toHaveBeenCalledWith(SCOPE);
    expect(api.failureInbox).toHaveBeenCalledWith(SCOPE);
  });

  it('shows only categories with at least one dead-lettered or waiting message', async () => {
    await create([QUIET_CATEGORY, EMPTY_CATEGORY], []);

    expect(host().textContent).toContain('MARKETPLACE_ORDER_INBOUND');
    expect(host().textContent).not.toContain('PAYMENT_CALLBACK');
  });

  it('renders a stuck inbox message with its error code and attempt count', async () => {
    await create([], [STUCK_MESSAGE]);

    expect(host().textContent).toContain('PROVIDER_TIMEOUT');
    expect(host().textContent).toContain('5');
  });

  it('replays a stuck message after a reason is given, then reloads', async () => {
    await create([], [STUCK_MESSAGE]);

    vi.spyOn(window, 'prompt').mockReturnValue('Provider confirmed it is back up');
    const replayButton = Array.from(host().querySelectorAll('button')).find((candidate) =>
      candidate.textContent?.includes('Replay'),
    ) as HTMLButtonElement;
    replayButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.replayInboxMessage).toHaveBeenCalledWith(
      SCOPE,
      'marketplace-order-inbound',
      'event-1',
      'Provider confirmed it is back up',
    );
    expect(api.failureInbox).toHaveBeenCalledTimes(2);
  });

  it('does not replay when the reason prompt is dismissed', async () => {
    await create([], [STUCK_MESSAGE]);

    vi.spyOn(window, 'prompt').mockReturnValue(null);
    const replayButton = Array.from(host().querySelectorAll('button')).find((candidate) =>
      candidate.textContent?.includes('Replay'),
    ) as HTMLButtonElement;
    replayButton.click();
    await flushMicrotasks();

    expect(api.replayInboxMessage).not.toHaveBeenCalled();
  });
});
