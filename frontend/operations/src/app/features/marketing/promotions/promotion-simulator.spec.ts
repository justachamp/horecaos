import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { ConditionFixedValue } from '../../../shared/ui/condition-types';
import { NO_LOOKUPS, bodyFromDraft, buildConditionCatalogue, emptyDraft } from './promotion-draft';
import { PromotionSimulator, ReplayChoice } from './promotion-simulator';
import {
  PromotionBody,
  PromotionsApi,
  SimulationRequest,
  SimulationResult,
  TraceEntry,
} from './promotions-api';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };
const VARIANTS: readonly ConditionFixedValue[] = [
  { value: 'v-pizza', label: 'Margherita (Pizza)' },
  { value: 'v-cola', label: 'Cola' },
];

function trace(
  patch: Partial<TraceEntry> & Pick<TraceEntry, 'promotionId' | 'code' | 'verdict'>,
): TraceEntry {
  return { conditionSequence: null, lostTo: [], benefitMinor: 0, ...patch };
}

function result(patch: Partial<SimulationResult> = {}): SimulationResult {
  return {
    currency: 'UZS',
    contextHash: 'abc',
    subtotalMinor: 102_000,
    taxMinor: 0,
    feeMinor: 0,
    discountMinor: 29_600,
    totalMinor: 72_400,
    loyaltyAccrualAllowed: true,
    loyaltyRedemptionAllowed: true,
    serviceInstant: '2026-10-02T07:30:00Z',
    timeZone: 'Asia/Tashkent',
    fulfillmentMode: 'DELIVERY',
    paymentMethodCode: 'CLICK',
    lines: [],
    adjustments: [
      {
        sequence: 1,
        lineId: null,
        type: 'ORDER_DISCOUNT',
        descriptionCode: 'PROMOTION',
        promotionId: 'p-click',
        definitionVersion: 2,
        amountMinor: -4_600,
      },
    ],
    trace: [
      trace({ promotionId: 'p-click', code: 'CLICK5', verdict: 'APPLIED', benefitMinor: 4_600 }),
      trace({
        promotionId: 'p-late',
        code: 'LATE',
        verdict: 'CONDITION_FAILED',
        conditionSequence: 2,
      }),
      trace({ promotionId: 'p-save', code: 'SAVE10', verdict: 'LOST_TO', lostTo: ['p-click'] }),
      trace({
        promotionId: 'p-vip',
        code: 'VIP',
        verdict: 'SUPPRESSED_BY_EXCLUSIVE',
        lostTo: ['p-save'],
      }),
      trace({ promotionId: 'p-old', code: 'OLD', verdict: 'OUTSIDE_WINDOW' }),
      trace({ promotionId: 'p-coupon', code: 'CODE', verdict: 'COUPON_NOT_PRESENTED' }),
    ],
    ...patch,
  };
}

@Component({
  selector: 'q-simulator-host',
  imports: [PromotionSimulator],
  template: `
    <q-promotion-simulator
      [scope]="scope"
      [catalogue]="catalogue"
      [locations]="[{ value: 'loc-1', label: 'Chilonzor' }]"
      [channels]="[{ value: 'WEB', label: 'Website (WEB)' }]"
      [paymentMethods]="[{ value: 'CLICK', label: 'Click' }]"
      [segments]="[{ value: 'aud-1', label: 'Regulars' }]"
      [variants]="variants"
      [menuLocationId]="menuLocationId()"
      [candidate]="candidate()"
      [replay]="replay()"
      (menuLocationChange)="menuLocationId.set($event)"
    />
  `,
})
class Host {
  readonly scope = SCOPE;
  readonly catalogue = buildConditionCatalogue(NO_LOOKUPS);
  readonly variants = VARIANTS;
  readonly menuLocationId = signal<string | null>('loc-1');
  readonly candidate = signal<PromotionBody | null>(null);
  readonly replay = signal<ReplayChoice | null>(null);
}

describe('PromotionSimulator', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<Host>>;
  let el: HTMLElement;
  let host: Host;
  const simulate = vi.fn();

  beforeEach(() => {
    simulate.mockReset();
    simulate.mockResolvedValue(result());
    TestBed.configureTestingModule({
      providers: [{ provide: PromotionsApi, useValue: { simulate } }],
    });
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    fixture.detectChanges();
    el = fixture.nativeElement;
  });

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  function byId<T extends HTMLElement>(id: string): T {
    return el.querySelector(`[data-testid="${id}"]`) as T;
  }

  function choose(id: string, value: string): void {
    const select = byId<HTMLSelectElement>(id);
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function addDish(typed: string, pick: number): void {
    const input = el.querySelector<HTMLInputElement>('[data-testid="q-combobox-input"]')!;
    input.focus();
    input.value = typed;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el.querySelectorAll<HTMLElement>('[data-testid="q-combobox-option"]')[pick].click();
    fixture.detectChanges();
  }

  function sent(): SimulationRequest {
    return simulate.mock.calls[simulate.mock.calls.length - 1][1] as SimulationRequest;
  }

  it('cannot run until there is a channel and a dish in the cart', () => {
    const run = byId<HTMLButtonElement>('sim-run');
    expect(run.disabled).toBe(true);
    choose('sim-channel', 'WEB');
    expect(run.disabled).toBe(true);
    addDish('marg', 0);
    expect(run.disabled).toBe(false);
  });

  it('offers nothing to add until something is typed, and finds a dish by any part of its name', () => {
    const input = el.querySelector<HTMLInputElement>('[data-testid="q-combobox-input"]')!;
    input.focus();
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="q-combobox-option"]')).toHaveLength(0);
    addDish('col', 0);
    expect(el.querySelectorAll('[data-testid="sim-line"]')).toHaveLength(1);
    expect(el.querySelector('[data-testid="sim-line"]')!.textContent).toContain('Cola');
  });

  it('adding the same dish again adds a unit, not a second line', () => {
    addDish('col', 0);
    addDish('col', 0);
    expect(el.querySelectorAll('[data-testid="sim-line"]')).toHaveLength(1);
    expect(el.querySelector<HTMLInputElement>('.sim__qty')!.value).toBe('2');
  });

  it('sends the cart, the context and the customer as facts, and nothing it was not given', async () => {
    choose('sim-channel', 'WEB');
    choose('sim-mode', 'DELIVERY');
    choose('sim-payment', 'CLICK');
    addDish('marg', 0);
    const qty = el.querySelector<HTMLInputElement>('.sim__qty')!;
    qty.value = '2';
    qty.dispatchEvent(new Event('input'));
    const position = byId<HTMLInputElement>('sim-brand-position');
    position.value = '5';
    position.dispatchEvent(new Event('input'));
    el.querySelector<HTMLButtonElement>('.sim__chips .chip')!.click();
    fixture.detectChanges();
    const [lat, lng] = [
      ...el.querySelectorAll<HTMLInputElement>('input[type="number"][step="any"]'),
    ];
    lat.value = '41.31';
    lat.dispatchEvent(new Event('input'));
    lng.value = '69.24';
    lng.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    byId<HTMLButtonElement>('sim-run').click();
    await settle();

    expect(simulate).toHaveBeenCalledTimes(1);
    expect(simulate.mock.calls[0][0]).toEqual(SCOPE);
    expect(sent()).toEqual({
      locationId: 'loc-1',
      channelCode: 'WEB',
      fulfillmentMode: 'DELIVERY',
      paymentMethodCode: 'CLICK',
      serviceInstant: null,
      lines: [
        { lineId: expect.any(String), variantId: 'v-pizza', quantity: 2, modifierOptionIds: null },
      ],
      presentedCouponCode: null,
      destination: { latitude: 41.31, longitude: 69.24 },
      facts: { brandOrderPosition: 5, channelOrderPosition: null, segments: ['aud-1'] },
      candidate: null,
      definitionVersions: null,
    });
  });

  it('sends no destination for a pickup order even if coordinates were typed before the mode changed', async () => {
    choose('sim-channel', 'WEB');
    choose('sim-mode', 'DELIVERY');
    const [lat, lng] = [
      ...el.querySelectorAll<HTMLInputElement>('input[type="number"][step="any"]'),
    ];
    lat.value = '41.31';
    lat.dispatchEvent(new Event('input'));
    lng.value = '69.24';
    lng.dispatchEvent(new Event('input'));
    choose('sim-mode', 'PICKUP');
    addDish('col', 0);
    byId<HTMLButtonElement>('sim-run').click();
    await settle();
    expect(sent().destination).toBeNull();
    expect(sent().fulfillmentMode).toBe('PICKUP');
  });

  it('tries the unsaved candidate only when the operator asks for it, and replays a chosen version', async () => {
    const candidate = bodyFromDraft({ ...emptyDraft(), name: 'Try me', code: 'TRY' });
    host.candidate.set(candidate);
    host.replay.set({ promotionId: 'p-click', code: 'CLICK5', versions: [3, 2, 1] });
    fixture.detectChanges();
    choose('sim-channel', 'WEB');
    addDish('col', 0);

    byId<HTMLButtonElement>('sim-run').click();
    await settle();
    expect(sent().candidate).toBeNull();
    expect(sent().definitionVersions).toBeNull();

    const use = byId<HTMLInputElement>('sim-use-candidate');
    use.checked = true;
    use.dispatchEvent(new Event('change'));
    choose('sim-replay', '2');
    byId<HTMLButtonElement>('sim-run').click();
    await settle();
    expect(sent().candidate).toEqual(candidate);
    expect(sent().definitionVersions).toEqual([{ promotionId: 'p-click', definitionVersion: 2 }]);
  });

  it('shows no "try" section when there is nothing to try', () => {
    expect(byId('sim-use-candidate')).toBeNull();
    expect(byId('sim-replay')).toBeNull();
  });

  it('shows the totals and what built them, naming each promotion by its handle', async () => {
    choose('sim-channel', 'WEB');
    addDish('col', 0);
    byId<HTMLButtonElement>('sim-run').click();
    await settle();
    expect(byId('sim-subtotal').textContent).toContain('102');
    expect(byId('sim-discount').textContent).toContain('29');
    expect(byId('sim-total').textContent).toContain('72');
    const result = byId('sim-result');
    expect(result.textContent).toContain('Order discount · CLICK5');
    expect(result.textContent).not.toContain('abc');
  });

  it('hands the engine’s verdicts to q-rule-simulator, with a reason for every promotion that did not apply', async () => {
    choose('sim-channel', 'WEB');
    addDish('col', 0);
    byId<HTMLButtonElement>('sim-run').click();
    await settle();
    const rows = [...byId('sim-trace').querySelectorAll('.result-row')].map((row) =>
      (row.textContent ?? '').replace(/\s+/g, ' ').trim(),
    );
    expect(rows).toHaveLength(6);
    expect(rows[0]).toContain('CLICK5');
    expect(rows[0]).toContain('Applied: ');
    expect(rows[0]).toContain('4');
    expect(rows[1]).toContain('LATE');
    expect(rows[1]).toContain('Condition 2 did not hold');
    expect(rows[2]).toContain('Lost to CLICK5');
    expect(rows[3]).toContain('Set aside by the exclusive SAVE10');
    expect(rows[4]).toContain('Outside its validity window');
    expect(rows[5]).toContain('Needs a promo code that was not presented');
    // The simulator's own candidate form is hidden: the answer came from the engine.
    expect(byId('sim-trace').querySelector('.candidate')).toBeNull();
    expect(byId('sim-trace').querySelectorAll('.result-row--matched')).toHaveLength(1);
  });

  it('says when an applied promotion suppresses loyalty points', async () => {
    simulate.mockResolvedValue(
      result({ loyaltyAccrualAllowed: false, loyaltyRedemptionAllowed: false }),
    );
    choose('sim-channel', 'WEB');
    addDish('col', 0);
    byId<HTMLButtonElement>('sim-run').click();
    await settle();
    expect(byId('sim-result').textContent).toContain('suppresses loyalty points');
    expect(byId('sim-result').textContent).toContain('blocks spending loyalty points');
  });

  it('shows the refusal instead of a stale result when the server says no', async () => {
    simulate.mockResolvedValueOnce(result());
    choose('sim-channel', 'WEB');
    addDish('col', 0);
    byId<HTMLButtonElement>('sim-run').click();
    await settle();
    expect(byId('sim-result')).not.toBeNull();

    simulate.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
    );
    byId<HTMLButtonElement>('sim-run').click();
    await settle();
    expect(byId('sim-error')).not.toBeNull();
    expect(byId('sim-result')).toBeNull();
  });
});
