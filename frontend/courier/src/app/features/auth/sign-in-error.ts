import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';
import type { OidcErrorCode } from '../../core/auth/oidc-client';
import { I18n } from '../../core/i18n/i18n';
import type { MessageKey } from '../../core/i18n/message-keys';
import { TPipe } from '../../core/i18n/t.pipe';

/** Which sentence a sign-in failure gets. The detail of a failure is never shown; the code picks a line. */
const MESSAGE_BY_CODE: Readonly<Record<OidcErrorCode, MessageKey>> = {
  access_denied: 'auth.error.access_denied',
  no_pending_login: 'auth.error.expired',
  login_expired: 'auth.error.expired',
  state_mismatch: 'auth.error.expired',
  storage_unavailable: 'auth.error.storage_unavailable',
  discovery_failed: 'auth.error.unavailable',
  issuer_mismatch: 'auth.error.unavailable',
  provider_error: 'auth.error.default',
  token_exchange_failed: 'auth.error.default',
  token_invalid: 'auth.error.default',
  refresh_failed: 'auth.error.default',
  refresh_unavailable: 'auth.error.unavailable',
};

/** `/auth/error?code=...`: a sign-in that did not complete, with the one thing a courier can do about it. */
@Component({
  selector: 'app-sign-in-error',
  imports: [TPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './sign-in-error.html',
  styleUrl: './auth-pages.css',
})
export class SignInErrorPage {
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18n);

  /** Bound from the `code` query parameter (`withComponentInputBinding`). */
  readonly code = input<string | undefined>();

  protected readonly message = computed(() => {
    // The code comes from the address bar, so it is looked up only as one of our own keys.
    const code = this.code();
    const key =
      code !== undefined && Object.hasOwn(MESSAGE_BY_CODE, code)
        ? MESSAGE_BY_CODE[code as OidcErrorCode]
        : 'auth.error.default';
    return this.i18n.t(key);
  });

  protected retry(): void {
    void this.auth.beginSignIn('/').catch(() => undefined);
  }
}
