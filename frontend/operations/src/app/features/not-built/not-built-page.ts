import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The honest empty route.
 *
 * Every rail entry navigates somewhere, because a rail item that does nothing
 * when clicked is a bug report. What it navigates to says plainly that the
 * section is not built and names the specification that owns it, so the next
 * person starts on a screen instead of on archaeology.
 *
 * This is the Togora rule the operations spec adopts, applied to routes:
 * **omit, do not disable.** A greyed-out rail item teaches an operator that grey
 * means "try again later"; a page that says what is missing teaches a developer
 * where to look.
 */
@Component({
  selector: 'q-not-built-page',
  imports: [TPipe],
  template: `
    <div class="not-built">
      <h1 class="q-subhead">{{ 'notBuilt.title' | t }}</h1>
      <p class="q-body-sm">{{ 'notBuilt.body' | t: { spec: spec() } }}</p>
    </div>
  `,
  styles: `
    .not-built {
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
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotBuiltPage {
  /** Supplied by the route's `data`, bound by `withComponentInputBinding()`. */
  readonly spec = input<string>('docs/operations-spec/');
}
