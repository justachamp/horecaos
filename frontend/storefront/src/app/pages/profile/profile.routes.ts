import { Routes } from '@angular/router';
import { ProfileComponent } from './profile.component';
import { ProfileFavoritesComponent } from './profile-favorites/profile-favorites';
import { ProfileLanguageComponent } from './profile-language/profile-language';
import { ProfileFaqComponent } from './profile-faq/profile-faq';
import { ProfileSupportComponent } from './profile-support/profile-support';
import { ProfileInviteComponent } from './profile-invite/profile-invite';
import { ProfileDetailsComponent } from './profile-details/profile-details';

export const PROFILE_ROUTES: Routes = [
  {
    path: '',
    component: ProfileComponent
  },
  {
    path: 'details',
    component: ProfileDetailsComponent
  },
  {
    path: 'favorites',
    component: ProfileFavoritesComponent
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
    path: 'invite',
    component: ProfileInviteComponent
  }
];
