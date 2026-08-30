import { Component, OnInit, inject, viewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs/operators';
import { BottomNavComponent } from './shared/bottom-nav/bottom-nav.component';
import { NotificationToastComponent } from './shared/notification-toast/notification-toast.component';
import { TelegramMiniAppHeaderComponent } from './shared/telegram-mini-app-header/telegram-mini-app-header.component';
import { TelegramWebappService } from './services/telegram-webapp.service';
import { TranslateService } from './services/translate.service';
import { NavigationHistoryService } from './services/navigation-history.service';

@Component({
  selector: 'app-root',
  imports: [
    CommonModule,
    RouterOutlet,
    BottomNavComponent,
    NotificationToastComponent,
    TelegramMiniAppHeaderComponent,
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App implements OnInit {
  showBottomNav: boolean;

  protected readonly scrollContainer = viewChild<ElementRef<HTMLElement>>('scrollContainer');

  private readonly router = inject(Router);
  private readonly telegramWebapp = inject(TelegramWebappService);
  private readonly translate = inject(TranslateService);
  private readonly navigationHistory = inject(NavigationHistoryService);

  /** True when launched from Telegram Mini App */
  protected readonly isTelegram = () => this.telegramWebapp.isTelegram;

  private static shouldShowBottomNav(url: string): boolean {
    return !url.startsWith('/product') && !url.startsWith('/auth') && !url.startsWith('/terms') && !url.startsWith('/search');
  }

  private static shouldResetScrollOnNavigate(url: string): boolean {
    return /^\/(home|cart|orders|profile|product|search)(\/|$)/.test(url);
  }

  constructor() {
    this.showBottomNav = App.shouldShowBottomNav(this.router.url);
    this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe(() => {
        this.showBottomNav = App.shouldShowBottomNav(this.router.url);
        if (App.shouldResetScrollOnNavigate(this.router.url) && !this.shouldKeepHomeMenuScroll()) {
          this.scrollToTop();
        }
      });
  }

  ngOnInit(): void {
    this.telegramWebapp.setupSwipeBehavior(false);
    if (this.telegramWebapp.isTelegram) {
      this.telegramWebapp.requestFullscreen();
    }
  }

  private shouldKeepHomeMenuScroll(): boolean {
    const state = this.router.lastSuccessfulNavigation()?.extras.state as
      | { scrollToMenyu?: boolean }
      | undefined;
    return this.router.url.startsWith('/home') && !!state?.scrollToMenyu;
  }

  private scrollToTop(): void {
    setTimeout(() => {
      const el = this.scrollContainer()?.nativeElement;
      if (el) {
        el.scrollTop = 0;
      }
      window.scrollTo(0, 0);
    });
  }
}
