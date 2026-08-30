import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { RETURN_TO_KEY } from '../../core/auth/auth.guard';
import { TPipe } from '../../core/i18n/t.pipe';

/**
 * Where Keycloak sends the operator back to.
 *
 * The redirect URI is a dedicated route rather than the application root for one
 * reason: the URI is allowlisted *exactly* on the Keycloak client, and an exact
 * allowlist is only workable when the value is a single fixed path. Allowlisting
 * the root and matching every route under it means allowlisting a wildcard,
 * which is the open-redirect footgun ADR 0003 names.
 *
 * This route performs the code exchange itself rather than leaving it to an
 * application initializer. Doing it in an initializer makes Angular build
 * `OidcSecurityService` while the router's own initializer is running, and the
 * two deadlock as `NG0200: Circular dependency detected` — see the note in
 * `auth.providers.ts`. Doing it here also means the exchange happens exactly
 * where it is relevant and nowhere else.
 */
@Component({
  selector: 'q-auth-callback-page',
  imports: [TPipe],
  template: `
    <div class="callback">
      @if (failed()) {
        <h1 class="q-subhead">{{ 'auth.failed' | t }}</h1>
        <p class="q-body-sm">{{ 'auth.failed.detail' | t }}</p>
        <button type="button" class="retry q-body-sm" (click)="retry()">
          {{ 'auth.retry' | t }}
        </button>
      } @else {
        <h1 class="q-subhead">{{ 'auth.signingIn' | t }}</h1>
        <p class="q-body-sm">{{ 'auth.signingIn.detail' | t }}</p>
      }
    </div>
  `,
  styles: `
    .callback {
      padding: 24px;
      max-width: 60ch;
    }
    h1 {
      margin: 0 0 8px;
    }
    p {
      margin: 0;
      color: var(--q-ink-muted);
    }
    .retry {
      margin-top: 16px;
      height: 32px;
      padding: 0 12px;
      background: var(--q-primary);
      color: var(--q-inverse-ink);
      border: none;
      border-radius: var(--q-radius);
      cursor: pointer;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthCallbackPage {
  private readonly oidc = inject(OidcSecurityService);
  private readonly router = inject(Router);

  protected readonly failed = signal(false);

  constructor() {
    this.oidc.checkAuth().subscribe({
      next: ({ isAuthenticated }) => {
        if (!isAuthenticated) {
          // Reached without a usable response: a stale bookmark of the callback
          // URL, a `state` mismatch, or a code that was already redeemed. Saying
          // so beats an immediate silent re-redirect, which loops forever when
          // the cause is a misconfigured client.
          this.failed.set(true);
          return;
        }
        void this.router.navigateByUrl(takeReturnTo());
      },
      error: () => this.failed.set(true),
    });
  }

  protected retry(): void {
    this.failed.set(false);
    this.oidc.authorize();
  }
}

/** Reads and clears the deep link the guard saved before redirecting. */
function takeReturnTo(): string {
  let target = '/today';
  try {
    const saved = globalThis.sessionStorage?.getItem(RETURN_TO_KEY);
    // Only same-document paths. A stored absolute URL would make this an
    // open redirect operated by whatever could write to sessionStorage.
    if (saved && saved.startsWith('/') && !saved.startsWith('//')) {
      target = saved;
    }
    globalThis.sessionStorage?.removeItem(RETURN_TO_KEY);
  } catch {
    // No storage: land on the default.
  }
  return target;
}
