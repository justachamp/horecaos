import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { OfferView } from './offers-api';
import { OfferPicker } from './offer-picker';

function offer(overrides: Partial<OfferView> = {}): OfferView {
  return {
    offerId: 'offer-1',
    lineageId: 'lineage-1',
    versionNumber: 2,
    status: 'PUBLISHED',
    displayName: 'Free delivery week',
    pricingPromotionId: 'promo-1',
    loyaltyAccrualRuleId: null,
    validFrom: '2026-10-01T00:00:00Z',
    validUntil: null,
    audienceId: null,
    allowedChannels: ['MESSAGING_APP', 'SMS', 'IN_APP'],
    templateKey: 'OFFER_FREE_DELIVERY',
    templateVersion: null,
    bannerImageReference: null,
    createdBy: 'actor-1',
    publishedBy: 'actor-2',
    publishedAt: '2026-10-01T00:00:00Z',
    version: 3,
    createdAt: '2026-09-30T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}

describe('OfferPicker', () => {
  let fixture: ComponentFixture<OfferPicker>;

  async function render(
    offers: readonly OfferView[],
    inputs: { channel?: string; value?: string | null; required?: boolean } = {},
  ): Promise<void> {
    await TestBed.configureTestingModule({ imports: [OfferPicker] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(OfferPicker);
    fixture.componentRef.setInput('offers', offers);
    fixture.componentRef.setInput('channel', inputs.channel ?? 'SMS');
    fixture.componentRef.setInput('value', inputs.value ?? null);
    fixture.componentRef.setInput('required', inputs.required ?? false);
    fixture.detectChanges();
  }

  function optionValues(): string[] {
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="offer-picker-select"]',
    ) as HTMLSelectElement;
    return [...select.options].map((o) => o.value);
  }

  it('lists a published offer allowed in the channel, with its version', async () => {
    await render([offer()]);

    expect(optionValues()).toEqual(['', 'offer-1']);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Free delivery week · v2');
  });

  it('lists nothing a step may not name: a draft, a retired, a superseded, an over or a wrong-channel offer', async () => {
    await render([
      offer({ offerId: 'draft', status: 'DRAFT' }),
      offer({ offerId: 'retired', status: 'RETIRED' }),
      offer({ offerId: 'old', status: 'SUPERSEDED' }),
      offer({ offerId: 'over', validUntil: '2026-01-01T00:00:00Z' }),
      offer({ offerId: 'elsewhere', allowedChannels: ['PUSH'] }),
      offer({ offerId: 'fine' }),
    ]);

    expect(optionValues()).toEqual(['', 'fine']);
  });

  it('emits the offer chosen, and null for none', async () => {
    await render([offer()]);
    const picked: (string | null)[] = [];
    fixture.componentInstance.valueChange.subscribe((id) => picked.push(id));
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="offer-picker-select"]',
    ) as HTMLSelectElement;

    select.value = 'offer-1';
    select.dispatchEvent(new Event('change'));
    select.value = '';
    select.dispatchEvent(new Event('change'));

    expect(picked).toEqual(['offer-1', null]);
  });

  it('says "none" when an offer is optional and asks for one when it is not', async () => {
    await render([offer()]);
    const text = (fixture.nativeElement as HTMLElement).querySelector('option')!.textContent;
    expect(text).toContain('No offer');

    TestBed.resetTestingModule();
    await render([offer()], { required: true });
    expect((fixture.nativeElement as HTMLElement).querySelector('option')!.textContent).toContain(
      'Choose an offer',
    );
  });

  it('says where offers are written when none is available for the channel', async () => {
    await render([offer({ allowedChannels: ['PUSH'] })]);

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="offer-picker-empty"]'),
    ).not.toBeNull();
  });

  it('keeps the offer a step already names in the list, flagged, when it is no longer in force', async () => {
    await render([offer({ status: 'RETIRED' })], { value: 'offer-1' });
    const host = fixture.nativeElement as HTMLElement;

    expect(optionValues()).toEqual(['', 'offer-1']);
    expect(host.querySelector('[data-testid="offer-picker-stale"]')).not.toBeNull();
  });

  it('shows what the offer points at as a kind, and offers no field to state a benefit', async () => {
    await render(
      [
        offer(),
        offer({
          offerId: 'points',
          pricingPromotionId: null,
          loyaltyAccrualRuleId: 'rule-1',
          displayName: 'Double points',
        }),
      ],
      {
        value: 'points',
      },
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="offer-picker-facts"]')!.textContent).toContain(
      'loyalty accrual rule',
    );
    // Selecting, never authoring: what an offer is worth is pricing's and loyalty's.
    expect(host.querySelectorAll('input')).toHaveLength(0);
  });
});
