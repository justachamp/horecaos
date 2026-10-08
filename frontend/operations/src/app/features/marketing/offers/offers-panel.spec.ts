import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { LoyaltyApi } from '../loyalty/loyalty-api';
import { MarketingApi } from '../marketing-api';
import { PromotionsApi } from '../promotions/promotions-api';
import { OfferView, OffersApi } from './offers-api';
import { OffersPanel } from './offers-panel';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

function offer(overrides: Partial<OfferView> = {}): OfferView {
  return {
    offerId: 'offer-1',
    lineageId: 'lineage-1',
    versionNumber: 1,
    status: 'DRAFT',
    displayName: 'Free delivery week',
    pricingPromotionId: 'promo-1',
    loyaltyAccrualRuleId: null,
    validFrom: '2026-10-01T00:00:00Z',
    validUntil: null,
    audienceId: null,
    allowedChannels: ['MESSAGING_APP'],
    templateKey: 'OFFER_FREE_DELIVERY',
    templateVersion: null,
    bannerImageReference: null,
    createdBy: 'actor-1',
    publishedBy: null,
    publishedAt: null,
    version: 4,
    createdAt: '2026-09-30T00:00:00Z',
    updatedAt: '2026-09-30T00:00:00Z',
    ...overrides,
  };
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('OffersPanel', () => {
  let fixture: ComponentFixture<OffersPanel>;
  let host: HTMLElement;
  let offers: Record<string, ReturnType<typeof vi.fn>>;

  async function render(
    initial: readonly OfferView[],
    options: { promotionsFail?: boolean; listFail?: ApiError } = {},
  ): Promise<void> {
    offers = {
      list: options.listFail
        ? vi.fn().mockRejectedValue(options.listFail)
        : vi.fn().mockResolvedValue(initial),
      create: vi.fn().mockResolvedValue(offer()),
      rewriteDraft: vi.fn().mockResolvedValue(offer()),
      newVersion: vi.fn().mockResolvedValue(offer()),
      publish: vi.fn().mockResolvedValue(offer({ status: 'PUBLISHED' })),
      retire: vi.fn().mockResolvedValue(offer({ status: 'RETIRED' })),
    };
    await TestBed.configureTestingModule({
      imports: [OffersPanel],
      providers: [
        { provide: OffersApi, useValue: offers },
        {
          provide: MarketingApi,
          useValue: {
            listTemplates: vi.fn().mockResolvedValue([
              {
                id: 'tpl-1',
                brandId: 'b1',
                templateKey: 'OFFER_FREE_DELIVERY',
                notificationClass: 'MARKETING',
                channel: 'MESSAGING_APP',
                consentPurpose: 'MARKETING_PROMOTIONS',
                status: 'ACTIVE',
                activeVersion: 1,
                version: 1,
              },
            ]),
            listAudiences: vi.fn().mockResolvedValue([]),
          },
        },
        {
          provide: PromotionsApi,
          useValue: {
            list: options.promotionsFail
              ? vi.fn().mockRejectedValue(new Error('403'))
              : vi.fn().mockResolvedValue([
                  {
                    promotionId: 'promo-1',
                    name: 'Free delivery',
                    code: 'FREE_DEL',
                    status: 'ACTIVE',
                  },
                  { promotionId: 'promo-old', name: 'Old one', code: 'OLD', status: 'ARCHIVED' },
                ]),
          },
        },
        {
          provide: LoyaltyApi,
          useValue: {
            listAccrualRules: vi.fn().mockResolvedValue([
              { id: 'rule-1', rateBasisPoints: 500, status: 'ACTIVE' },
              { id: 'rule-old', rateBasisPoints: 100, status: 'RETIRED' },
            ]),
          },
        },
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(OffersPanel);
    host = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function click(testId: string, index = 0): void {
    (host.querySelectorAll(`[data-testid="${testId}"]`)[index] as HTMLElement).click();
    fixture.detectChanges();
  }

  function type(testId: string, value: string): void {
    const input = host.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function choose(testId: string, value: string): void {
    const select = host.querySelector(`[data-testid="${testId}"]`) as HTMLSelectElement;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('lists a lineage with its newest version first, the status, and what the offer points at', async () => {
    await render([
      offer({ offerId: 'v1', versionNumber: 1, status: 'SUPERSEDED' }),
      offer({
        offerId: 'v2',
        versionNumber: 2,
        status: 'PUBLISHED',
        displayName: 'Free delivery week',
      }),
    ]);

    expect(host.querySelectorAll('[data-testid="offer-group"]')).toHaveLength(1);
    const rows = [...host.querySelectorAll('[data-testid="offer-row"]')].map((r) => r.textContent);
    expect(rows[0]).toContain('v2');
    expect(rows[0]).toContain('Published');
    expect(rows[1]).toContain('v1');
    expect(rows[1]).toContain('Superseded');
    // Named from pricing, not from anything this screen holds.
    expect(rows[0]).toContain('Free delivery (FREE_DEL)');
  });

  it('says there are no offers yet, and says nothing else is needed to start', async () => {
    await render([]);

    expect(host.querySelector('[data-testid="offers-empty"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="offers-create"]')).not.toBeNull();
  });

  it('shows the denied state, and no create action, when the brand’s offers cannot be read', async () => {
    await render([], {
      listFail: new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
    });

    expect(host.querySelector('[data-testid="offers-denied"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="offers-create"]')).toBeNull();
  });

  describe('drafting an offer', () => {
    it('offers a choice of existing promotions and no field in which to state a benefit', async () => {
      await render([]);
      click('offers-create');

      const select = host.querySelector(
        '[data-testid="offer-form-reference"]',
      ) as HTMLSelectElement;
      // An archived promotion is not something an offer can point at.
      expect([...select.options].map((o) => o.value)).toEqual(['', 'promo-1']);
      expect(host.querySelectorAll('q-drawer input[type="number"]')).toHaveLength(0);
    });

    it('creates an offer that names exactly one promotion, a window, channels and a template', async () => {
      await render([]);
      click('offers-create');
      type('offer-form-name', 'Free delivery week');
      choose('offer-form-reference', 'promo-1');
      choose('offer-form-template', 'OFFER_FREE_DELIVERY');
      type('offer-form-valid-from', '2026-10-10T09:00');
      click('offer-form-channel-SMS');
      (host.querySelector('[data-testid="offer-form-submit"]') as HTMLButtonElement).click();
      await flush();

      expect(offers['create']).toHaveBeenCalledTimes(1);
      const [scope, request] = offers['create'].mock.calls[0];
      expect(scope).toEqual(SCOPE);
      expect(request).toEqual({
        displayName: 'Free delivery week',
        pricingPromotionId: 'promo-1',
        loyaltyAccrualRuleId: null,
        // 09:00 in Tashkent is 04:00 UTC: the form reads the time in the brand's zone, not the browser's.
        validFrom: '2026-10-10T04:00:00.000Z',
        validUntil: null,
        audienceId: null,
        allowedChannels: ['MESSAGING_APP', 'SMS'],
        templateKey: 'OFFER_FREE_DELIVERY',
        templateVersion: null,
        bannerImageReference: null,
      });
    });

    it('points at an accrual rule instead when asked, never at both', async () => {
      await render([]);
      click('offers-create');
      type('offer-form-name', 'Double points');
      choose('offer-form-reference', 'promo-1');
      click('offer-form-kind-rule');
      // Switching kind forgets the other kind's choice: both would be refused.
      expect(
        (host.querySelector('[data-testid="offer-form-reference"]') as HTMLSelectElement).value,
      ).toBe('');
      choose('offer-form-reference', 'rule-1');
      choose('offer-form-template', 'OFFER_FREE_DELIVERY');
      type('offer-form-valid-from', '2026-10-10T09:00');
      (host.querySelector('[data-testid="offer-form-submit"]') as HTMLButtonElement).click();
      await flush();

      const request = offers['create'].mock.calls[0][1];
      expect(request.pricingPromotionId).toBeNull();
      expect(request.loyaltyAccrualRuleId).toBe('rule-1');
    });

    it('says what is missing and keeps save off until nothing is', async () => {
      await render([]);
      click('offers-create');

      expect(
        (host.querySelector('[data-testid="offer-form-submit"]') as HTMLButtonElement).disabled,
      ).toBe(true);
      expect(host.querySelector('[data-testid="offer-form-problem"]')!.textContent).toContain(
        'name',
      );

      type('offer-form-name', 'X');
      choose('offer-form-reference', 'promo-1');
      choose('offer-form-template', 'OFFER_FREE_DELIVERY');
      type('offer-form-valid-from', '2026-10-10T09:00');
      type('offer-form-valid-until', '2026-10-09T09:00');
      expect(host.querySelector('[data-testid="offer-form-problem"]')!.textContent).toContain(
        'end after',
      );

      type('offer-form-valid-until', '2026-10-17T09:00');
      expect(
        (host.querySelector('[data-testid="offer-form-submit"]') as HTMLButtonElement).disabled,
      ).toBe(false);
    });

    it('falls back to typing the id when promotions cannot be read', async () => {
      await render([], { promotionsFail: true });
      click('offers-create');

      expect(host.querySelector('[data-testid="offer-form-reference"]')).toBeNull();
      expect(host.querySelector('[data-testid="offer-form-reference-id"]')).not.toBeNull();
    });

    it('shows the server’s refusal beside the form and keeps the form open', async () => {
      await render([]);
      offers['create'].mockRejectedValue(
        new ApiError(
          ApiErrorCode.VALIDATION_FAILED,
          400,
          { status: 400, detail: 'Promotion FREE_DEL is archived' } as never,
          null,
        ),
      );
      click('offers-create');
      type('offer-form-name', 'X');
      choose('offer-form-reference', 'promo-1');
      choose('offer-form-template', 'OFFER_FREE_DELIVERY');
      type('offer-form-valid-from', '2026-10-10T09:00');
      (host.querySelector('[data-testid="offer-form-submit"]') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      expect(host.querySelector('[data-testid="offer-form-error"]')!.textContent).toContain(
        'archived',
      );
      expect(host.querySelector('q-drawer')).not.toBeNull();
    });
  });

  describe('what each version can do', () => {
    it('rewrites a draft in place, at the version it was read at', async () => {
      await render([offer({ status: 'DRAFT', version: 4 })]);
      click('offer-edit');
      type('offer-form-name', 'Renamed');
      (host.querySelector('[data-testid="offer-form-submit"]') as HTMLButtonElement).click();
      await flush();

      expect(offers['rewriteDraft']).toHaveBeenCalledTimes(1);
      const [scope, offerId, request, expected] = offers['rewriteDraft'].mock.calls[0];
      expect([scope, offerId, request.displayName, expected]).toEqual([
        SCOPE,
        'offer-1',
        'Renamed',
        4,
      ]);
    });

    it('opens a draft with what it already says: its reference, template and name, not the first choice in each list', async () => {
      await render([
        offer({
          status: 'DRAFT',
          pricingPromotionId: 'promo-1',
          templateKey: 'OFFER_FREE_DELIVERY',
          displayName: 'Free delivery week',
        }),
      ]);
      click('offer-edit');

      expect(
        (host.querySelector('[data-testid="offer-form-name"]') as HTMLInputElement).value,
      ).toBe('Free delivery week');
      expect(
        (host.querySelector('[data-testid="offer-form-reference"]') as HTMLSelectElement).value,
      ).toBe('promo-1');
      expect(
        (host.querySelector('[data-testid="offer-form-template"]') as HTMLSelectElement).value,
      ).toBe('OFFER_FREE_DELIVERY');
    });

    it('never edits a published version: it offers a new one, and retiring', async () => {
      await render([offer({ status: 'PUBLISHED' })]);

      expect(host.querySelector('[data-testid="offer-edit"]')).toBeNull();
      expect(host.querySelector('[data-testid="offer-publish"]')).toBeNull();
      expect(host.querySelector('[data-testid="offer-new-version"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="offer-retire"]')).not.toBeNull();
    });

    it('drafts a new version from a published one, in the same lineage', async () => {
      await render([offer({ status: 'PUBLISHED', versionNumber: 2 })]);
      click('offer-new-version');
      type('offer-form-name', 'Free delivery fortnight');
      (host.querySelector('[data-testid="offer-form-submit"]') as HTMLButtonElement).click();
      await flush();

      expect(offers['newVersion']).toHaveBeenCalledTimes(1);
      expect(offers['newVersion'].mock.calls[0][1]).toBe('offer-1');
      expect(offers['rewriteDraft']).not.toHaveBeenCalled();
    });

    it('offers only a new version for a superseded or retired one', async () => {
      await render([
        offer({ offerId: 'a', status: 'SUPERSEDED' }),
        offer({ offerId: 'b', versionNumber: 2, status: 'RETIRED' }),
      ]);

      expect(host.querySelectorAll('[data-testid="offer-new-version"]')).toHaveLength(2);
      expect(host.querySelector('[data-testid="offer-retire"]')).toBeNull();
    });

    it('publishes only after being asked to confirm, at the version it read', async () => {
      await render([offer({ status: 'DRAFT', version: 4 })]);
      click('offer-publish');

      expect(offers['publish']).not.toHaveBeenCalled();
      (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
      await flush();

      expect(offers['publish']).toHaveBeenCalledWith(SCOPE, 'offer-1', 4);
      // Re-read, so the next click carries the version the server now holds.
      expect(offers['list']).toHaveBeenCalledTimes(2);
    });

    it('shows a refused publication and still re-reads the list', async () => {
      await render([offer({ status: 'DRAFT' })]);
      offers['publish'].mockRejectedValue(
        new ApiError(ApiErrorCode.STALE_VERSION, 412, null, null),
      );
      click('offer-publish');
      (host.querySelector('[data-testid="q-confirm-confirm"]') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      expect(host.querySelector('[data-testid="offers-action-error"]')).not.toBeNull();
      expect(offers['list']).toHaveBeenCalledTimes(2);
    });

    it('retires only with a reason, and says what retiring does to scenarios that name it', async () => {
      await render([offer({ status: 'PUBLISHED', version: 7 })]);
      click('offer-retire');

      expect(host.querySelector('q-modal')!.textContent).toContain('stop offering it');
      const confirm = host.querySelector(
        '[data-testid="offer-retire-confirm"]',
      ) as HTMLButtonElement;
      expect(confirm.disabled).toBe(true);

      type('offer-retire-reason', 'Promotion ended early');
      expect(confirm.disabled).toBe(false);
      confirm.click();
      await flush();

      expect(offers['retire']).toHaveBeenCalledWith(SCOPE, 'offer-1', 'Promotion ended early', 7);
    });
  });
});
