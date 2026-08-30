import { Component, OnInit, OnDestroy, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { OrderItem, API_TO_UI_STATUS, UI_TO_API_STATUS, API_STATUS_TO_I18N_KEY } from '../orders.data';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { TranslateService } from '../../../services/translate.service';
import { OrdersService, type ApiOrder } from '../../../services/orders.service';
import { NotificationService } from '../../../services/notification.service';

/** API status values for active (in-progress) orders tab */
const ACTIVE_ORDER_STATUSES = ['new', 'accepted', 'cooking', 'ready', 'delivering'] as const;

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

  private readonly translate = inject(TranslateService);
  private reloadSub?: Subscription;

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
      }
    });
  }

  ngOnDestroy(): void {
    this.reloadSub?.unsubscribe();
  }

  loadOrders(): void {
    this.loading.set(true);
    this.error.set(null);
    this.cancelError.set(null);
    this.ordersService.getOrders([...ACTIVE_ORDER_STATUSES]).subscribe({
      next: (apiOrders) => {
        this.loading.set(false);
        this.orders.set(this.mapApiOrdersToItems(apiOrders));
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
      const itemCount = o.items_count != null ? `${o.items_count} ${itemsUnit}` : '';
      const distM = o.delivery_distance ?? 0;
      const distanceKm = distM > 0 ? (distM / 1000).toFixed(1) : '';
      const orderNum = o.order_number ?? o.number ?? o.id ?? 0;
      const statusId = (typeof o.status === 'object' ? o.status?.id : o.status) ?? '';
      const uiStatus = API_TO_UI_STATUS[statusId ?? ''] ?? statusId ?? 'tasdiqlandi';
      return {
        id: String(o.id),
        title: 'Order',
        subtitle: '',
        status: uiStatus as OrderItem['status'],
        date: '',
        price: priceStr,
        image: o.image_url || '/jizbiz/orders/buyurtmalar_0.png',
        orderNumber: orderNum,
        itemCount,
        distanceKm,
        actions: o.actions ?? [],
      };
    });
  }

  getStatusLabel(status: string): string {
    const apiStatus = UI_TO_API_STATUS[status] ?? status.toLowerCase();
    const i18nSuffix = API_STATUS_TO_I18N_KEY[apiStatus];
    const key = i18nSuffix ? `orders.${i18nSuffix}` : `orders.status${status.charAt(0).toUpperCase() + status.slice(1)}`;
    return this.translate.get(key) || status;
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
