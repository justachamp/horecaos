import { NgModule } from '@angular/core';
import { RouterModule } from '@angular/router';
import { CART_ROUTES } from './cart.routes';

@NgModule({
  imports: [RouterModule.forChild(CART_ROUTES)],
  exports: [RouterModule]
})
export class CartModule {}
