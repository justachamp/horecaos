import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs/operators';
import { OrderReloadComponent } from './order-reload/order-reload.component';
import { TelegramWebappService } from '../../services/telegram-webapp.service';
import { TranslatePipe } from '../../shared/translate/translate.pipe';

const ACTIVE_STATUSES = ['new', 'accepted', 'cooking', 'ready', 'delivering'];
const FINISHED_STATUSES = ['completed'];
const CANCELLED_STATUSES = ['cancelled'];

@Component({
  selector: 'app-orders',
  standalone: true,
  templateUrl: './orders.component.html',
  styleUrl: './orders.component.scss',
  imports: [CommonModule, RouterLink, RouterLinkActive, RouterOutlet, OrderReloadComponent, TranslatePipe],
})
export class OrdersComponent implements OnInit {
  private readonly router = inject(Router);
  private readonly urlSignal = signal('');
  readonly telegramWebapp = inject(TelegramWebappService);

  tabs = [
    { labelKey: 'orders.active', path: 'active' },
    { labelKey: 'orders.finished', path: 'finished' },
    { labelKey: 'orders.cancelled', path: 'cancelled' },
  ];

  /** Statuses for current tab (used by order-reload) */
  readonly currentStatuses = computed(() => {
    const url = this.urlSignal();
    if (url.includes('/orders/finished')) return FINISHED_STATUSES;
    if (url.includes('/orders/cancelled')) return CANCELLED_STATUSES;
    return ACTIVE_STATUSES;
  });

  ngOnInit(): void {
    this.urlSignal.set(this.router.url);
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe((e) => this.urlSignal.set(e.url));
  }
}
