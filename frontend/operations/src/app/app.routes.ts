import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';
import { NAV_ITEMS } from './shell/navigation';

/**
 * The routing table.
 *
 * Two real routes and eleven honest empty ones. The empty ones exist because
 * every rail entry must navigate somewhere — a rail item that does nothing when
 * clicked is a bug report — and because a placeholder that names its
 * specification is more useful to the next developer than a screen half-built
 * against it.
 *
 * Everything except the callback is behind {@link authGuard}. The guard proves
 * somebody is signed in; it never decides what they may do. Authorization is the
 * server's (ADR 0025).
 */
export const routes: Routes = [
  {
    path: 'auth/callback',
    // Outside the shell and outside the guard. Guarding the route that completes
    // the login is a redirect loop.
    loadComponent: () =>
      import('./features/auth-callback/auth-callback-page').then((m) => m.AuthCallbackPage),
  },
  {
    path: '',
    loadComponent: () => import('./shell/shell').then((m) => m.Shell),
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'today' },
      {
        path: 'today',
        loadComponent: () => import('./features/today/today-page').then((m) => m.TodayPage),
      },
      {
        path: 'orders',
        loadComponent: () => import('./features/orders/orders-page').then((m) => m.OrdersPage),
        children: [
          // Taking an order docks beside the queue like any other detail, which
          // is the point: an operator building a basket must still see 4819 go
          // late. Declared before `:orderId` so that "new" is a destination and
          // not an order whose id happens to be the word new.
          {
            path: 'new',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: { spec: 'operations-spec/orders.md §5 (New order)' },
          },
          // The detail is a *child* of the board, not a sibling. That is what
          // makes it dock beside the queue instead of replacing it, and what
          // keeps it deep-linkable and back-button-correct at the same time.
          {
            path: ':orderId',
            loadComponent: () =>
              import('./features/orders/order-detail-pane').then((m) => m.OrderDetailPane),
          },
        ],
      },
      {
        path: 'inbox',
        loadComponent: () => import('./features/inbox/inbox-page').then((m) => m.InboxPage),
        children: [
          // Docks beside the list rather than replacing it — same reasoning
          // as the order board's own detail child route.
          {
            path: ':conversationId',
            loadComponent: () =>
              import('./features/inbox/inbox-detail-pane').then((m) => m.InboxDetailPane),
          },
        ],
      },
      ...placeholderRoutes(),
      // Unknown paths land on Today rather than on a 404. There is no such thing
      // as a deleted page in this console — only one that has not been built —
      // so a 404 would be a lie about what happened.
      { path: '**', redirectTo: 'today' },
    ],
  },
];

/**
 * One placeholder per unbuilt rail entry, derived from the navigation model so
 * the two cannot drift. Adding a rail item without a route is then impossible.
 */
function placeholderRoutes(): Routes {
  const built = new Set(['/today', '/orders', '/inbox']);
  return NAV_ITEMS.filter((item) => !built.has(item.path)).map((item) => ({
    path: item.path.slice(1),
    loadComponent: () => import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
    data: { spec: item.spec ?? 'docs/operations-spec/' },
  }));
}
