import { Component, input, signal } from '@angular/core';
import { OrdersService, type ApiOrder } from '../../../services/orders.service';

@Component({
  selector: 'app-order-reload',
  standalone: true,
  templateUrl: './order-reload.component.html',
  styleUrl: './order-reload.component.scss',
})
export class OrderReloadComponent {
  /** Status filter for getOrders (e.g. ['new','accepted','cooking','ready','delivering'] for active) */
  readonly statuses = input.required<string[]>();

  readonly loading = signal(false);

  constructor(private ordersService: OrdersService) {}

  reload(): void {
    const statuses = this.statuses();
    if (!statuses?.length || this.loading()) return;
    this.loading.set(true);
    this.ordersService.getOrders([...statuses]).subscribe({
      next: (orders) => {
        this.loading.set(false);
        this.ordersService.emitOrdersLoaded(statuses, orders);
      },
      error: () => {
        this.loading.set(false);
      },
    });
  }
}
