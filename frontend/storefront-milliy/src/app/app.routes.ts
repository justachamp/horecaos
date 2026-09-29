import { Routes } from '@angular/router';

import { authGuard } from './guards/auth.guard';

/**
 * The six screens the Milliy design defines, plus `referral` (ADR 0067):
 * a customer's own code, sharing it, and redeeming a friend's, reached from
 * a real row on the profile screen rather than promised and left unbuilt --
 * and the table-QR scan landing and table menu (ADR 0047, `dine-in/...`).
 *
 * The gating mirrors the first storefront's, because it mirrors the platform:
 * the published menu is browsable without an account (ADR 0016, and
 * `SecurityConfiguration`'s storefront permitAll list), while a cart, an order
 * and a profile all require a proven identity — the platform has no anonymous
 * form of any of them. `referral` follows the same gate: a code is minted for
 * a real customer account, never a browsing stranger.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'home' },
  {
    path: 'home',
    loadComponent: () => import('./pages/home/home.component').then((m) => m.HomeComponent),
  },
  {
    path: 'product/:productId',
    // `withComponentInputBinding` feeds the route param straight into the
    // component's required input, so the screen never reads the router itself.
    loadComponent: () => import('./pages/details/details.component').then((m) => m.DetailsComponent),
  },
  // ADR 0047's table-QR flow. `:tableToken` is the one-time value a table's
  // printed code encodes -- DineInScanComponent spends it once, against
  // QrEntryController.exchange, and replaces the URL with the token-free
  // `dine-in/table` so the printed token never sits in history past that single
  // request. `dine-in/table` is declared FIRST: otherwise the token route would
  // swallow it as a table token called "table". Public: a guest at a table holds
  // no account yet, the same pre-account standing as the menu above.
  {
    path: 'dine-in/table',
    loadComponent: () =>
      import('./pages/dine-in/dine-in-table/dine-in-table.component').then(
        (m) => m.DineInTableComponent,
      ),
  },
  {
    path: 'dine-in/:tableToken',
    loadComponent: () =>
      import('./pages/dine-in/dine-in-scan/dine-in-scan.component').then(
        (m) => m.DineInScanComponent,
      ),
  },
  {
    path: 'cart',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/cart/cart.component').then((m) => m.CartComponent),
  },
  {
    path: 'checkout',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./pages/checkout/checkout.component').then((m) => m.CheckoutComponent),
  },
  {
    path: 'orders',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/orders/orders.component').then((m) => m.OrdersComponent),
  },
  {
    path: 'profile',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/profile/profile.component').then((m) => m.ProfileComponent),
  },
  {
    path: 'referral',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/referral/referral.component').then((m) => m.ReferralComponent),
  },
  { path: '**', redirectTo: 'home' },
];
