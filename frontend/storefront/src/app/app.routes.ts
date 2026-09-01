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
  // Public: the pre-account browse surface (ADR 0016). Home reads the
  // published menu unauthenticated, the same way the platform serves it --
  // see SecurityConfiguration's storefront GET permitAll list. `category`,
  // `product/:id` and `search` below are the same menu document read three
  // other ways and were never gated to begin with.
  { path: 'home', component: HomeComponent },
  // Gated: checkout. The platform has no anonymous-cart capability -- POST
  // /carts is not in SecurityConfiguration's permitAll list, so a basket
  // cannot exist without a session. UiCartService.add (see food-card and
  // product) is the second, earlier half of this boundary: it sends an
  // anonymous visitor here to sign in before the first line is even
  // attempted, rather than letting the write 401.
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
  // Gated: this is the customer's own saved-address book (/me/addresses),
  // not the branch-discovery surface -- unrelated to the public
  // pickup-locations endpoint and personal data either way.
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
  // Gated: a customer's own order history, ownership-authorised the same
  // way /me is.
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
  // Public at the top level: ProfileComponent already renders a signed-out
  // state (a "Sign in" affordance in place of account data -- see
  // isAuthorized() in profile.component.ts) rather than assuming a session.
  // The account-only screens under it (details, favorites) are individually
  // gated in profile.routes.ts; language/faq/support/telegram are not,
  // because they are either not personal (language, faq, support read the
  // brand's own public content) or already session-optional in place
  // (telegram's own needsSignIn prompt).
  {
    path: 'profile',
    loadChildren: () => import('./pages/profile/profile.module').then((m) => m.ProfileModule),
  },
  { path: 'category', component: CategoryItemsComponent },
  // Client-side search over the already-loaded menu (MenuService.search) --
  // no platform endpoint of its own, so nothing here needs a session either.
  { path: 'search', component: SearchComponent },
  { path: 'product/:id', component: ProductComponent },
  { path: 'terms', component: TermsOfConditionsComponent },
  { path: '**', redirectTo: 'home' },
];
