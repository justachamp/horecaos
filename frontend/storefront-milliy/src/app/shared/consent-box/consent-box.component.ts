import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The box that lets the words of a review appear on the restaurant's page, without the customer's
 * name (ADR 0159, Decision 6).
 *
 * It is never ticked for the customer: {@link checked} is whatever the page says, and the page starts
 * it unticked. The sentence beside it says exactly what is shown and what is not, and that it can be
 * taken back, because a consent that does not say what it consents to is not one.
 */
@Component({
  selector: 'app-consent-box',
  standalone: true,
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <label class="consent" data-testid="public-consent">
      <input
        type="checkbox"
        [checked]="checked()"
        (change)="changed.emit($any($event.target).checked)"
      />
      <span class="consent__text">
        <span class="consent__label">{{ 'profile.publicConsent' | t }}</span>
        <span class="consent__hint">{{ 'profile.publicConsentHint' | t }}</span>
      </span>
    </label>
  `,
  styleUrl: './consent-box.component.scss',
})
export class ConsentBoxComponent {
  readonly checked = input(false);

  readonly changed = output<boolean>();
}
