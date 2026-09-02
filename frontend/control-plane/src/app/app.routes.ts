import { Routes } from '@angular/router';

import { authGuard, requiresCapability } from './core/auth/guards';
import { ConsoleShell } from './layout/console-shell';
import { Overview } from './features/overview/overview';

/**
 * Two real sections, and the one state that is not a section.
 *
 * `/login` sits outside the shell and outside {@link authGuard} for the same
 * reason it exists at all (ADR 0062): guarding the page that signs somebody
 * in would refuse to render it to exactly the visitor it is for.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/sign-in-page').then((m) => m.SignInPage),
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
        path: 'integrations',
        canActivate: [requiresCapability('INTEGRATION_INSTALLATION_MANAGE')],
        loadComponent: () => import('./features/integrations/integrations').then((m) => m.Integrations),
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
