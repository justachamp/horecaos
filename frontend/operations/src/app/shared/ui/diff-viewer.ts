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
 * Generalises `activity-log-page.ts`'s `changeEntries`/`formatFieldChange`,
 * the only production reader of `ChangeDocuments.change(field, before,
 * after)`'s `{before, after}` shape (`T08`'s own finding: **zero** production
 * callers write it yet — this viewer is ready before the write side is).
 *
 * **Both shapes, honestly.** `T08` also finds that ~130 of 132 `.changed(...)`
 * call sites write a flat, after-only map — a fact recorded, not a change
 * observed. A field whose value is not a `{before, after}` object renders as
 * "set to «value»" rather than a fabricated `undefined → value` diff line,
 * so a viewer built before the write-side migration lands does not lie about
 * what it is showing.
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
