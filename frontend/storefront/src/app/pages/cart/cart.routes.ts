import { Routes } from '@angular/router';
import { CartComponent } from './cart.component';
import { CartItemsComponent } from './cart-items/cart-items.component';
import { CartConfirmationComponent } from './cart-confirmation/cart-confirmation.component';
import { CartOrderStatusComponent } from './cart-order-status/cart-order-status.component';

export const CART_ROUTES: Routes = [
  {
    path: '',
    component: CartComponent,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'items' },
      { path: 'items', component: CartItemsComponent },
      { path: 'confirmation', component: CartConfirmationComponent },
      { path: 'order-status', component: CartOrderStatusComponent },
      { path: 'order-status/:id', component: CartOrderStatusComponent }
    ]
  }
];
