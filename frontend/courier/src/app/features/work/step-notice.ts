import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import type { StepMessage } from '../../core/deliveries/step-message';

/**
 * A sentence under a button: what became of the tap. Errors are announced assertively, anything
 * else politely, and a screen reader hears it where it appears rather than having to look for it.
 */
@Component({
  selector: 'app-step-notice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (message(); as m) {
      <p
        class="notice"
        [class.warning]="m.tone === 'warning'"
        [class.error]="m.tone === 'error'"
        [attr.role]="m.tone === 'error' ? 'alert' : 'status'"
      >
        {{ m.text }}
      </p>
    }
  `,
})
export class StepNotice {
  readonly message = input<StepMessage | null>(null);
}
