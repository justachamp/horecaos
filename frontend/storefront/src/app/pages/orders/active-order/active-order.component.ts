import { Component, OnInit, OnDestroy, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { OrderItem, ORDER_STATUS_I18N_KEY, formatPlacedAt } from '../orders.data';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { TranslateService } from '../../../services/translate.service';
import { OrdersService, type ApiOrder } from '../../../services/orders.service';
import { NotificationService } from '../../../services/notification.service';

/**
 * Legacy tab-identity tokens, not real order statuses. `OrdersService.getOrders`
 * maps each one to the real platform statuses it covers (see its own
 * `PLATFORM_STATUSES` table) -- a real order's `status` field is never one of
 * these words.
 */
const ACTIVE_ORDER_STATUSES = ['new', 'accepted', 'cooking', 'ready', 'delivering'] as const;

/** How often the list re-reads while this screen is open and visible. */
const POLL_INTERVAL_MS = 10_000;

@Component({
  selector: 'app-active-order',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslatePipe],
  templateUrl: './active-order.component.html',
  styleUrl: './active-order.component.scss'
})
export class ActiveOrderComponent implements OnInit, OnDestroy {
  orders = signal<OrderItem[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);
  cancellingId = signal<string | null>(null);
  cancelError = signal<string | null>(null);

  /** When the list was last confirmed current -- the initial load, or a poll tick. */
  readonly lastUpdated = signal<Date | null>(null);

  private readonly translate = inject(TranslateService);
  private reloadSub?: Subscription;
  private pollSub?: Subscription;

  constructor(
    private ordersService: OrdersService,
    private notification: NotificationService
  ) {}

  ngOnInit(): void {
    this.loadOrders();
    this.reloadSub = this.ordersService.onOrdersLoaded.subscribe((ev) => {
      const expected = [...ACTIVE_ORDER_STATUSES].sort().join();
      const actual = [...ev.statuses].sort().join();
      if (actual === expected) {
        this.orders.set(this.mapApiOrdersToItems(ev.orders));
        this.lastUpdated.set(new Date());
      }
    });
    // A manual reload (the "orders.refresh" button elsewhere, or the retry
    // button below) already keeps the screen current; this is what keeps it
    // current when nobody touches anything -- an order left open on this
    // screen otherwise shows "TASDIQLANDI" long after the kitchen moved on.
    this.pollSub = this.ordersService
      .poll(POLL_INTERVAL_MS, () => this.ordersService.getOrders([...ACTIVE_ORDER_STATUSES]))
      .subscribe((apiOrders) => {
        this.orders.set(this.mapApiOrdersToItems(apiOrders));
        this.lastUpdated.set(new Date());
      });
  }

  ngOnDestroy(): void {
    this.reloadSub?.unsubscribe();
    this.pollSub?.unsubscribe();
  }

  loadOrders(): void {
    this.loading.set(true);
    this.error.set(null);
    this.cancelError.set(null);
    this.ordersService.getOrders([...ACTIVE_ORDER_STATUSES]).subscribe({
      next: (apiOrders) => {
        this.loading.set(false);
        this.orders.set(this.mapApiOrdersToItems(apiOrders));
        this.lastUpdated.set(new Date());
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err?.error?.message ?? err?.message ?? "Buyurtmalar yuklanmadi.");
      },
    });
  }

  private mapApiOrdersToItems(apiOrders: ApiOrder[]): OrderItem[] {
    const currency = this.translate.get('common.currency') || "so'm";
    const itemsUnit = this.translate.get('common.itemsUnit') || 'ta';
    return apiOrders.map((o) => {
      const total = o.total ?? o.total_price ?? 0;
      const priceStr = total > 0 ? `${total.toLocaleString('uz-UZ')} ${currency}` : `0 ${currency}`;
      // `items_count` and `delivery_distance` are only ever set when the API
      // actually reports them -- see OrdersService.toApiOrder's comment. They
      // do not exist on today's order-list response, so these stay empty
      // rather than showing a fabricated "0".
      const itemCount = o.items_count != null ? `${o.items_count} ${itemsUnit}` : '';
      const distM = o.delivery_distance ?? 0;
      const distanceKm = distM > 0 ? (distM / 1000).toFixed(1) : '';
      const orderNum = o.order_number ?? o.number ?? o.id ?? 0;
      const statusId = (typeof o.status === 'object' ? o.status?.id : o.status) ?? '';
      return {
        id: String(o.id),
        title: 'Order',
        subtitle: '',
        status: statusId,
        date: formatPlacedAt(o.created_date),
        price: priceStr,
        image: o.image_url || '/assets/orders/placeholder-order.png',
        orderNumber: orderNum,
        itemCount,
        distanceKm,
        actions: o.actions ?? [],
      };
    });
  }

  /**
   * The platform's own status, translated -- never a guessed legacy word and
   * never a raw i18n key left on screen. `ORDER_STATUS_I18N_KEY` names every
   * status `ordering.domain.OrderStatus` defines; a status this build somehow
   * does not recognise falls back to the raw value from the API rather than
   * an untranslated key path like `orders.statusCONFIRMED`.
   */
  getStatusLabel(status: string): string {
    if (!status) return '';
    const key = ORDER_STATUS_I18N_KEY[status];
    return key ? this.translate.get(key) : status;
  }

  cancelOrder(order: OrderItem): void {
    if (this.cancellingId()) return;
    this.cancelError.set(null);
    this.cancellingId.set(order.id);
    this.ordersService.cancelOrder(order.id).subscribe({
      next: () => {
        this.cancellingId.set(null);
        this.notification.show(`No ${order.orderNumber ?? order.id} order cancelled`);
        this.orders.update((list) => list.filter((o) => o.id !== order.id));
      },
      error: (err) => {
        this.cancellingId.set(null);
        this.cancelError.set(err?.error?.message ?? err?.message ?? "Buyurtma bekor qilinmadi.");
      },
    });
  }
}
