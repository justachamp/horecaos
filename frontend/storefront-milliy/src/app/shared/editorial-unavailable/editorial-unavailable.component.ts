import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { TranslatePipe } from '../translate/translate.pipe';

/**
 * What an editorial page shows when there is nothing to show (ADR 0157, Decision 7): a 404, a kind
 * the brand has switched off, a slug that was never published. They are one answer on purpose --
 * nothing can be learned by probing -- and this is a plain, quiet page, not an error screen: no
 * alert, no retry, nothing is broken. It leads home.
 */
@Component({
  selector: 'app-editorial-unavailable',
  standalone: true,
  imports: [RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
    }
    .unavailable {
      padding: 48px 24px;
      text-align: center;
    }
    .unavailable__title {
      margin: 0 0 8px;
      font: 800 22px/1.2 var(--font-display);
    }
    .unavailable__body {
      margin: 0 0 20px;
      color: var(--text-secondary);
      font: 400 14px/1.5 var(--font-ui);
    }
    .unavailable__home {
      display: inline-block;
      padding: 12px 22px;
      border-radius: var(--radius-pill);
      background: var(--brand-accent);
      color: var(--text-on-accent);
      font: 700 14px/1 var(--font-display);
    }
  `,
  template: `
    <section class="unavailable" data-testid="editorial-unavailable">
      <h1 class="unavailable__title">{{ 'editorial.unavailableTitle' | t }}</h1>
      <p class="unavailable__body">{{ 'editorial.unavailableBody' | t }}</p>
      <a class="unavailable__home" routerLink="/home">{{ 'home.goHome' | t }}</a>
    </section>
  `,
})
export class EditorialUnavailableComponent {}
