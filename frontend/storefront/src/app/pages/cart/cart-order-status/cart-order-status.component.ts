import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, ActivatedRoute } from '@angular/router';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { OrdersService } from '../../../services/orders.service';
import { TranslateService } from '../../../services/translate.service';
import type { ApiOrderDetail } from '../../../services/orders.service';

export type OrderStatusStep = 'reviewing' | 'preparing' | 'delivering' | 'delivered';

@Component({
  selector: 'app-cart-order-status',
  standalone: true,
  templateUrl: './cart-order-status.component.html',
  styleUrl: './cart-order-status.component.scss',
  imports: [CommonModule, TranslatePipe]
})
export class CartOrderStatusComponent implements OnInit {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly ordersService = inject(OrdersService);
  private readonly translate = inject(TranslateService);

  /** Order ID from route */
  orderId = signal<string | null>(null);

  /** Current step: reviewing | preparing | delivering | delivered */
  step: OrderStatusStep = 'reviewing';

  /** Cancellation countdown (state 1 only), e.g. "00:59" */
  cancelTimeRemaining = '00:59';

  /** Delivery time range (state 3 only) */
  deliveryTimeFrom = '15:51';
  deliveryTimeTo = '16:01';

  /** Star rating 1–5 (state 4 only) */
  rating = 0;

  /** Courier phone for call link */
  courierPhone = signal<string | null>(null);

  cancelling = signal(false);
  cancelError = signal<string | null>(null);

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    this.orderId.set(id);
    if (id) {
      this.ordersService.getOrderDetail(id).subscribe({
        next: (order) => this.applyOrderData(order),
        error: () => {},
      });
    }
  }

  private applyOrderData(order: ApiOrderDetail): void {
    const statusId = (order.status as { id?: string })?.id ?? '';
    const stepMap: Record<string, OrderStatusStep> = {
      new: 'reviewing',
      accepted: 'reviewing',
      cooking: 'preparing',
      ready: 'delivering',
      delivering: 'delivering',
      completed: 'delivered',
    };
    this.step = stepMap[statusId] ?? 'reviewing';
    const courier = (order as { courier_phone?: string }).courier_phone;
    if (courier) this.courierPhone.set(courier);
  }

  close(): void {
    this.router.navigate(['/cart/items']).catch(() => {});
  }

  cancelOrder(): void {
    const id = this.orderId();
    if (!id || this.cancelling()) return;
    if (!confirm(this.getCancelConfirmMessage())) return;
    this.cancelError.set(null);
    this.cancelling.set(true);
    this.ordersService.cancelOrder(id).subscribe({
      next: () => {
        this.cancelling.set(false);
        this.router.navigate(['/orders', 'active']).catch(() => {});
      },
      error: (err) => {
        this.cancelling.set(false);
        this.cancelError.set(err?.error?.message ?? err?.message ?? 'Order was not cancelled.');
      },
    });
  }

  private getCancelConfirmMessage(): string {
    return this.translate.get('orders.cancelConfirm') || 'Are you sure you want to cancel this order?';
  }

  callCourier(): void {
    const phone = this.courierPhone();
    const tel = phone ? `tel:${phone.replace(/\D/g, '')}` : 'tel:';
    if (typeof window !== 'undefined') {
      window.location.href = tel;
    }
  }

  setRating(value: number): void {
    this.rating = value;
  }

  submitRating(): void {
    this.router.navigate(['/orders', 'active']).catch(() => {});
  }

  openOrderDetails(): void {
    const id = this.orderId();
    if (id) {
      this.router.navigate(['/orders', 'detail', id]).catch(() => {});
    } else {
      this.router.navigate(['/orders', 'active']).catch(() => {});
    }
  }

  /** Step index 0–3 for progress dots */
  stepIndex(step: OrderStatusStep): number {
    const order: OrderStatusStep[] = ['reviewing', 'preparing', 'delivering', 'delivered'];
    return order.indexOf(step);
  }

  isStepActive(index: number): boolean {
    return this.stepIndex(this.step) >= index;
  }
}
