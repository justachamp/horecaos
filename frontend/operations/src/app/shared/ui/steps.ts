import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type StepState = 'complete' | 'current' | 'upcoming';
export type StepTone = 'default' | 'danger';

/** Already translated — this component never looks a key up (`q-action-menu`'s own rule for itself). */
export interface StepItem {
  readonly id: string;
  readonly label: string;
  readonly state: StepState;
  /** `danger` marks a step that is `current` because the flow stopped there, not because it advanced there. */
  readonly tone?: StepTone;
}

/**
 * A position in a known sequence, rendered — `q-steps` (row `X.33`).
 *
 * **Two consumers, two very different sequences, one component.** The
 * connect-provider drawer's own two steps (`settings/integrations/
 * connect-provider-panel.ts`) are static and short-lived — connect, then
 * bind; the order lifecycle rail derives a position from
 * `OrderDetailPane`'s own §3.10 timeline read and can also stop early, at a
 * rejection or a cancellation, which {@link StepItem.tone} exists for. This
 * component knows neither story — it renders whichever list of
 * already-decided states it is handed, in order, with a connecting rail.
 *
 * **Not a wizard.** There is no "next" here, no validation, no output. A
 * caller that needs the operator to move forward keeps that entirely to
 * itself, the same split `q-status-pill` draws between the status word and
 * the lateness overlay: this component decides how a position looks, never
 * what produced it.
 */
@Component({
  selector: 'q-steps',
  templateUrl: './steps.html',
  styleUrl: './steps.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Steps {
  readonly steps = input.required<readonly StepItem[]>();
  /** The whole rail's accessible name — "Connect a provider, step 2 of 2". Already translated. */
  readonly ariaLabel = input<string | null>(null);
}
