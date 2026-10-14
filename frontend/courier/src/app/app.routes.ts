import type { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';
import { messagesGuard } from './core/i18n/messages.guard';

/**
 * The route table. `/auth/*` is public by nature -- nobody signing in has a session yet -- and
 * everything else sits behind {@link authGuard}.
 *
 * Declare the message areas a screen shows on the route that brings it in (`messagesGuard`), and
 * `title` as a message key. Every screen is a lazy chunk.
 */
export const routes: Routes = [
  {
    path: 'auth/callback',
    loadComponent: () => import('./features/auth/callback').then((m) => m.CallbackPage),
  },
  {
    path: 'auth/signed-out',
    title: 'auth.signedOut.title',
    loadComponent: () => import('./features/auth/signed-out').then((m) => m.SignedOutPage),
  },
  {
    path: 'auth/error',
    title: 'auth.error.title',
    loadComponent: () => import('./features/auth/sign-in-error').then((m) => m.SignInErrorPage),
  },
  {
    path: '',
    canActivate: [authGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'home.title',
        canActivate: [messagesGuard('home')],
        loadComponent: () => import('./features/home/home').then((m) => m.HomePage),
      },
      {
        path: 'profile',
        title: 'profile.title',
        canActivate: [messagesGuard('profile')],
        loadComponent: () => import('./features/profile/profile').then((m) => m.ProfilePage),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
