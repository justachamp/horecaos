import { Routes } from '@angular/router';

import { authGuard, requiresCapability } from './core/auth/guards';
import { ConsoleShell } from './layout/console-shell';
import { Overview } from './features/overview/overview';

/**
 * Two real sections, and the two states that are not sections.
 *
 * `/unavailable` sits outside the shell and outside the auth guard, because it
 * exists precisely for the case where authentication cannot happen; guarding
 * it would send a visitor into a redirect loop against a realm that is down.
 */
export const routes: Routes = [
  {
    path: 'unavailable',
    loadComponent: () =>
      import('./features/states/sign-in-unavailable').then((m) => m.SignInUnavailable),
  },
  {
    path: '',
    component: ConsoleShell,
    canActivate: [authGuard],
    children: [
      { path: '', component: Overview },
      {
        path: 'tenants',
        canActivate: [requiresCapability('TENANT_READ')],
        loadComponent: () => import('./features/tenants/tenants').then((m) => m.Tenants),
      },
      {
        path: 'denied',
        loadComponent: () => import('./features/states/access-denied').then((m) => m.AccessDenied),
      },
      // An unknown path inside the console goes to the overview rather than to
      // a 404 screen: every URL here is one the console itself produced, so a
      // miss means a stale bookmark, and the overview is where that person
      // wanted to start anyway.
      { path: '**', redirectTo: '' },
    ],
  },
];
