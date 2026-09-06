import { Component, OnInit, signal, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { OrderDetail, OrderLineItem } from '../../pages/orders/orders.data';
import {
  OrdersService,
  type ApiOrderDetail,
  type ApiOrderLineItem,
  type ReorderPlanResponse,
} from '../../services/orders.service';
import { NotificationService } from '../../services/notification.service';
import { TranslateService } from '../../services/translate.service';
import { TranslatePipe } from '../translate/translate.pipe';
import { NavigationHistoryService } from '../../services/navigation-history.service';
import { UiCartService } from '../../services/ui-cart.service';

/**
 * A single order's detail, plus (ADR 0074) whether it can be ordered again.
 *
 * <h2>Repeat, resolved rather than guessed</h2>
 *
 * There used to be no repeat here at all. Building one by matching this
 * order's line names against the current menu would have been the same
 * mistake `frontend/storefront-milliy` made and then had to undo: a renamed
 * dish silently fails to match, a two-variant dish gets whichever one is
 * listed first, and the modifiers never travel. `GET .../orders/{id}/reorder`
 * exists precisely so no client does that -- it resolves the order's own
 * stored variant and modifier ids against the menu as it stands now,
 * including this location's offerings and the kitchen's 86 list, neither of
 * which a storefront can see, and answers one verdict for the whole order.
 *
 * <h2>Why this screen, and one plan per view</h2>
 *
 * This is already a single-order screen: `ngOnInit` reads one order by id,
 * once. Asking for its reorder plan alongside it costs one more request for
 * the same one order, not one per row the way a repeat button on a list
 * screen would (see the design note on `frontend/storefront-milliy`'s
 * `OrdersComponent`, which restricts itself to the newest history row for
 * exactly that reason). There is no list here to economise across.
 *
 * <h2>Hidden, not greyed</h2>
 *
 * The button appears only when the plan's `verdict` is `READY`. `PARTIAL` --
 * some lines available, some not -- renders no button, by the platform
 * owner's own rule: a repeat that quietly drops a dish is not a repeat. A
 * plan request that fails renders no button either, for the same reason a
 * `PARTIAL` one does -- see {@link loadReorderPlan}.
 */
@Component({
  selector: 'app-order-detail',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslatePipe],
  templateUrl: './order-detail.component.html',
  styleUrl: './order-detail.component.scss'
})
export class OrderDetailComponent implements OnInit {
  order = signal<OrderDetail | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);
  cancelling = signal(false);
  cancelError = signal<string | null>(null);

  /**
   * The reorder plan for this one order, or null while it is in flight, has
   * failed, or the order cannot be repeated.
   *
   * Null and "not READY" render identically -- no button -- deliberately: a
   * plan request that failed must not leave behind a button that would fail
   * too.
   */
  reorderPlan = signal<ReorderPlanResponse | null>(null);
  repeating = signal(false);
  repeatError = signal<string | null>(null);

  /** True once the plan says this exact order is READY to repeat. */
  readonly canRepeat = computed(() => {
    const plan = this.reorderPlan();
    const current = this.order();
    return plan !== null && current !== null && plan.verdict === 'READY' && plan.orderId === current.id;
  });

  private readonly translate = inject(TranslateService);
  private readonly cart = inject(UiCartService);

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private history: NavigationHistoryService,
    private ordersService: OrdersService,
    private notification: NotificationService
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.loading.set(false);
      return;
    }
    this.ordersService.getOrderDetail(id).subscribe({
      next: (res) => {
        this.loading.set(false);
        const api = this.unwrapResponse(res);
        this.order.set(this.mapToOrderDetail(api));
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err?.error?.message ?? err?.message ?? "Buyurtma yuklanmadi.");
      },
    });
    this.loadReorderPlan(id);
  }

  /**
   * Asks whether this order can be repeated.
   *
   * Independent of {@link ngOnInit}'s own load: the order detail renders as
   * soon as it arrives, and the repeat button appears a moment later once the
   * platform has answered. A failure leaves the plan null, which {@link
   * canRepeat} reads as "no button" -- the safe direction, and the reason
   * this swallows rather than surfaces the failure.
   */
  private loadReorderPlan(id: string): void {
    this.reorderPlan.set(null);
    this.ordersService.getReorderPlan(id).subscribe({
      next: (plan) => this.reorderPlan.set(plan),
      error: () => this.reorderPlan.set(null),
    });
  }

  /** Unwrap if API returns { data: {...} } or { result: {...} } */
  private unwrapResponse(res: ApiOrderDetail | Record<string, unknown>): ApiOrderDetail {
    const r = res as Record<string, unknown>;
    const data = r?.['data'];
    const result = r?.['result'];
    if (data && typeof data === 'object' && data !== null) return data as ApiOrderDetail;
    if (result && typeof result === 'object' && result !== null) return result as ApiOrderDetail;
    return res as ApiOrderDetail;
  }

  private mapToOrderDetail(api: ApiOrderDetail): OrderDetail {
    const currency = this.translate.get('common.currency') || "so'm";
    const format = (n: number) => (n > 0 ? `${n.toLocaleString('uz-UZ')} ${currency}` : `0 ${currency}`);
    const rawItems = api.items ?? [];
    const lineItems: OrderLineItem[] = rawItems.map((i) => {
      const price = Number(i.price) || 0;
      const qty = Number(i.quantity) || 1;
      const img = i.image;
      return {
        name: String(i.name ?? ''),
        image: img && typeof img === 'string' ? img : '/assets/logo/placeholder-item.png',
        quantity: qty,
        unitPrice: format(price),
        variantId: i.variant_id,
      };
    });
    const totalVal = this.extractPrice(api.total);
    const subtotalVal = api.subtotal != null ? this.extractPrice(api.subtotal) : totalVal;
    const deliveryVal = this.extractPrice(api.delivery);
    const packagingVal = this.extractPrice(api.packaging);
    return {
      id: String(api.id),
      orderNumber: Number(api.order_number ?? api.id),
      lineItems,
      subtotal: format(subtotalVal),
      // The platform's order response has no delivery-fee field (see
      // OrderResponse in StorefrontOrderingController): the amount is folded
      // into `total` with no breakdown. `deliveryVal` is therefore always 0
      // here, and showing "0 so'm" would tell the customer delivery was free
      // when it may not have been -- the row is hidden rather than guessed,
      // the same choice already made for `packaging` below and for
      // cart-order-status.component, which shows no delivery line at all.
      deliveryFee: deliveryVal > 0 ? format(deliveryVal) : undefined,
      total: format(totalVal),
      packaging: packagingVal > 0 ? format(packagingVal) : undefined,
      actions: api.actions ?? [],
    };
  }

  private extractPrice(val: unknown): number {
    if (val == null) return 0;
    if (typeof val === 'number') return val;
    if (typeof val === 'object' && 'price' in val) return Number((val as { price?: unknown }).price) || 0;
    return Number(val) || 0;
  }

  close(): void {
    this.history.back('/orders');
  }

  cancelOrder(): void {
    const o = this.order();
    if (!o || this.cancelling()) return;
    this.cancelError.set(null);
    this.cancelling.set(true);
    this.ordersService.cancelOrder(o.id).subscribe({
      next: () => {
        this.cancelling.set(false);
        this.notification.show(`No ${o.orderNumber} order cancelled`);
        this.router.navigate(['/orders']).catch(() => {});
      },
      error: (err) => {
        this.cancelling.set(false);
        this.cancelError.set(err?.error?.message ?? err?.message ?? "Buyurtma bekor qilinmadi.");
      },
    });
  }

  /**
   * Rebuilds the cart from the plan's own lines -- variant, quantity, *and*
   * every modifier option -- never by matching this order's product names
   * against today's menu. Runs only once {@link canRepeat} is true, which
   * means the plan already said READY -- every line available -- so there is
   * no partial basket to build and nothing to ask the customer to confirm.
   *
   * The plan is still a snapshot: a dish can be 86'd between reading it and
   * this call. `UiCartService.add` refuses on its own if that happens, and
   * pricing stays the authority that would refuse regardless -- narrowing
   * that window is what the plan is for; closing it is not something a
   * client can do.
   */
  async repeat(): Promise<void> {
    const plan = this.reorderPlan();
    if (!this.canRepeat() || !plan || this.repeating()) {
      return;
    }
    this.repeating.set(true);
    this.repeatError.set(null);
    try {
      for (const line of plan.lines) {
        await this.cart.add(line.variantId, line.quantity, undefined, line.modifierOptionIds);
      }
      this.notification.show(
        this.translate.getWithParams('orders.repeatAddedAll', { count: plan.lines.length }),
      );
      this.router.navigate(['/cart']).catch(() => {});
    } catch {
      this.repeatError.set(this.translate.get('errors.generic'));
    } finally {
      this.repeating.set(false);
    }
  }
}
