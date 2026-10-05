import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import type { GiftOfferGroup } from '../../services/applied-promotions';
import { LangService } from '../../services/lang.service';
import { TranslateService } from '../../services/translate.service';
import { UiCartService } from '../../services/ui-cart.service';
import { GiftOffersComponent } from './gift-offers.component';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}|${Object.values(params).join('|')}` : key;
  current = (): Record<string, unknown> => ({});
}

class FakeLangService {
  langId = () => 'en';
}

class FakeUiCartService {
  readonly giftOffers = signal<readonly GiftOfferGroup[]>([]);
  readonly updating = signal(false);
  addGift = vi.fn(async (_variantId: string) => true);
}

function group(overrides: Partial<GiftOfferGroup> = {}): GiftOfferGroup {
  return {
    ruleId: 'rule-1',
    toAdd: 1,
    choices: [{ variantId: 'v-cola', name: 'Cola', image: '/cola.png', inCart: false }],
    ...overrides,
  };
}

function render(groups: readonly GiftOfferGroup[], configure?: (cart: FakeUiCartService) => void) {
  const cart = new FakeUiCartService();
  cart.giftOffers.set(groups);
  configure?.(cart);
  TestBed.configureTestingModule({
    providers: [
      { provide: UiCartService, useValue: cart },
      { provide: TranslateService, useValue: new FakeTranslateService() },
      { provide: LangService, useValue: new FakeLangService() },
    ],
  });
  const fixture = TestBed.createComponent(GiftOffersComponent);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const buttons = () => [
    ...host.querySelectorAll<HTMLButtonElement>('[data-testid="gift-offer-add"]'),
  ];
  const labels = () =>
    [...host.querySelectorAll('[data-testid="gift-offer-label"]')].map((label) =>
      label.textContent?.trim(),
    );
  return { fixture, host, cart, buttons, labels };
}

describe('GiftOffersComponent (ADR 0140: an offer, one tap to take it up)', () => {
  it('draws nothing when the platform offers no gift', () => {
    const { host } = render([]);

    expect(host.querySelector('[data-testid="gift-offers"]')).toBeNull();
  });

  it('offers a single gift by name, and adds it with one tap', () => {
    const { host, cart, buttons, labels } = render([group()]);

    expect(host.querySelector('[data-testid="gift-offer-title"]')!.textContent).toContain(
      'cart.giftOffer.title',
    );
    expect(labels()).toEqual(['cart.giftOffer.add|Cola']);

    buttons()[0].click();

    expect(cart.addGift).toHaveBeenCalledWith('v-cola');
  });

  it('lets the customer choose when a rule gives a choice of gifts, one button each', () => {
    const { host, cart, buttons, labels } = render([
      group({
        choices: [
          { variantId: 'v-cola', name: 'Cola', image: null, inCart: false },
          { variantId: 'v-fanta', name: 'Fanta', image: null, inCart: false },
        ],
      }),
    ]);

    expect(host.querySelector('[data-testid="gift-offer-title"]')!.textContent).toContain(
      'cart.giftOffer.choose',
    );
    expect(labels()).toEqual(['cart.giftOffer.add|Cola', 'cart.giftOffer.add|Fanta']);

    buttons()[1].click();
    expect(cart.addGift).toHaveBeenCalledWith('v-fanta');
  });

  it('says how many when more than one is still to add', () => {
    const { labels } = render([group({ toAdd: 2 })]);

    expect(labels()).toEqual(['cart.giftOffer.addMany|Cola|2']);
  });

  it('writes a part of a portion with the language’s decimal mark', () => {
    const { labels } = render([group({ toAdd: 0.5 })]);

    expect(labels()).toEqual(['cart.giftOffer.addMany|Cola|0.5']);
  });

  it('shows one block per rule', () => {
    const { host } = render([
      group(),
      group({
        ruleId: 'rule-2',
        choices: [{ variantId: 'v-tea', name: 'Tea', image: null, inCart: false }],
      }),
    ]);

    expect(host.querySelectorAll('[data-testid="gift-offer"]')).toHaveLength(2);
  });

  it('will not add while the basket is being written', () => {
    const { buttons } = render([group()], (cart) => cart.updating.set(true));

    expect(buttons()[0].disabled).toBe(true);
  });
});
