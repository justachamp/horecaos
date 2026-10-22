import { courierProfile } from '../../../testing/fixtures';
import { configureWork, renderPage, textOf, type WorkHarness } from '../../../testing/work-harness';
import { delivery } from '../../../testing/work-fixtures';
import { ApiError } from '../../core/api/problem-details';
import type { CourierDelivery } from '../../core/courier/courier-work';
import { DeliveriesPage } from './deliveries';

describe('DeliveriesPage', () => {
  let work: WorkHarness;

  /** Explicit, distinct assignment times: the order never depends on when the spec ran. */
  const list = (): CourierDelivery[] => [
    delivery({
      shipmentId: 'ship-b',
      orderReference: 'B-2002',
      assignedAt: '2026-10-14T05:20:00Z',
      status: 'PICKED_UP',
      cashDueMinor: 0,
      prepaid: true,
    }),
    delivery({
      shipmentId: 'ship-a',
      orderReference: 'A-1042',
      assignedAt: '2026-10-14T05:00:00Z',
      status: 'ASSIGNED',
    }),
    delivery({
      shipmentId: 'ship-old',
      orderReference: 'Z-0001',
      assignedAt: '2026-10-14T03:00:00Z',
      status: 'DELIVERED',
      deliveredAt: '2026-10-14T03:40:00Z',
    }),
  ];

  async function render(items: CourierDelivery[] = list(), record = courierProfile()) {
    work = configureWork(record);
    work.api.deliveries.mockResolvedValue(items);
    const fixture = await renderPage(DeliveriesPage, ['deliveries', 'work']);
    await vi.waitFor(() => expect(work.api.deliveries).toHaveBeenCalled());
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => sessionStorage.clear());
  afterEach(() => sessionStorage.clear());

  it('lists what the courier carries, oldest assignment first, each as a way into its own screen', async () => {
    const page = await render();
    await vi.waitFor(() => {
      page.detectChanges();
      expect(page.nativeElement.querySelectorAll('.carried a.delivery')).toHaveLength(2);
    });

    const cards = [
      ...page.nativeElement.querySelectorAll('.carried a.delivery'),
    ] as HTMLAnchorElement[];
    expect(cards.map((a) => a.getAttribute('href'))).toEqual([
      '/deliveries/ship-a',
      '/deliveries/ship-b',
    ]);
    expect(textOf(cards[0])).toContain('Order A-1042');
    expect(textOf(cards[0])).toContain('Go to the pickup');
    expect(textOf(cards[0])).toContain('Collect');
    expect(textOf(cards[0])).toContain('125,000');
    expect(textOf(cards[1])).toContain('On the road');
    expect(textOf(cards[1])).toContain('Paid online');
  });

  it('keeps what is finished apart from what is carried', async () => {
    const page = await render();
    await vi.waitFor(() => {
      page.detectChanges();
      expect(textOf(page)).toContain('Order Z-0001');
    });

    const finished = page.nativeElement.querySelector('.finished') as HTMLElement;
    expect(textOf(finished)).toContain('Order Z-0001');
    expect(textOf(finished)).toContain('Delivered');
    expect(textOf(page.nativeElement.querySelector('.carried'))).not.toContain('Z-0001');
  });

  it('says plainly that nothing is carried', async () => {
    const page = await render([]);

    await vi.waitFor(() => {
      page.detectChanges();
      expect(textOf(page)).toContain('You are not carrying anything right now.');
    });
  });

  it('asks again on request, and says when it could not', async () => {
    const page = await render();
    work.api.deliveries.mockRejectedValue(
      new ApiError({ status: 0, code: 'NETWORK', detail: 'x' }),
    );

    (page.nativeElement.querySelector('button') as HTMLButtonElement).click();

    await vi.waitFor(() => {
      page.detectChanges();
      expect(textOf(page)).toContain('Cannot reach the server');
    });
    expect(work.api.deliveries.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('is still there with no shift open: a delivery is finished after the shift closes', async () => {
    const page = await render(list(), courierProfile({ openShift: null }));
    await vi.waitFor(() => {
      page.detectChanges();
      expect(page.nativeElement.querySelectorAll('.carried a.delivery')).toHaveLength(2);
    });
  });
});
