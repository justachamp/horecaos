import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * Keycloak did not answer.
 *
 * Rendered outside the shell, because there is no session and a rail full of
 * sections nobody can open reads as a permissions problem rather than an
 * outage. The distinction matters to whoever is about to telephone support.
 */
@Component({
  selector: 'app-sign-in-unavailable',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="state">
      <h1 class="q-title">{{ i18n.t('state.unavailable.title') }}</h1>
      <p class="q-body-sm body">{{ i18n.t('state.unavailable.body') }}</p>
      <button class="q-body-sm retry" type="button" (click)="retry()">
        {{ i18n.t('state.unavailable.retry') }}
      </button>
    </section>
  `,
  styles: `
    :host {
      display: block;
      padding: 48px 24px;
    }

    .state {
      max-width: 560px;
      margin: 0 auto;
    }

    .body {
      color: var(--q-ink-muted);
      margin-top: 8px;
    }

    .retry {
      margin-top: 24px;
      height: 40px;
      padding: 0 16px;
      border: none;
      border-radius: var(--q-radius);
      background: var(--q-primary);
      color: var(--q-inverse-ink);
      cursor: pointer;
    }
  `,
})
export class SignInUnavailable {
  protected readonly i18n = inject(I18nService);

  /**
   * A full reload rather than a retry of the discovery call. Discovery runs
   * once during bootstrap and the application initialiser has already
   * finished; reloading is the honest way to run it again.
   */
  protected retry(): void {
    location.reload();
  }
}
