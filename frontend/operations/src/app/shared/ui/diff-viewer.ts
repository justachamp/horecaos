import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** One field's change, resolved from whichever shape the change document carried. */
export interface DiffFieldEntry {
  readonly field: string;
  /** `undefined` for a flat, after-only fact — see this file's own doc comment. */
  readonly before: unknown;
  readonly after: unknown;
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  return typeof value === 'string' ? value : JSON.stringify(value);
}

/**
 * A per-field before/after change document, rendered — `q-diff-viewer`
 * (row `X.26`).
 *
 * `activity-log-page.ts`'s drawer is this component's one caller — before
 * row `X.26`'s own audit, that screen rendered its own hand-rolled
 * `<div class="diff">` instead of consuming this component, which had
 * existed with no call site anywhere in the app; that duplication is fixed
 * as of Staff `9.3a`.
 *
 * **Both shapes, honestly.** Staff `9.3a` migrates the settings-heavy
 * aggregates' own `.changed(...)` call sites onto {@code
 * ChangeDocuments.diff}'s `{before, after}` shape a call site at a time —
 * most of the platform's ~130 sites still write a flat, after-only map,
 * a fact recorded rather than a change observed. A field whose value is not
 * a `{before, after}` object renders as "set to «value»" rather than a
 * fabricated `undefined → value` diff line, so this viewer never lies about
 * what it is showing for a call site not yet migrated.
 */
@Component({
  selector: 'q-diff-viewer',
  templateUrl: './diff-viewer.html',
  styleUrl: './diff-viewer.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DiffViewer {
  /** `AuditEventDetail.changeDocument` / `ChangeDocuments.change(...)`'s own record shape. `null` when there is nothing to show. */
  readonly document = input<Readonly<Record<string, unknown>> | null>(null);
  /** Already translated. Shown when {@link document} is `null` or empty. */
  readonly emptyLabel = input<string | null>(null);

  protected readonly entries = computed<readonly DiffFieldEntry[]>(() => {
    const doc = this.document();
    if (!doc) {
      return [];
    }
    return Object.entries(doc).map(([field, value]) => {
      if (
        value !== null &&
        typeof value === 'object' &&
        !Array.isArray(value) &&
        'before' in value &&
        'after' in value
      ) {
        const change = value as { before: unknown; after: unknown };
        return { field, before: change.before, after: change.after };
      }
      return { field, before: undefined, after: value };
    });
  });

  protected format(value: unknown): string {
    return formatValue(value);
  }
}
