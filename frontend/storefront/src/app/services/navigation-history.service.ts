import { Injectable, inject } from '@angular/core';
import { NavigationEnd, NavigationStart, Router } from '@angular/router';
import { filter } from 'rxjs/operators';

/**
 * Tracks in-app routes so Back can navigate to the previous screen
 * without using browser history (which can leave the app).
 */
@Injectable({ providedIn: 'root' })
export class NavigationHistoryService {
  private readonly router = inject(Router);
  private readonly stack: string[] = [];
  private replaceCurrent = false;

  constructor() {
    this.router.events.pipe(filter((e) => e instanceof NavigationStart)).subscribe(() => {
      this.replaceCurrent = !!this.router.getCurrentNavigation()?.extras.replaceUrl;
    });

    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe((e) => {
      const url = this.normalize(e.urlAfterRedirects);
      if (!url) return;

      if (this.replaceCurrent && this.stack.length > 0) {
        this.stack[this.stack.length - 1] = url;
        this.replaceCurrent = false;
        return;
      }

      const last = this.stack[this.stack.length - 1];
      if (last !== url) {
        this.stack.push(url);
      }
      this.replaceCurrent = false;
    });

    const initial = this.normalize(this.router.url);
    if (initial && this.stack[this.stack.length - 1] !== initial) {
      this.stack.push(initial);
    }
  }

  back(fallback = '/home'): void {
    const current = this.normalize(this.router.url);
    while (this.stack.length > 0 && this.stack[this.stack.length - 1] === current) {
      this.stack.pop();
    }

    const previous = this.stack[this.stack.length - 1];
    const target = previous && previous !== current ? previous : fallback;
    this.router.navigateByUrl(target).catch(() => {});
  }

  private normalize(url: string): string {
    const path = url.split('?')[0].split('#')[0];
    if (path.length > 1 && path.endsWith('/')) {
      return path.slice(0, -1);
    }
    return path || '/home';
  }
}
