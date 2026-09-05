import { Component, OnInit, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { OrderDetail, OrderLineItem } from '../../pages/orders/orders.data';
import { OrdersService, type ApiOrderDetail, type ApiOrderLineItem } from '../../services/orders.service';
import { NotificationService } from '../../services/notification.service';
import { TranslateService } from '../../services/translate.service';
import { TranslatePipe } from '../translate/translate.pipe';
import { NavigationHistoryService } from '../../services/navigation-history.service';

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

  private readonly translate = inject(TranslateService);

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
}
