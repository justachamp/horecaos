import { Component, inject, OnInit, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { UiCartService } from '../../services/ui-cart.service';
import { Session } from '../../core/auth/session';
import { TranslatePipe } from '../translate/translate.pipe';

@Component({
  selector: 'app-bottom-nav',
  standalone: true,
  imports: [RouterLink, TranslatePipe],
  templateUrl: './bottom-nav.component.html',
  styleUrl: './bottom-nav.component.scss'
})
export class BottomNavComponent implements OnInit {
  readonly cart = inject(UiCartService);
  private readonly session = inject(Session);
  private readonly router = inject(Router);

  readonly activeTab = signal<string | null>(this.getTabFromUrl(this.router.url));

  constructor() {
    this.router.events.subscribe((event) => {
      if (event instanceof NavigationEnd) {
        this.activeTab.set(this.getTabFromUrl(event.urlAfterRedirects));
      }
    });
  }

  ngOnInit(): void {
    if (!this.session.isAuthenticated()) {
      return;
    }
    if (!this.cart.cartData()) {
      void this.cart.load();
    }
  }

  isTabActive(tab: string): boolean {
    return this.activeTab() === tab;
  }

  private getTabFromUrl(url: string): string | null {
    if (url.startsWith('/home')) return 'home';
    if (url.startsWith('/cart')) return 'cart';
    if (url.startsWith('/orders')) return 'orders';
    if (url.startsWith('/profile')) return 'profile';
    return null;
  }
}
