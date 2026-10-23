import { fix } from '../../../testing/fake-device';
import { courierProfile } from '../../../testing/fixtures';
import {
  buttonLabelled,
  configureWork,
  renderPage,
  textOf,
  type WorkHarness,
} from '../../../testing/work-harness';
import { delivery } from '../../../testing/work-fixtures';
import { ApiError } from '../../core/api/problem-details';
import type { CourierDelivery } from '../../core/courier/courier-work';
import { DeliveryPage } from './delivery';

describe('DeliveryPage', () => {
  let work: WorkHarness;

  async function render(held: CourierDelivery, locale: 'en' | 'ru' = 'en') {
    work = configureWork(courierProfile());
    work.api.delivery.mockResolvedValue(held);
    work.geo.onceAnswer = fix({ latitude: 41.3, longitude: 69.2, accuracyMeters: 9 });
    const fixture = await renderPage(DeliveryPage, ['deliveries', 'work'], locale, {
      id: held.shipmentId,
    });
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(work.api.delivery).toHaveBeenCalled();
    });
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  async function tap(fixture: Awaited<ReturnType<typeof render>>, label: string) {
    const button = buttonLabelled(fixture.nativeElement, label);
    expect(button, `a button labelled "${label}"`).not.toBeNull();
    button!.click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const assigned = delivery({ shipmentId: 'ship-1', version: 4 });
  const waiting = delivery({ shipmentId: 'ship-1', version: 5, status: 'PICKUP_PENDING' });
  const onTheRoad = delivery({
    shipmentId: 'ship-1',
    version: 6,
    status: 'PICKED_UP',
    pickedUpAt: '2026-10-14T05:15:00Z',
  });

  beforeEach(() => sessionStorage.clear());
  afterEach(() => sessionStorage.clear());

  describe('what it shows', () => {
    it('shows the order, where to go and what is due, with money from minor units', async () => {
      const page = await render(assigned);

      const text = textOf(page);
      expect(text).toContain('Order A-1042');
      expect(text).toContain('Go to the pickup');
      expect(text).toContain('Chilonzor kitchen');
      expect(text).toContain('Bunyodkor 12');
      expect(text).toContain('Collect');
      expect(text).toContain('125,000');
      expect(text).toContain('Kitchen: ready');
      expect(text).toContain('3.2 km');
      expect(page.nativeElement.querySelector('a[href="geo:41.2753,69.2037"]')).not.toBeNull();
    });

    it('says a delivery that is not the courier’s, or is gone, is exactly that', async () => {
      work = configureWork(courierProfile());
      work.api.delivery.mockRejectedValue(
        new ApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'x' }),
      );
      const page = await renderPage(DeliveryPage, ['deliveries', 'work'], 'en', { id: 'nobody' });

      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('not yours, or it no longer exists');
      });
    });

    it('speaks Russian', async () => {
      const page = await render(assigned, 'ru');

      expect(textOf(page)).toContain('Заказ A-1042');
      expect(buttonLabelled(page.nativeElement, 'Забрал')).not.toBeNull();
    });
  });

  describe('arriving and picking up', () => {
    it('offers arrival and pickup from a new assignment, and arrival moves it to waiting', async () => {
      const page = await render(assigned);
      expect(buttonLabelled(page.nativeElement, 'I have arrived')).not.toBeNull();
      expect(buttonLabelled(page.nativeElement, 'Picked up')).not.toBeNull();
      work.api.advance.mockResolvedValue(waiting);

      await tap(page, 'I have arrived');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('Waiting at the pickup');
      });
      expect(work.api.advance.mock.calls[0].slice(1, 4)).toEqual([
        assigned,
        'PICKUP_PENDING',
        { latitude: 41.3, longitude: 69.2, accuracyMeters: 9 },
      ]);
      expect(buttonLabelled(page.nativeElement, 'I have arrived')).toBeNull();
    });

    it('picks up through the pickup gate, and a soft gate that could not confirm the position says so', async () => {
      const page = await render(waiting);
      work.api.pickUp.mockResolvedValue({
        ...onTheRoad,
        checks: [
          { kind: 'GEO', outcome: 'FAILED' },
          { kind: 'PHOTO', outcome: 'SKIPPED' },
        ],
      });

      await tap(page, 'Picked up');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('On the road');
      });
      expect(textOf(page)).toContain(
        'Done. The platform could not confirm: location, photo. Your dispatcher can see this.',
      );
      expect(page.nativeElement.querySelector('.notice.warning')).not.toBeNull();
      expect(page.nativeElement.querySelector('[role="alert"]')).toBeNull();
    });

    it('is refused by a hard gate (409 GATE_FAILED) with the failed checks named, and stays where it was', async () => {
      const page = await render(waiting);
      work.api.pickUp.mockRejectedValue(
        new ApiError({ status: 409, code: 'GATE_FAILED', detail: 'GEO' }),
      );

      await tap(page, 'Picked up');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(page.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
          'This step was refused. These checks could not be confirmed: location.',
        );
      });
      expect(textOf(page)).toContain('Waiting at the pickup');
      expect(buttonLabelled(page.nativeElement, 'Picked up')).not.toBeNull();
    });

    it('can be tried again once the courier has moved, with a fresh decision', async () => {
      const page = await render(waiting);
      work.api.pickUp
        .mockRejectedValueOnce(new ApiError({ status: 409, code: 'GATE_FAILED', detail: 'GEO' }))
        .mockResolvedValueOnce({ ...onTheRoad, checks: [{ kind: 'GEO', outcome: 'PASSED' }] });

      await tap(page, 'Picked up');
      await vi.waitFor(() => {
        page.detectChanges();
        expect(page.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
      });
      await tap(page, 'Picked up');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('Every check passed.');
      });
      expect(page.nativeElement.querySelector('[role="alert"]')).toBeNull();
      expect(work.api.pickUp.mock.calls[1][3]).not.toBe(work.api.pickUp.mock.calls[0][3]);
    });

    it('says in words what a rule that refused wants -- here, that the courier is too far', async () => {
      const page = await render(waiting);
      work.api.pickUp.mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'TOO_FAR_FROM_PICKUP',
          detail: 'x',
        }),
      );

      await tap(page, 'Picked up');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(page.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
          'Go closer and try again',
        );
      });
    });

    it('says the kitchen has not finished, when that is the reason', async () => {
      const page = await render(delivery({ ...waiting, kitchenReady: false }));
      expect(textOf(page)).toContain('Kitchen: still preparing');
      work.api.pickUp.mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'KITCHEN_NOT_READY',
          detail: 'x',
        }),
      );

      await tap(page, 'Picked up');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('The kitchen has not finished this order yet.');
      });
    });

    it('reloads and says so when somebody else moved the delivery first', async () => {
      const page = await render(waiting);
      work.api.pickUp.mockRejectedValue(
        new ApiError({ status: 409, code: 'STALE_VERSION', detail: 'x' }),
      );
      work.api.delivery.mockResolvedValue(onTheRoad);

      await tap(page, 'Picked up');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('This changed while you were looking.');
      });
      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('On the road');
      });
    });

    it('asks for the position the platform wants when it is missing, and for nothing else', async () => {
      const page = await render(waiting);
      work.geo.onceAnswer = 'denied';
      work.api.pickUp.mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'GPS_POSITION_REQUIRED',
          detail: 'x',
        }),
      );

      await tap(page, 'Picked up');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('Turn on location for this app and try again.');
      });
      expect(work.api.pickUp.mock.calls[0][2]).toBeNull();
    });
  });

  describe('the cash and the handover', () => {
    it('does not offer the handover until the cash is confirmed when the tenant requires it', async () => {
      const page = await render(delivery({ ...onTheRoad, paymentConfirmationRequired: true }));

      expect(buttonLabelled(page.nativeElement, 'Delivered to the customer')?.disabled).toBe(true);
      expect(textOf(page)).toContain('Confirm the cash before you hand the order over.');
      expect(buttonLabelled(page.nativeElement, 'Confirm cash')).not.toBeNull();
    });

    it('confirms the cash counted, in minor units, and then allows the handover', async () => {
      const held = delivery({ ...onTheRoad, paymentConfirmationRequired: true });
      const page = await render(held);
      work.api.confirmPayment.mockResolvedValue({
        ...held,
        version: 7,
        paymentConfirmedAt: '2026-10-14T05:30:00Z',
        paymentConfirmedMinor: 12_500_000,
      });
      const input = page.nativeElement.querySelector('input') as HTMLInputElement;

      input.value = 'many';
      input.dispatchEvent(new Event('input'));
      await tap(page, 'Confirm cash');
      expect(textOf(page)).toContain('Enter the amount');
      expect(work.api.confirmPayment).not.toHaveBeenCalled();

      input.value = '125 000';
      input.dispatchEvent(new Event('input'));
      await tap(page, 'Confirm cash');

      await vi.waitFor(() => expect(work.api.confirmPayment).toHaveBeenCalled());
      expect(work.api.confirmPayment.mock.calls[0][2]).toBe(12_500_000);
      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('Cash confirmed:');
      });
      expect(buttonLabelled(page.nativeElement, 'Delivered to the customer')?.disabled).toBe(false);
    });

    it('tells the courier when the amount is not the amount due', async () => {
      const held = delivery({ ...onTheRoad, paymentConfirmationRequired: true });
      const page = await render(held);
      work.api.confirmPayment.mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'PAYMENT_AMOUNT_MISMATCH',
          detail: 'x',
        }),
      );
      const input = page.nativeElement.querySelector('input') as HTMLInputElement;
      input.value = '100';
      input.dispatchEvent(new Event('input'));

      await tap(page, 'Confirm cash');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(page.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
          'not the amount due',
        );
      });
    });

    it('asks for no cash on an order paid online', async () => {
      const page = await render(delivery({ ...onTheRoad, prepaid: true, cashDueMinor: 0 }));

      expect(textOf(page)).toContain('Paid online. Collect nothing.');
      expect(buttonLabelled(page.nativeElement, 'Confirm cash')).toBeNull();
      expect(buttonLabelled(page.nativeElement, 'Delivered to the customer')?.disabled).toBe(false);
    });

    it('hands over through the handover gate and thanks the courier', async () => {
      const held = delivery({ ...onTheRoad, prepaid: true, cashDueMinor: 0 });
      const page = await render(held);
      work.api.handOver.mockResolvedValue({
        ...held,
        status: 'DELIVERED',
        version: 7,
        deliveredAt: '2026-10-14T05:45:00Z',
        checks: [{ kind: 'GEO', outcome: 'PASSED' }],
      });

      await tap(page, 'Delivered to the customer');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('Delivered. Thank you.');
      });
      expect(work.api.handOver.mock.calls[0][1]).toEqual(held);
      expect(textOf(page)).toContain('Every check passed.');
      expect(buttonLabelled(page.nativeElement, 'Delivered to the customer')).toBeNull();
      expect(textOf(page)).toContain('Delivered at 10:45');
    });

    it('is refused by a hard handover gate with the checks named, and nothing is delivered', async () => {
      const held = delivery({ ...onTheRoad, prepaid: true, cashDueMinor: 0 });
      const page = await render(held);
      work.api.handOver.mockRejectedValue(
        new ApiError({ status: 409, code: 'GATE_FAILED', detail: 'GEO, PAYMENT' }),
      );

      await tap(page, 'Delivered to the customer');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(page.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
          'location, payment',
        );
      });
      expect(textOf(page)).toContain('On the road');
    });

    it('is told too far from the door in words', async () => {
      const held = delivery({ ...onTheRoad, prepaid: true, cashDueMinor: 0 });
      const page = await render(held);
      work.api.handOver.mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'TOO_FAR_FROM_DROPOFF',
          detail: 'x',
        }),
      );

      await tap(page, 'Delivered to the customer');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('too far from the customer’s door');
      });
    });
  });

  describe('the customer’s address', () => {
    const door = {
      latitude: 41.31,
      longitude: 69.25,
      addressLine: 'Amir Temur 1',
      entrance: '2',
      floor: '3',
      apartment: '14',
      instructions: 'Ring twice',
    };

    it('is opened by a deliberate tap, shown with a way to navigate, and gone when hidden', async () => {
      const page = await render(onTheRoad);
      expect(textOf(page)).not.toContain('Amir Temur');
      work.api.revealDeliveryLocation.mockResolvedValue(door);

      await tap(page, 'Show the customer’s address');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('Amir Temur 1');
      });
      expect(work.api.revealDeliveryLocation.mock.calls[0][1]).toBe('ship-1');
      expect(textOf(page)).toContain('Ring twice');
      expect(page.nativeElement.querySelector('a[href="geo:41.31,69.25"]')).not.toBeNull();

      await tap(page, 'Hide the address');
      expect(textOf(page)).not.toContain('Amir Temur');
      expect(page.nativeElement.innerHTML).not.toContain('Amir Temur');
    });

    it('is not offered once the delivery is finished', async () => {
      const page = await render(
        delivery({ ...onTheRoad, status: 'DELIVERED', deliveredAt: '2026-10-14T05:45:00Z' }),
      );

      expect(buttonLabelled(page.nativeElement, 'Show the customer’s address')).toBeNull();
    });

    it('says why when the platform will not show it', async () => {
      const page = await render(onTheRoad);
      work.api.revealDeliveryLocation.mockRejectedValue(
        new ApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          reason: 'STEP_NOT_ALLOWED',
          detail: 'x',
        }),
      );

      await tap(page, 'Show the customer’s address');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(page.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
      });
    });
  });

  it('says a cancelled delivery is cancelled, and offers no step', async () => {
    const page = await render(delivery({ shipmentId: 'ship-1', status: 'CANCELLED' }));

    expect(textOf(page)).toContain('This delivery was cancelled.');
    expect(buttonLabelled(page.nativeElement, 'Picked up')).toBeNull();
    expect(buttonLabelled(page.nativeElement, 'Delivered to the customer')).toBeNull();
  });

  it('leaves the buttons alone while a step is in the air, so a double tap is one step', async () => {
    const page = await render(waiting);
    let release: (value: unknown) => void = () => undefined;
    work.api.pickUp.mockReturnValue(new Promise((resolve) => (release = resolve)));

    buttonLabelled(page.nativeElement, 'Picked up')!.click();
    await vi.waitFor(() => expect(work.api.pickUp).toHaveBeenCalledOnce());
    await vi.waitFor(() => {
      page.detectChanges();
      expect(buttonLabelled(page.nativeElement, 'Picked up')?.disabled).toBe(true);
    });
    buttonLabelled(page.nativeElement, 'Picked up')!.click();
    release({ ...onTheRoad });
    await page.whenStable();

    expect(work.api.pickUp).toHaveBeenCalledOnce();
  });
});
