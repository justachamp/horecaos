import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';
import { TPipe } from '../../core/i18n/t.pipe';

/** `/auth/signed-out`: where the identity provider sends the browser after a sign-out. */
@Component({
  selector: 'app-signed-out',
  imports: [TPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './signed-out.html',
  styleUrl: './auth-pages.css',
})
export class SignedOutPage {
  private readonly auth = inject(AuthService);

  protected signIn(): void {
    void this.auth.beginSignIn('/').catch(() => undefined);
  }
}
