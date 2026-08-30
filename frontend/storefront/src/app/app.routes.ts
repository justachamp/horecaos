import { Routes } from '@angular/router';
import { authGuard } from './guards/auth.guard';
import { HomeComponent } from './pages/home/home.component';
import { ProductComponent } from './pages/product/product.component';
import { ActiveOrderComponent } from './pages/orders/active-order/active-order.component';
import { FinishedOrderComponent } from './pages/orders/finished-order/finished-order.component';
import { CancelledOrderComponent } from './pages/orders/cancelled-order/cancelled-order.component';
import { OrdersComponent } from './pages/orders/orders.component';
import { OrderDetailComponent } from './shared/order-detail/order-detail.component';
import { LocationsComponent } from './pages/locations/locations.component';
import { LocationsAddComponent } from './pages/locations/locations-add/locations-add.component';
import { LocationsListComponent } from './pages/locations/locations-list/locations-list.component';
import { LocationsPermissionComponent } from './pages/locations/locations-permission/locations-permission.component';
import { LocationsSaveComponent } from './pages/locations/locations-save/locations-save.component';
import { AuthComponent } from './pages/auth/auth.component';
import { AuthLoginComponent } from './pages/auth/auth-login/auth-login.component';
import { AuthCodeComponent } from './pages/auth/auth-code/auth-code.component';
import { CategoryItemsComponent } from './pages/category-items/category-items.component';
import { TermsOfConditionsComponent } from './pages/terms/terms-of-conditions.component';
import { SearchComponent } from './pages/search/search.component';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'home' },
  { path: 'home', component: HomeComponent, canActivate: [authGuard] },
  {
    path: 'cart',
    loadChildren: () => import('./pages/cart/cart.module').then((m) => m.CartModule),
    canActivate: [authGuard],
  },
  {
    path: 'auth',
    component: AuthComponent,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'login' },
      { path: 'login', component: AuthLoginComponent },
      { path: 'code', component: AuthCodeComponent },
    ],
  },
  {
    path: 'locations',
    component: LocationsComponent,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'list' },
      { path: 'list', component: LocationsListComponent },
      { path: 'add', component: LocationsAddComponent },
      { path: 'save', component: LocationsSaveComponent },
      { path: 'permission', component: LocationsPermissionComponent },
    ],
  },
  {
    path: 'orders',
    component: OrdersComponent,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'active' },
      { path: 'active', component: ActiveOrderComponent },
      { path: 'finished', component: FinishedOrderComponent },
      { path: 'cancelled', component: CancelledOrderComponent },
      { path: 'detail/:id', component: OrderDetailComponent },
    ],
  },
  {
    path: 'profile',
    loadChildren: () => import('./pages/profile/profile.module').then((m) => m.ProfileModule),
  },
  { path: 'category', component: CategoryItemsComponent },
  { path: 'search', component: SearchComponent, canActivate: [authGuard] },
  { path: 'product/:id', component: ProductComponent },
  { path: 'terms', component: TermsOfConditionsComponent },
  { path: '**', redirectTo: 'home' },
];
