import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Who did it — `q-actor-chip` (row `X.26`).
 *
 * `actorLabel(event) => event.actorDisplay ?? event.actorSubject ?? '—'` was
 * written three times: `staff/activity-log-page.ts`, `settings/data-privacy/
 * data-privacy-page.ts` and `catalog/product-editor-page.ts` (`historyActorLabel`).
 * Each also drew its own actor-type marker by hand
 * (`activity-log-page.html`'s `.actor-icon--{type}` span). This is both, once.
 *
 * **`kind` is a plain string, not the closed `ActorRef.Type` union.** A wire
 * value this client has not learned yet still renders — with the fallback
 * marker and the label it was given — rather than throwing away the one fact
 * (who) an unrecognised principal type does not change. The same
 * forward-compatibility rule `order-status.ts`'s `orderStatusLabel` and
 * `order-detail-pane.ts`'s `triggerLabel` already follow for an unknown wire
 * value.
 *
 * **No i18n inside this component.** `displayName` and `subject` arrive
 * already resolved (`AuditQueryService`'s read-time name resolution, `T08`) —
 * this chip only decides *which* of the two to show and how to mark the kind,
 * never how to say it.
 */
@Component({
  selector: 'q-actor-chip',
  templateUrl: './actor-chip.html',
  styleUrl: './actor-chip.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActorChip {
  /** `USER` | `SERVICE` | `SYSTEM_JOB` | `MIGRATION` on the wire (`ActorRef.Type`) — see this file's own doc comment for why it is typed loosely. */
  readonly kind = input.required<string>();
  /** A resolved human name, already translated/formatted. `null` when nothing resolved. */
  readonly displayName = input<string | null>(null);
  /** The raw principal id — a Keycloak subject, a job name, a migration run reference. `null` when even that is absent. */
  readonly subject = input<string | null>(null);
  /** Shown when neither {@link displayName} nor {@link subject} is present. Already translated. */
  readonly unknownLabel = input('—');

  protected readonly label = computed(
    () => this.displayName() ?? this.subject() ?? this.unknownLabel(),
  );
  protected readonly markerClass = computed(
    () => `q-actor-chip__marker--${this.kind().toLowerCase()}`,
  );
}
