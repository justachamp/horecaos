import { Routes } from '@angular/router';
import { authGuard } from './guards/auth.guard';
import { HomeComponent } from './pages/home/home.component';

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
    loadComponent: () => import('./pages/auth/auth.component').then((m) => m.AuthComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'login' },
      {
        path: 'login',
        loadComponent: () =>
          import('./pages/auth/auth-login/auth-login.component').then((m) => m.AuthLoginComponent),
      },
      {
        path: 'code',
        loadComponent: () =>
          import('./pages/auth/auth-code/auth-code.component').then((m) => m.AuthCodeComponent),
      },
    ],
  },
  // Gated: this is the customer's own saved-address book (/me/addresses),
  // not the branch-discovery surface -- unrelated to the public
  // pickup-locations endpoint and personal data either way.
  {
    path: 'locations',
    loadComponent: () =>
      import('./pages/locations/locations.component').then((m) => m.LocationsComponent),
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'list' },
      {
        path: 'list',
        loadComponent: () =>
          import('./pages/locations/locations-list/locations-list.component').then(
            (m) => m.LocationsListComponent,
          ),
      },
      {
        path: 'add',
        loadComponent: () =>
          import('./pages/locations/locations-add/locations-add.component').then(
            (m) => m.LocationsAddComponent,
          ),
      },
      {
        path: 'save',
        loadComponent: () =>
          import('./pages/locations/locations-save/locations-save.component').then(
            (m) => m.LocationsSaveComponent,
          ),
      },
      {
        path: 'permission',
        loadComponent: () =>
          import('./pages/locations/locations-permission/locations-permission.component').then(
            (m) => m.LocationsPermissionComponent,
          ),
      },
    ],
  },
  // Gated: a customer's own order history, ownership-authorised the same
  // way /me is.
  {
    path: 'orders',
    loadComponent: () => import('./pages/orders/orders.component').then((m) => m.OrdersComponent),
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'active' },
      {
        path: 'active',
        loadComponent: () =>
          import('./pages/orders/active-order/active-order.component').then(
            (m) => m.ActiveOrderComponent,
          ),
      },
      {
        path: 'finished',
        loadComponent: () =>
          import('./pages/orders/finished-order/finished-order.component').then(
            (m) => m.FinishedOrderComponent,
          ),
      },
      {
        path: 'cancelled',
        loadComponent: () =>
          import('./pages/orders/cancelled-order/cancelled-order.component').then(
            (m) => m.CancelledOrderComponent,
          ),
      },
      {
        path: 'detail/:id',
        loadComponent: () =>
          import('./shared/order-detail/order-detail.component').then(
            (m) => m.OrderDetailComponent,
          ),
      },
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
  {
    path: 'category',
    loadComponent: () =>
      import('./pages/category-items/category-items.component').then(
        (m) => m.CategoryItemsComponent,
      ),
  },
  // Client-side search over the already-loaded menu (MenuService.search) --
  // no platform endpoint of its own, so nothing here needs a session either.
  {
    path: 'search',
    loadComponent: () => import('./pages/search/search.component').then((m) => m.SearchComponent),
  },
  {
    path: 'product/:id',
    loadComponent: () =>
      import('./pages/product/product.component').then((m) => m.ProductComponent),
  },
  {
    path: 'terms',
    loadComponent: () =>
      import('./pages/terms/terms-of-conditions.component').then(
        (m) => m.TermsOfConditionsComponent,
      ),
  },
  // Row 10.5: a channel's own static pages (about/contacts/delivery-terms/
  // privacy-offer), published from the operations console's channel setup
  // hub. An unrecognised slug renders the same not-found state as one the
  // channel has never published -- see ChannelPageComponent's own doc.
  {
    path: 'pages/:slug',
    loadComponent: () =>
      import('./pages/channel-page/channel-page.component').then((m) => m.ChannelPageComponent),
  },
  // Row 10.5's dine-in facet (ADR 0047): the table-QR flow. `:tableToken` is
  // the one-time value a table's printed code encodes -- DineInScanComponent
  // spends it once, against QrEntryController.exchange, and replaces the URL
  // with the token-free `dine-in` below before anything else runs, so the
  // printed token never sits in history past that single request (see that
  // component's own doc). Public: a guest holds no session at all yet, the
  // same pre-account standing as the menu routes above.
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
  { path: '**', redirectTo: 'home' },
];
