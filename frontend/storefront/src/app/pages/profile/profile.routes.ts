import { Routes } from '@angular/router';
import { ProfileComponent } from './profile.component';
import { ProfileFavoritesComponent } from './profile-favorites/profile-favorites';
import { ProfileLanguageComponent } from './profile-language/profile-language';
import { ProfileFaqComponent } from './profile-faq/profile-faq';
import { ProfileSupportComponent } from './profile-support/profile-support';
import { ProfileInviteComponent } from './profile-invite/profile-invite';
import { ProfileDetailsComponent } from './profile-details/profile-details';
import { ProfileTelegramComponent } from './profile-telegram/profile-telegram';
import { favouritesEnabledGuard } from '../../guards/features.guard';
import { authGuard } from '../../guards/auth.guard';

export const PROFILE_ROUTES: Routes = [
  {
    path: '',
    component: ProfileComponent
  },
  // Account-only: ProfileDetailsComponent reads and writes /me
  // unconditionally and has no guest state to fall back to (unlike
  // ProfileComponent itself, or profile-telegram's own needsSignIn prompt),
  // so an anonymous visit is refused at the route rather than left to 401.
  {
    path: 'details',
    component: ProfileDetailsComponent,
    canActivate: [authGuard]
  },
  // Gated the same way: favourites is a customer's own list at /me/favourites,
  // ownership-authorised and not on the public browse surface. The profile
  // menu already hides this link for a guest (ProfileMenuComponent's
  // authorizedOnly); this is what stops reaching it by a direct URL instead.
  {
    path: 'favorites',
    component: ProfileFavoritesComponent,
    canActivate: [authGuard, favouritesEnabledGuard]
  },
  {
    path: 'locations',
    redirectTo: '/locations/list',
    pathMatch: 'full'
  },
  {
    path: 'language',
    component: ProfileLanguageComponent
  },
  {
    path: 'faq',
    component: ProfileFaqComponent
  },
  {
    path: 'support',
    component: ProfileSupportComponent
  },
  {
    path: 'telegram',
    component: ProfileTelegramComponent
  },
  {
    path: 'invite',
    component: ProfileInviteComponent
  }
];
