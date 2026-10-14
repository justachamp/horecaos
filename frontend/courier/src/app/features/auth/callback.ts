import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { OidcError } from '../../core/auth/oidc-client';
import { TPipe } from '../../core/i18n/t.pipe';

/**
 * `/auth/callback`: where the identity provider sends the browser back to.
 *
 * Redeems the code, then replaces this URL with the screen the courier was heading for -- replaces,
 * so the single-use code never sits in the history. A failure goes to the error screen with its
 * code; this page never starts another sign-in on its own, because a callback that fails and
 * retries by itself is a redirect loop.
 */
@Component({
  selector: 'app-auth-callback',
  imports: [TPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p class="working" role="status">{{ 'auth.callback.working' | t }}</p>`,
  styles: `
    .working {
      padding: 32px 16px;
      text-align: center;
      color: var(--q-ink-muted);
    }
  `,
})
export class CallbackPage implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  async ngOnInit(): Promise<void> {
    const query = new URLSearchParams();
    for (const [name, value] of Object.entries(this.route.snapshot.queryParams)) {
      if (typeof value === 'string') {
        query.set(name, value);
      }
    }
    try {
      const destination = await this.auth.completeSignIn(query);
      await this.router.navigateByUrl(destination, { replaceUrl: true });
    } catch (failure) {
      const code = failure instanceof OidcError ? failure.code : 'provider_error';
      await this.router.navigate(['/auth/error'], { queryParams: { code }, replaceUrl: true });
    }
  }
}
