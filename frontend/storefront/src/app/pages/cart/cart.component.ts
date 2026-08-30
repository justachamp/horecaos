import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { UiCartService } from '../../services/ui-cart.service';
import { TelegramWebappService } from '../../services/telegram-webapp.service';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { NavigationHistoryService } from '../../services/navigation-history.service';

@Component({
  selector: 'app-cart',
  standalone: true,
  imports: [CommonModule, RouterOutlet, TranslatePipe],
  templateUrl: './cart.component.html',
  styleUrl: './cart.component.scss'
})
export class CartComponent {
  private readonly history = inject(NavigationHistoryService);
  private readonly cart = inject(UiCartService);
  readonly telegramWebapp = inject(TelegramWebappService);

  get items() {
    return this.cart.items();
  }

  get deliveryTime() {
    return this.cart.deliveryTime();
  }

  get deliveryPartner() {
    return this.cart.deliveryPartner();
  }

  close(): void {
    this.history.back('/home');
  }

  clearCart(): void {
    this.cart.clearCart();
  }
}
