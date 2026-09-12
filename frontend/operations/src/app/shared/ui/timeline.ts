import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { ActorChip } from './actor-chip';

export type TimelineTone = 'default' | 'info' | 'success' | 'warning' | 'danger';

/** {@link ActorChip}'s own three inputs, bundled — see that component's doc comment. */
export interface TimelineActor {
  readonly kind: string;
  readonly displayName: string | null;
  readonly subject: string | null;
}

/** A short translated word beside the entry — an audit outcome, a lifecycle badge. */
export interface TimelineBadge {
  readonly label: string;
  readonly tone: TimelineTone;
}

/**
 * One row. Every field the caller already has translated or formatted —
 * this component composes them, it does not look anything up (the same rule
 * `q-action-menu`'s `ActionMenuItem.label` states for itself).
 */
export interface TimelineEntry {
  readonly id: string;
  /** Already formatted (`core/format/datetime`'s job, not this component's). */
  readonly timestamp: string;
  /** Omitted for a pure system fact nobody caused. */
  readonly actor?: TimelineActor | null;
  readonly title: string;
  /** One optional line under the title. */
  readonly detail?: string | null;
  readonly badge?: TimelineBadge | null;
  /**
   * A gap notice rendered as its own row immediately before this entry —
   * §3.10's "if the sequence has a gap the panel says «пропущена запись N»,
   * because hiding it hides a bug." `null`/absent when the sequence is
   * continuous.
   */
  readonly gapBefore?: string | null;
  /** Whether clicking the row emits {@link Timeline.select}. Static rows (the order lifecycle lane) leave this `false`. */
  readonly selectable?: boolean;
}

/**
 * A chronological list of things that happened to something, with who did
 * them (`q-actor-chip`) — `q-timeline` (row `X.26`).
 *
 * **One idea, two screens.** The order detail's §3.10 lanes and the staff
 * activity log's event list were two hand-rolled markups over the same
 * shape: a time, an actor, a headline, one line of detail, sometimes a
 * gap or an outcome badge. Extracted once; each screen maps its own response
 * onto {@link TimelineEntry} and keeps whatever is genuinely its own —
 * the order pane still computes `missingSequenceBefore` and `triggerLabel`,
 * the activity log still owns its expandable diff drawer underneath the
 * selected row. What moves here is only the row itself.
 *
 * **Selection is the caller's, not this component's**, matching
 * `q-action-menu`'s own `open`/`openChange` split: `selectedId` is an input,
 * `select` an output, and a row with `selectable: false` never emits.
 */
@Component({
  selector: 'q-timeline',
  imports: [ActorChip],
  templateUrl: './timeline.html',
  styleUrl: './timeline.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Timeline {
  readonly entries = input.required<readonly TimelineEntry[]>();
  /** Already translated. Shown in place of the list when {@link entries} is empty. */
  readonly emptyLabel = input<string | null>(null);
  readonly selectedId = input<string | null>(null);

  readonly select = output<string>();

  protected onEntryClick(entry: TimelineEntry): void {
    if (entry.selectable) {
      this.select.emit(entry.id);
    }
  }
}
