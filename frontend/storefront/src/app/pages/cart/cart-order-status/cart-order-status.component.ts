import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { OrdersService } from '../../../services/orders.service';
import { TranslateService } from '../../../services/translate.service';
import { MenuService } from '../../../services/menu.service';
import { AnalyticsInjector } from '../../../core/analytics/analytics-injector';
import { pushEcommerceEvent } from '../../../core/analytics/ecommerce-events';
import { money, toMajorUnits } from '../../../core/money/money';
import type { ApiOrderDetail, ApiOrderLineItem } from '../../../services/orders.service';

/** Fallback ISO currency when the menu that built this cart was never loaded this session. */
const FALLBACK_CURRENCY = 'UZS';

/** `sessionStorage` prefix for the purchase dedup flag — see {@link CartOrderStatusComponent.trackPurchaseOnce}. */
const PURCHASE_TRACKED_KEY_PREFIX = 'horecaos:ga4:purchase:';

/** How often the order re-reads while this screen is open and visible. */
const POLL_INTERVAL_MS = 10_000;

/**
 * The platform statuses an order may still be cancelled from.
 *
 * Mirrors `OrdersService`'s own `PLATFORM_STATUSES.new` grouping: once an
 * order reaches `CONFIRMED` the kitchen has accepted it, and the platform
 * refuses a cancellation at any version from there on.
 */
const CANCELLABLE_STATUSES: ReadonlySet<string> = new Set([
  'RECEIVED',
  'PAYMENT_AUTHORIZING',
  'AWAITING_APPROVAL',
]);

/** The platform's own status vocabulary, translated. Nothing here is invented. */
const STATUS_I18N_KEY: Readonly<Record<string, string>> = {
  RECEIVED: 'orders.platformStatus.RECEIVED',
  PAYMENT_AUTHORIZING: 'orders.platformStatus.PAYMENT_AUTHORIZING',
  AWAITING_APPROVAL: 'orders.platformStatus.AWAITING_APPROVAL',
  CONFIRMED: 'orders.platformStatus.CONFIRMED',
  PREPARING: 'orders.platformStatus.PREPARING',
  READY: 'orders.platformStatus.READY',
  FULFILLING: 'orders.platformStatus.FULFILLING',
  COMPLETED: 'orders.platformStatus.COMPLETED',
  CANCELLED: 'orders.platformStatus.CANCELLED',
  REJECTED: 'orders.platformStatus.REJECTED',
  EXPIRED: 'orders.platformStatus.EXPIRED',
  PAYMENT_FAILED: 'orders.platformStatus.PAYMENT_FAILED',
};

/**
 * The order-status screen, showing only what the order actually is.
 *
 * This used to render a four-step "reviewing / preparing / delivering /
 * delivered" progress bar keyed off legacy tab tokens ('new', 'accepted', …)
 * that the platform's `OrderResponse.status` never sends -- the real values
 * are `RECEIVED`, `CONFIRMED`, `PREPARING` and so on, so the mapping always
 * missed and every order silently rendered as "reviewing". It also carried a
 * hardcoded cancellation countdown, a hardcoded delivery time window, a
 * courier phone number and a five-star rating prompt, none of which the
 * platform has ever sent: there is no rating backend, no courier assignment
 * surfaced to the storefront yet, and no delivery-window estimate on an order.
 *
 * What is real, and what this renders instead: the order's own status
 * (translated, not invented), its line items and totals, and a last-updated
 * stamp backed by actual polling.
 */
@Component({
  selector: 'app-cart-order-status',
  standalone: true,
  templateUrl: './cart-order-status.component.html',
  styleUrl: './cart-order-status.component.scss',
  imports: [CommonModule, TranslatePipe],
})
export class CartOrderStatusComponent implements OnInit, OnDestroy {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly ordersService = inject(OrdersService);
  private readonly translate = inject(TranslateService);
  private readonly menuService = inject(MenuService);
  private readonly analytics = inject(AnalyticsInjector);

  /** Order ID from route */
  readonly orderId = signal<string | null>(null);

  readonly order = signal<ApiOrderDetail | null>(null);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);
  readonly lastUpdated = signal<Date | null>(null);

  readonly cancelling = signal(false);
  readonly cancelError = signal<string | null>(null);

  private pollSub?: Subscription;

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    this.orderId.set(id);
    if (!id) {
      this.loading.set(false);
      return;
    }
    // ADR 0106, gap-map row 10.8e: fire-and-forget — a customer's order
    // confirmation must never wait on, or fail because of, a third-party
    // analytics script.
    void this.analytics.ensureLoaded();

    this.ordersService.getOrderDetail(id).subscribe({
      next: (order) => {
        this.loading.set(false);
        this.order.set(order);
        this.lastUpdated.set(new Date());
        this.trackPurchaseOnce(id, order);
      },
      error: (err) => {
        this.loading.set(false);
        this.loadError.set(
          err?.error?.message ?? err?.message ?? this.translate.get('orders.orderNotFound'),
        );
      },
    });
    this.pollSub = this.ordersService
      .poll(POLL_INTERVAL_MS, () => this.ordersService.getOrderDetail(id))
      .subscribe((order) => {
        this.order.set(order);
        this.lastUpdated.set(new Date());
      });
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  /** The status as the platform sent it, translated. Never a guessed step. */
  statusLabel(): string {
    const statusId = this.rawStatus();
    if (!statusId) return '';
    const key = STATUS_I18N_KEY[statusId];
    return key ? this.translate.get(key) : statusId;
  }

  isCancellable(): boolean {
    const statusId = this.rawStatus();
    return statusId !== null && CANCELLABLE_STATUSES.has(statusId);
  }

  placedAtLabel(): string {
    const created = this.order()?.created_date;
    if (!created) return '';
    const parsed = new Date(created);
    return Number.isNaN(parsed.getTime()) ? created : parsed.toLocaleString();
  }

  lineItems(): ApiOrderLineItem[] {
    return this.order()?.items ?? [];
  }

  lineTotal(item: ApiOrderLineItem): number {
    return (item.price ?? 0) * (item.quantity ?? 0);
  }

  formatPrice(value: number): string {
    const currency = this.translate.get('common.currency') || "so'm";
    return `${value.toLocaleString('uz-UZ')} ${currency}`;
  }

  subtotalFormatted(): string {
    return this.formatPrice(this.priceOf(this.order()?.subtotal));
  }

  totalFormatted(): string {
    return this.formatPrice(this.priceOf(this.order()?.total));
  }

  close(): void {
    this.router.navigate(['/cart/items']).catch(() => {});
  }

  cancelOrder(): void {
    const id = this.orderId();
    if (!id || this.cancelling()) return;
    if (!confirm(this.translate.get('orders.cancelConfirm'))) return;
    this.cancelError.set(null);
    this.cancelling.set(true);
    this.ordersService.cancelOrder(id).subscribe({
      next: () => {
        this.cancelling.set(false);
        this.router.navigate(['/orders', 'active']).catch(() => {});
      },
      error: (err) => {
        this.cancelling.set(false);
        this.cancelError.set(
          err?.error?.message ?? err?.message ?? this.translate.get('orders.cancelError'),
        );
      },
    });
  }

  openOrderDetails(): void {
    const id = this.orderId();
    if (id) {
      this.router.navigate(['/orders', 'detail', id]).catch(() => {});
    } else {
      this.router.navigate(['/orders', 'active']).catch(() => {});
    }
  }

  /**
   * ADR 0106, gap-map row 10.8e: `purchase`, GA4 ecommerce event contract v1,
   * fired once per order.
   *
   * Deduplicated in `sessionStorage` by `transaction_id` (the platform order
   * id) rather than relying only on this method's own call site running
   * once — a browser refresh remounts this component and re-subscribes to
   * `getOrderDetail`, which would otherwise re-fire `purchase` on every
   * reload of the confirmation screen. GA4 itself also deduplicates
   * `purchase` events sharing a `transaction_id` (its own documented
   * behaviour), so this is belt-and-braces against a poll or a remount
   * counting one order twice, not the only safeguard.
   */
  private trackPurchaseOnce(orderId: string, order: ApiOrderDetail): void {
    const key = `${PURCHASE_TRACKED_KEY_PREFIX}${orderId}`;
    try {
      if (sessionStorage.getItem(key) === '1') {
        return;
      }
      sessionStorage.setItem(key, '1');
    } catch {
      // Private browsing or storage disabled: track anyway rather than
      // silently dropping the one event this wave wires — a rare double
      // count from a refresh is a smaller cost than never counting at all.
    }

    // GA4's ecommerce object is major units (so'm), not this platform's own
    // minor-units wire convention (ADR 0018) — `toMajorUnits` is the one
    // conversion boundary the event contract's own doc names, see
    // `docs/analytics/ga4-ecommerce-event-contract-v1.md`.
    const currency = this.menuService.currency() ?? FALLBACK_CURRENCY;
    const items = (order.items ?? []).map((item) => ({
      item_id: String(item.variant_id ?? item.item_id ?? ''),
      item_name: item.name ?? '',
      price: toMajorUnits(money(item.price ?? 0, currency)),
      quantity: item.quantity ?? 0,
    }));

    pushEcommerceEvent('purchase', {
      currency,
      value: toMajorUnits(money(this.priceOf(order.total), currency)),
      items,
      transaction_id: orderId,
    });
  }

  private rawStatus(): string | null {
    return this.order()?.status?.id ?? null;
  }

  private priceOf(value: { price?: number } | number | undefined): number {
    if (value == null) return 0;
    return typeof value === 'number' ? value : (value.price ?? 0);
  }
}
