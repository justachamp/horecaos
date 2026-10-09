import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';

import {
  OrderMapPoint,
  OrderMapPointsApi,
  OrderMapPointsResponse,
  TERMINAL_ORDER_STATUSES,
} from '../../core/api/order-map-points-api';
import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { boundsOf } from '../../shared/ui/map/geometry';
import { MapArea, MapCanvas, MapMarker } from '../../shared/ui/map/map-canvas';
import { LatLng, MapBounds } from '../../shared/ui/map/map-provider';
import { describeApiError } from '../orders/order-errors';

const STATUS_KEYS: Readonly<Record<string, MessageKey>> = {
  RECEIVED: 'orders.status.RECEIVED',
  PAYMENT_AUTHORIZING: 'orders.status.PAYMENT_AUTHORIZING',
  AWAITING_APPROVAL: 'orders.status.AWAITING_APPROVAL',
  PAYMENT_FAILED: 'orders.status.PAYMENT_FAILED',
  CONFIRMED: 'orders.status.CONFIRMED',
  REJECTED: 'orders.status.REJECTED',
  EXPIRED: 'orders.status.EXPIRED',
  PREPARING: 'orders.status.PREPARING',
  READY: 'orders.status.READY',
  FULFILLING: 'orders.status.FULFILLING',
  COMPLETED: 'orders.status.COMPLETED',
  CANCELLED: 'orders.status.CANCELLED',
};

type LoadState = 'idle' | 'loading' | 'ready' | 'denied' | 'failed';

/**
 * Today's delivery orders as pins on a map (rows `7.10a` and `3.1`; ADR 0145 decision 8:
 * "a dispatcher-scope read with an ADR 0027 audited purpose, never a reporting fact").
 *
 * **Opening the day's doorsteps is a deliberate act and the screen says so.** Nothing is read when
 * the screen opens and nothing is re-read on a timer: a person presses the button, the platform
 * writes one audit fact naming them and the purpose this screen states (not typed by the person:
 * the screen is the purpose), and the pins appear. Pressing it again is a second reveal and a
 * second fact. The sentence above the map says what pressing it does, because the person pressing
 * is entitled to know that it is recorded.
 *
 * **No pin names anybody.** A pin is an order number, its state and a point. Who lives at it stays
 * behind the per-order reveal, which is a different capability with its own purpose.
 *
 * **The host owns what else is on the map.** The dispatch board puts its couriers on the same map
 * (`extraMarkers`) and the geography report puts the zone outlines under it (`areas`); this
 * component owns only the orders and the act of opening them. A caller without
 * `order.points.reveal` gets one honest sentence in place of the button's result, not an error.
 */
@Component({
  selector: 'q-order-points-map',
  imports: [TPipe, MapCanvas],
  templateUrl: './order-points-map.html',
  styleUrl: './order-points-map.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderPointsMap {
  private readonly api = inject(OrderMapPointsApi);
  private readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  /** Why the doorsteps are being opened: recorded, in the platform's own audit, as this screen's reason. */
  readonly purpose = input.required<string>();
  /** Where the map opens when there is nothing to fit to. */
  readonly center = input.required<LatLng>();
  /** Pins that are not orders: the dispatch board's couriers. */
  readonly extraMarkers = input<readonly MapMarker[]>([]);
  /** Outlines drawn under the pins: the live delivery zones. */
  readonly areas = input<readonly MapArea[]>([]);
  /** Draw the map before anything has been revealed (the dispatch board has couriers to show). */
  readonly alwaysShowMap = input(false);
  readonly height = input(380);

  protected readonly state = signal<LoadState>('idle');
  protected readonly result = signal<OrderMapPointsResponse | null>(null);
  protected readonly errorText = signal<string | null>(null);
  protected readonly openOnly = signal(true);

  private generation = 0;

  protected readonly shown = computed<readonly OrderMapPoint[]>(() => {
    const points = this.result()?.points ?? [];
    return this.openOnly()
      ? points.filter((point) => !TERMINAL_ORDER_STATUSES.has(point.status))
      : points;
  });

  protected readonly orderMarkers = computed<readonly MapMarker[]>(() =>
    this.shown().map((point) => ({
      id: `order:${point.orderId}`,
      position: { latitude: point.latitude, longitude: point.longitude },
      label: this.i18n.t('delivery.orderPoints.pinLabel', {
        number: point.publicOrderNumber,
        status: this.statusLabel(point.status),
      }),
      tone: TERMINAL_ORDER_STATUSES.has(point.status) ? ('closed' as const) : ('order' as const),
    })),
  );

  protected readonly markers = computed<readonly MapMarker[]>(() => [
    ...this.extraMarkers(),
    ...this.orderMarkers(),
  ]);

  /** Fitted once per reveal, to the orders; the host's couriers moving never moves the viewport. */
  protected readonly fit = computed<MapBounds | null>(() => {
    const positions = this.orderMarkers().map((marker) => marker.position);
    return positions.length >= 2 ? boundsOf(positions) : null;
  });

  protected readonly mapCentre = computed<LatLng>(
    () => this.orderMarkers()[0]?.position ?? this.center(),
  );

  protected readonly showMap = computed(
    () => this.alwaysShowMap() || (this.state() === 'ready' && this.result() !== null),
  );

  protected readonly hasCouriers = computed(() => this.extraMarkers().length > 0);

  protected async reveal(): Promise<void> {
    const generation = ++this.generation;
    this.state.set('loading');
    this.errorText.set(null);
    try {
      const result = await this.api.reveal(this.scope(), this.purpose());
      if (generation !== this.generation) {
        return;
      }
      this.result.set(result);
      this.state.set('ready');
    } catch (error) {
      if (generation !== this.generation) {
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.errorText.set(
          error instanceof ApiError
            ? describeApiError(error, (key, values) => this.i18n.t(key, values))
            : this.i18n.t('error.unknown.noReference'),
        );
        this.state.set('failed');
      }
    }
  }

  protected setOpenOnly(checked: boolean): void {
    this.openOnly.set(checked);
  }

  protected statusLabel(status: string): string {
    const key = STATUS_KEYS[status];
    return key === undefined ? status : this.i18n.t(key);
  }
}
