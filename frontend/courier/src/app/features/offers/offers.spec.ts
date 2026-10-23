import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { fix } from '../../../testing/fake-device';
import { courierProfile } from '../../../testing/fixtures';
import {
  buttonLabelled,
  configureWork,
  renderPage,
  textOf,
  type WorkHarness,
} from '../../../testing/work-harness';
import { delivery, offer } from '../../../testing/work-fixtures';
import { ApiError } from '../../core/api/problem-details';
import type { CourierOffer } from '../../core/courier/courier-work';
import { OffersStore } from '../../core/offers/offers.store';
import { OffersPage } from './offers';

describe('OffersPage', () => {
  let work: WorkHarness;

  const inSeconds = (seconds: number) => new Date(Date.now() + seconds * 1_000).toISOString();

  /** Two offers with distinct, explicit lapse times -- the sort never depends on when the spec ran. */
  function offers(): CourierOffer[] {
    return [
      offer({
        offerId: 'offer-later',
        orderReference: 'B-2002',
        expiresAt: inSeconds(50),
        orderTotalMinor: 8_000_000,
      }),
      offer({
        offerId: 'offer-soon',
        orderReference: 'A-1042',
        expiresAt: inSeconds(20),
        orderTotalMinor: 12_500_000,
        quotedEarningMinor: 1_800_000,
        quoteCurrency: 'UZS',
      }),
    ];
  }

  async function render(
    record = courierProfile(),
    list: CourierOffer[] = offers(),
    locale: 'en' | 'ru' = 'en',
  ) {
    work = configureWork(record);
    work.api.offers.mockResolvedValue(list);
    work.geo.onceAnswer = fix({ latitude: 41.3, longitude: 69.2, accuracyMeters: 9 });
    const fixture = await renderPage(OffersPage, ['offers', 'work'], locale);
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(work.api.me).toHaveBeenCalled();
    });
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  async function settled(fixture: Awaited<ReturnType<typeof render>>) {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });
  afterEach(() => {
    TestBed.inject(OffersStore).stop();
    sessionStorage.clear();
    localStorage.clear();
  });

  it('asks the courier to open a shift when none is open, and polls for nothing', async () => {
    const page = await render(courierProfile({ openShift: null }));

    expect(textOf(page)).toContain('No shift is open');
    expect(page.nativeElement.querySelector('a[href="/shift"]')?.textContent).toContain(
      'Go to the shift',
    );
    expect(work.api.offers).not.toHaveBeenCalled();
  });

  it('says there is nothing yet, and that new offers arrive by themselves', async () => {
    const page = await render(courierProfile(), []);
    await vi.waitFor(() => {
      page.detectChanges();
      expect(textOf(page)).toContain('No offers right now. New ones appear here by themselves.');
    });
  });

  it('lists the offer that lapses first first, with what the courier needs to decide', async () => {
    const page = await render();
    await vi.waitFor(() => {
      page.detectChanges();
      expect(page.nativeElement.querySelectorAll('article')).toHaveLength(2);
    });

    const cards = [...page.nativeElement.querySelectorAll('article')] as HTMLElement[];
    expect(textOf(cards[0])).toContain('Order A-1042');
    expect(textOf(cards[1])).toContain('Order B-2002');
    const first = textOf(cards[0]);
    expect(first).toContain('You earn');
    expect(first).toContain('18,000');
    expect(first).toContain('Collect');
    expect(first).toContain('125,000');
    expect(first).toContain('Chilonzor kitchen');
    expect(first).toContain('Bunyodkor 12');
    expect(first).toContain('Kitchen: ready');
    expect(first).toContain('3.2 km');
    expect(first).toMatch(/Lapses in (19|20) s/);
  });

  it('says paid online with nothing to collect, and that there is no quote when none resolves', async () => {
    const page = await render(courierProfile(), [
      offer({
        offerId: 'offer-prepaid',
        prepaid: true,
        quotedEarningMinor: null,
        kitchenReady: false,
        expiresAt: inSeconds(30),
      }),
    ]);
    await vi.waitFor(() => {
      page.detectChanges();
      expect(page.nativeElement.querySelector('article')).not.toBeNull();
    });

    const card = textOf(page.nativeElement.querySelector('article'));
    expect(card).toContain('Paid online. Collect nothing.');
    expect(card).toContain('No pay quote for this offer');
    expect(card).toContain('Kitchen: still preparing');
  });

  it('marks an offer that adds to a run the courier already carries', async () => {
    const page = await render(courierProfile(), [
      offer({ payBasis: 'RUN_INCREMENT', expiresAt: inSeconds(30) }),
    ]);
    await vi.waitFor(() => {
      page.detectChanges();
      expect(textOf(page)).toContain('Extra for joining the run you already carry');
    });
  });

  it('accepts with the offer’s version and the courier’s position, then goes to the delivery', async () => {
    const page = await render();
    await vi.waitFor(() => {
      page.detectChanges();
      expect(page.nativeElement.querySelectorAll('article')).toHaveLength(2);
    });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    work.api.acceptOffer.mockResolvedValue({
      outcome: 'ACCEPTED',
      delivery: delivery({ shipmentId: 'ship-9' }),
    });

    buttonLabelled(page.nativeElement.querySelector('article'), 'Accept')!.click();

    await vi.waitFor(() => expect(work.api.acceptOffer).toHaveBeenCalled());
    const [, held, position] = work.api.acceptOffer.mock.calls[0];
    expect(held.offerId).toBe('offer-soon');
    expect(position).toEqual({ latitude: 41.3, longitude: 69.2, accuracyMeters: 9 });
    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith(['/deliveries', 'ship-9']));
  });

  it('says in words that the offer is gone -- not as an error -- and takes it off the list', async () => {
    const page = await render();
    await vi.waitFor(() => {
      page.detectChanges();
      expect(page.nativeElement.querySelectorAll('article')).toHaveLength(2);
    });
    work.api.acceptOffer.mockResolvedValue({ outcome: 'NO_LONGER_AVAILABLE', delivery: null });

    buttonLabelled(page.nativeElement.querySelector('article'), 'Accept')!.click();
    await vi.waitFor(() => {
      page.detectChanges();
      expect(textOf(page)).toContain('That offer is no longer available.');
    });

    expect(page.nativeElement.querySelectorAll('article')).toHaveLength(1);
    expect(page.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('keeps the offer and says what to do when a rule refuses it', async () => {
    const page = await render();
    await vi.waitFor(() => {
      page.detectChanges();
      expect(page.nativeElement.querySelectorAll('article')).toHaveLength(2);
    });
    work.api.acceptOffer.mockRejectedValue(
      new ApiError({
        status: 422,
        code: 'UNPROCESSABLE_STATE',
        reason: 'TOO_FAR_FROM_PICKUP',
        detail: 'developer words',
      }),
    );

    buttonLabelled(page.nativeElement.querySelector('article'), 'Accept')!.click();

    await vi.waitFor(() => {
      page.detectChanges();
      expect(page.nativeElement.querySelector('article [role="alert"]')?.textContent).toContain(
        'too far from the pickup point',
      );
    });
    expect(page.nativeElement.querySelectorAll('article')).toHaveLength(2);
    expect(textOf(page)).not.toContain('developer words');
  });

  it('declines an offer and says so', async () => {
    const page = await render();
    await vi.waitFor(() => {
      page.detectChanges();
      expect(page.nativeElement.querySelectorAll('article')).toHaveLength(2);
    });
    work.api.declineOffer.mockResolvedValue({ outcome: 'DECLINED' });

    buttonLabelled(page.nativeElement.querySelector('article'), 'Decline')!.click();

    await vi.waitFor(() => {
      page.detectChanges();
      expect(textOf(page)).toContain('Offer declined.');
    });
    expect(page.nativeElement.querySelectorAll('article')).toHaveLength(1);
    expect(work.api.declineOffer.mock.calls[0][1].offerId).toBe('offer-soon');
  });

  it('shows the customer’s address only when the tenant allows it, only after an audited tap, and drops it on hide', async () => {
    const page = await render(courierProfile(), [
      offer({ offerId: 'offer-open', customerLocationRevealable: true, expiresAt: inSeconds(40) }),
      offer({ offerId: 'offer-shut', customerLocationRevealable: false, expiresAt: inSeconds(50) }),
    ]);
    await vi.waitFor(() => {
      page.detectChanges();
      expect(page.nativeElement.querySelectorAll('article')).toHaveLength(2);
    });
    const [open, shut] = [...page.nativeElement.querySelectorAll('article')] as HTMLElement[];
    expect(buttonLabelled(shut, 'Show the customer’s address')).toBeNull();
    expect(textOf(open)).not.toContain('Amir Temur');

    work.api.revealOfferLocation.mockResolvedValue({
      latitude: 41.31,
      longitude: 69.25,
      addressLine: 'Amir Temur 1',
      entrance: '2',
      floor: null,
      apartment: '14',
      instructions: null,
    });
    buttonLabelled(open, 'Show the customer’s address')!.click();
    await vi.waitFor(() => {
      page.detectChanges();
      expect(textOf(open)).toContain('Amir Temur 1');
    });
    expect(work.api.revealOfferLocation.mock.calls[0][1]).toBe('offer-open');
    expect(textOf(open)).toContain('Entrance 2');
    expect(textOf(open)).toContain('Apartment 14');
    expect(textOf(open)).toContain('a record is kept');
    expect(open.querySelector('a[href="geo:41.31,69.25"]')).not.toBeNull();

    buttonLabelled(open, 'Hide the address')!.click();
    await settled(page);
    expect(textOf(page)).not.toContain('Amir Temur');
  });

  it('tells the courier plainly when the list could not be read', async () => {
    const page = await render();
    await vi.waitFor(() => {
      page.detectChanges();
      expect(page.nativeElement.querySelectorAll('article')).toHaveLength(2);
    });
    work.api.offers.mockRejectedValue(new ApiError({ status: 0, code: 'NETWORK', detail: 'x' }));

    // The next poll is fifteen seconds away; ask the same store the page reads.
    TestBed.inject(OffersStore).stop();
    TestBed.inject(OffersStore).start({
      brandId: courierProfile().openShift!.brandId,
      locationId: courierProfile().openShift!.locationId,
    });
    await vi.waitFor(() => {
      page.detectChanges();
      expect(textOf(page)).toContain('Cannot reach the server');
    });
  });

  it('speaks the courier’s language', async () => {
    const page = await render(courierProfile(), offers(), 'ru');
    await vi.waitFor(() => {
      page.detectChanges();
      expect(textOf(page)).toContain('Заказ A-1042');
    });
    expect(buttonLabelled(page.nativeElement, 'Принять')).not.toBeNull();
  });
});
