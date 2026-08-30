import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';

import { OrdersService } from '../../../services/orders.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';

/**
 * Where Click and Payme send the browser back after the customer pays or
 * cancels (`PaymentSessionService.open`'s `returnUrl`).
 *
 * The browser landing here proves nothing about the payment -- the platform's
 * own doc comment on `PaymentSessionRequest` is explicit that only the
 * provider's server-to-server callback does. So this screen's one job is to
 * re-read the order (never trust whatever the provider appended to the query
 * string) and hand off to the order-status screen, which shows the platform's
 * own answer and keeps polling it -- if the callback has not landed yet, the
 * customer sees that honestly rather than a page that guessed "paid" from a
 * redirect.
 */
@Component({
  selector: 'app-payment-return',
  standalone: true,
  imports: [CommonModule, TranslatePipe],
  templateUrl: './payment-return.component.html',
  styleUrl: './payment-return.component.scss',
})
export class PaymentReturnComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly ordersService = inject(OrdersService);

  ngOnInit(): void {
    const orderId = this.route.snapshot.paramMap.get('id');
    if (!orderId) {
      this.router.navigate(['/orders', 'active'], { replaceUrl: true }).catch(() => {});
      return;
    }
    // A fresh read rather than trusting a cache: the whole point of landing
    // here is that something happened on the provider's side while this tab
    // was away.
    this.ordersService.getOrderDetail(orderId).subscribe({
      next: () => this.goToStatus(orderId),
      // Even a failed read still sends the customer to the order-status
      // screen, which will try again -- there is nowhere better to put them,
      // and the order itself was created before this page was ever opened.
      error: () => this.goToStatus(orderId),
    });
  }

  private goToStatus(orderId: string): void {
    this.router.navigate(['/cart', 'order-status', orderId], { replaceUrl: true }).catch(() => {});
  }
}
