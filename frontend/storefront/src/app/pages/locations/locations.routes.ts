import { Routes } from '@angular/router';
import { LocationsComponent } from './locations.component';
import { LocationsAddComponent } from './locations-add/locations-add.component';
import { LocationsListComponent } from './locations-list/locations-list.component';
import { LocationsSaveComponent } from './locations-save/locations-save.component';

export const LOCATIONS_ROUTES: Routes = [
  {
    path: '',
    component: LocationsComponent,
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'list' },
      { path: 'list', component: LocationsListComponent },
      { path: 'add', component: LocationsAddComponent },
      { path: 'save', component: LocationsSaveComponent }
    ]
  }
];
