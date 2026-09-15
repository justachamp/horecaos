import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { ReportingApi } from '../reports/reporting-api';
import { PaymentMethodsApi } from '../settings/payment-methods/payment-methods-api';
import { MyWorkApi, MyWorkChannelMixResponse } from './my-work-api';
import { MyWorkPage } from './my-work-page';

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function channelMix(overrides: Partial<MyWorkChannelMixResponse> = {}): MyWorkChannelMixResponse {
  return {
    periodFrom: '2026-09-11T19:00:00Z',
    periodTo: '2026-09-12T19:00:00Z',
    channelMix: [],
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function configure(options: {
  scope?: typeof FAKE_SCOPE | null;
  channelMix?: ReturnType<typeof vi.fn>;
  reportingRead?: boolean;
  paymentMix?: ReturnType<typeof vi.fn>;
}): void {
  const hasReportingRead = options.reportingRead ?? true;

  TestBed.configureTestingModule({
    providers: [
      {
        provide: CurrentLocation,
        useValue: {
          scope: () => (options.scope === undefined ? FAKE_SCOPE : options.scope),
          denied: () => options.scope === null,
          ensureLoaded: () => Promise.resolve(),
        },
      },
      {
        provide: SessionCapabilities,
        useValue: {
          has: (capability: string) => capability === 'REPORTING_READ' && hasReportingRead,
          ensureLoaded: () => Promise.resolve(),
        },
      },
      {
        provide: MyWorkApi,
        useValue: { channelMix: options.channelMix ?? vi.fn().mockResolvedValue(channelMix()) },
      },
      {
        provide: ReportingApi,
        useValue: {
          paymentMix:
            options.paymentMix ??
            vi.fn().mockResolvedValue({ overview: [], byLocation: [], provenance: {} }),
        },
      },
      { provide: PaymentMethodsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
}

async function render() {
  const fixture = TestBed.createComponent(MyWorkPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

describe('MyWorkPage', () => {
  it('shows the page-level denied state when the operator holds no location grant', async () => {
    configure({ scope: null });
    const fixture = await render();

    expect(fixture.nativeElement.querySelector('[data-testid="my-work-denied"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="my-work-channel-band"]')).toBeNull();
  });

  // -------------------------------------------------------------- 0.2a

  describe('0.2a — personal statistics by channel', () => {
    it('renders the channel bars once the self-scoped read settles', async () => {
      configure({
        channelMix: vi.fn().mockResolvedValue(
          channelMix({
            channelMix: [
              { key: 'TELEGRAM', orders: 5 },
              { key: 'WEBSITE', orders: 2 },
            ],
          }),
        ),
      });
      const fixture = await render();

      const band = fixture.nativeElement.querySelector('[data-testid="my-work-channel-band"]');
      expect(band).not.toBeNull();
      expect(band.querySelector('[data-testid="q-bar-chart"]')).not.toBeNull();
      expect(band.querySelector('[data-testid="my-work-channel-empty"]')).toBeNull();
    });

    it('shows the empty state, not an empty chart, when the caller took no orders today', async () => {
      configure({ channelMix: vi.fn().mockResolvedValue(channelMix({ channelMix: [] })) });
      const fixture = await render();

      const band = fixture.nativeElement.querySelector('[data-testid="my-work-channel-band"]');
      expect(band.querySelector('[data-testid="my-work-channel-empty"]')).not.toBeNull();
      expect(band.querySelector('[data-testid="q-bar-chart"]')).toBeNull();
    });

    it('never sends an actorId of its own — the read is self-scoped, not parameterised', async () => {
      const channelMixFn = vi.fn().mockResolvedValue(channelMix());
      configure({ channelMix: channelMixFn });
      await render();

      expect(channelMixFn).toHaveBeenCalledTimes(1);
      expect(channelMixFn).toHaveBeenCalledWith(FAKE_SCOPE);
    });
  });

  // -------------------------------------------------------------- 0.2b

  describe('0.2b — revenue by payment method', () => {
    it('renders the payment mix when the operator holds REPORTING_READ', async () => {
      configure({
        reportingRead: true,
        paymentMix: vi.fn().mockResolvedValue({
          overview: [
            {
              locationId: null,
              legalEntityId: null,
              paymentMethodCode: 'CASH',
              settlesFromBalance: false,
              tenderCount: 3,
              amountSom: 150000,
            },
          ],
          byLocation: [],
          provenance: {},
        }),
      });
      const fixture = await render();

      const band = fixture.nativeElement.querySelector('[data-testid="my-work-payment-band"]');
      expect(band.querySelector('[data-testid="q-donut-chart"]')).not.toBeNull();
      expect(band.querySelector('[data-testid="my-work-payment-denied"]')).toBeNull();
    });

    it('renders a named wall, not an empty chart, when the operator lacks REPORTING_READ', async () => {
      const paymentMixFn = vi.fn();
      configure({ reportingRead: false, paymentMix: paymentMixFn });
      const fixture = await render();

      const band = fixture.nativeElement.querySelector('[data-testid="my-work-payment-band"]');
      const denied = band.querySelector('[data-testid="my-work-payment-denied"]');
      expect(denied).not.toBeNull();
      expect(denied.textContent).toContain('REPORTING_READ');
      expect(band.querySelector('[data-testid="q-donut-chart"]')).toBeNull();
      // The whole point of the pre-check: no request is even attempted.
      expect(paymentMixFn).not.toHaveBeenCalled();
    });

    it("renders the same named wall when the server itself refuses with 403 (the client's own guess was wrong)", async () => {
      configure({
        reportingRead: true,
        paymentMix: vi
          .fn()
          .mockRejectedValue(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null)),
      });
      const fixture = await render();

      const band = fixture.nativeElement.querySelector('[data-testid="my-work-payment-band"]');
      expect(band.querySelector('[data-testid="my-work-payment-denied"]')).not.toBeNull();
    });

    it('0.2a stays ready even when 0.2b is denied — the two bands never share one page state', async () => {
      configure({ reportingRead: false });
      const fixture = await render();

      const channelBand = fixture.nativeElement.querySelector(
        '[data-testid="my-work-channel-band"]',
      );
      expect(channelBand.querySelector('[data-testid="my-work-channel-empty"]')).not.toBeNull();
      expect(
        fixture.nativeElement.querySelector('[data-testid="my-work-payment-denied"]'),
      ).not.toBeNull();
    });
  });

  // ------------------------------------------------------- the locked band

  describe('0.2c/0.2d — the locked band', () => {
    it('names the staff-identity ADR for personal data and personalization, never an empty section', async () => {
      configure({});
      const fixture = await render();

      const locked = fixture.nativeElement.querySelector('[data-testid="my-work-locked-band"]');
      expect(locked).not.toBeNull();
      expect(locked.querySelector('[data-testid="q-locked-state"]')).not.toBeNull();
      expect(locked.textContent).toContain('staff-identity ADR');
      // Not the entitlement wall's own default title — this is a data-model
      // gap, not an unpurchased plan, and must not read as one.
      expect(locked.textContent).not.toContain('Not included in this plan');
    });
  });
});
