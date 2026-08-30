import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { UiCartService } from '../../services/ui-cart.service';
import { TranslatePipe } from '../translate/translate.pipe';

@Component({
  selector: 'app-cart-hint-badge',
  standalone: true,
  templateUrl: './cart-hint-badge.component.html',
  styleUrl: './cart-hint-badge.component.scss',
  imports: [CommonModule, RouterLink, TranslatePipe],

})
export class CartHintBadgeComponent {
  constructor(public cart: UiCartService) {}

  get showBadge(): boolean {
    return this.cart.totalItemsCount() > 0;
  }
}
