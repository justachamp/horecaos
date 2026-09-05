import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { IconComponent } from '../icon/icon.component';
import { UiCartService } from '../../services/ui-cart.service';
import { TranslatePipe } from '../translate/translate.pipe';

/** The four destinations the design's flow moves between. */
const TABS = [
  { path: '/home', icon: 'ornament', label: 'nav.home' },
  { path: '/cart', icon: 'bag', label: 'nav.cart' },
  { path: '/orders', icon: 'clock', label: 'nav.orders' },
  { path: '/profile', icon: 'pin', label: 'nav.profile' },
] as const;

@Component({
  selector: 'app-bottom-nav',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, IconComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './bottom-nav.component.html',
  styleUrl: './bottom-nav.component.scss',
})
export class BottomNavComponent {
  protected readonly cart = inject(UiCartService);
  protected readonly tabs = TABS;
}
