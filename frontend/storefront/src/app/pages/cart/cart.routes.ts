import { Routes } from '@angular/router';
import { CartComponent } from './cart.component';
import { CartItemsComponent } from './cart-items/cart-items.component';
import { CartConfirmationComponent } from './cart-confirmation/cart-confirmation.component';
import { CartOrderStatusComponent } from './cart-order-status/cart-order-status.component';
import { PaymentReturnComponent } from './payment-return/payment-return.component';

export const CART_ROUTES: Routes = [
  {
    path: '',
    component: CartComponent,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'items' },
      { path: 'items', component: CartItemsComponent },
      { path: 'confirmation', component: CartConfirmationComponent },
      { path: 'order-status', component: CartOrderStatusComponent },
      { path: 'order-status/:id', component: CartOrderStatusComponent },
      // Where Click/Payme return the browser after an online payment
      // (PaymentSessionService.open's returnUrl). Outside CartComponent's own
      // chrome would also work; kept a child of it so it shares the same
      // outlet and bottom-nav visibility rules as the rest of /cart.
      { path: 'payment-return/:id', component: PaymentReturnComponent }
    ]
  }
];
