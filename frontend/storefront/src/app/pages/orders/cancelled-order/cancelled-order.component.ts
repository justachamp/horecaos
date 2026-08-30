import { Component, OnInit, OnDestroy, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { OrderItem } from '../orders.data';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { TranslateService } from '../../../services/translate.service';
import { OrdersService, type ApiOrder } from '../../../services/orders.service';

@Component({
  selector: 'app-cancelled-order',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslatePipe],
  templateUrl: './cancelled-order.component.html',
  styleUrl: './cancelled-order.component.scss'
})
export class CancelledOrderComponent implements OnInit, OnDestroy {
  orders = signal<OrderItem[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);

  private readonly translate = inject(TranslateService);
  private reloadSub?: Subscription;

  constructor(private ordersService: OrdersService) {}

  ngOnInit(): void {
    this.loadOrders();
    this.reloadSub = this.ordersService.onOrdersLoaded.subscribe((ev) => {
      if (ev.statuses.length === 1 && ev.statuses[0] === 'cancelled') {
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
    this.ordersService.getOrders(['cancelled']).subscribe({
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
      return {
        id: String(o.id),
        title: 'Order',
        subtitle: '',
        status: 'bekor',
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
}
