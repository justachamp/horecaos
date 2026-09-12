import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * The pill's own tone. `none` is the default grey every unremarkable status
 * wears; the rest come from the generated tint/text token pairs.
 */
export type StatusTone = 'none' | 'info' | 'success' | 'warning' | 'danger';

/**
 * A status word, and — separately — whether the thing is late (ADR 0101, row `X.15`).
 *
 * **Lateness is an overlay, not a state, and this component is where that stops
 * being a slogan.** The IA says it in one line — "Lateness must not be modelled
 * as a state" — and this console has already paid for getting it right
 * elsewhere: `order-severity.ts` computes severity per render and refuses to
 * store it, because "a stored flag is wrong five seconds after it is written".
 * What was missing was the rendering half. On the order board today the status
 * word and the severity rail are two unrelated visual systems on opposite sides
 * of the row, so an operator correlates them by eye; on the kitchen board the
 * same fact is a third thing again.
 *
 * So: `label` renders unchanged whatever the overlay says. A `READY` order that
 * is forty-six minutes old reads **READY**, with a lateness marker beside it —
 * not `LATE`, which would destroy the one fact the operator needs (the food is
 * ready; it is the handover that is late).
 *
 * **The dual-state variant is a second segment, not a second pill.** IA 1.1/2.1
 * wants the commercial status and the cooking status legible together, "so an
 * operator has to open the kitchen screen" no longer. `secondaryLabel` is that
 * segment, with its own tone, inside the same border — one object to read
 * rather than two to correlate.
 *
 * **One accessible name for the whole pill.** Three adjacent `<span>`s are
 * three fragments to a screen reader, announced in whatever order the reader
 * chooses, with no indication that the third qualifies the first. `aria-label`
 * composes them into one sentence and the fragments are hidden.
 */
@Component({
  selector: 'q-status-pill',
  templateUrl: './status-pill.html',
  styleUrl: './status-pill.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusPill {
  /** The status word itself, already translated. Never altered by the overlay. */
  readonly label = input.required<string>();
  readonly tone = input<StatusTone>('none');

  /**
   * The dual-state pill's second segment — the cooking status beside the
   * commercial one. `null` on a single-state pill.
   */
  readonly secondaryLabel = input<string | null>(null);
  readonly secondaryTone = input<StatusTone>('none');

  /**
   * The lateness overlay, already translated — «опаздывает», or a countdown.
   * Independent of {@link label} and of {@link tone}: it may be set on any
   * status, including one whose own tone is `success`.
   */
  readonly overlayLabel = input<string | null>(null);
  readonly overlayTone = input<StatusTone>('danger');

  /**
   * The whole pill as one sentence, in reading order: status, then cooking
   * state, then lateness. Composed rather than left to the reader to assemble.
   */
  protected readonly accessibleName = computed(() =>
    [this.label(), this.secondaryLabel(), this.overlayLabel()]
      .filter((part): part is string => part !== null && part !== '')
      .join(' · '),
  );
}
