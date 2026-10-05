import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { MxikReferenceRow } from '../../catalog/catalog-domain';
import { MxikPicker } from '../../catalog/mxik-picker';
import { describeApiError } from '../../orders/order-errors';
import {
  FiscalBackfillItem,
  FiscalCategoryDefault,
  FiscalCoverageNode,
  FiscalizationApi,
} from './fiscalization-api';

/**
 * A real ИКПУ is seventeen digits (`PartnerFiscalizationBridge` says so beside
 * its deliberately fake one). This is an input guard for a person typing or
 * pasting into a cell, not a rule the platform enforces: ADR 0038 keeps the
 * code's shape out of the server, because it belongs to the official list and
 * that list — not a regular expression — is what a code is validated against
 * once it is imported. Until then the guard is all a wrong paste meets.
 */
export const MXIK_PATTERN = /^\d{17}$/;

/**
 * Digits only. The repository names no length for a package code (the fake one
 * in `PartnerFiscalizationBridge` has seven), so this refuses what is plainly
 * not a code — letters, a pasted name — and stops at the column's own width.
 */
export const PACKAGE_CODE_PATTERN = /^\d{1,10}$/;

/** Rows per request. A pasted column of six hundred dishes is six calls, each its own intent. */
export const BACKFILL_BATCH_SIZE = 100;

export type BackfillColumn = 'mxik' | 'pkg';

/** What this editor classifies (settings.md §10.7 Tab 3). The delivery fee has its own control above it. */
export type BackfillNodeType = 'VARIANT' | 'MODIFIER_OPTION';

/** A pending edit; a column that is absent is untouched and keeps what the dish holds. */
interface RowEdit {
  readonly mxik?: string;
  readonly pkg?: string;
}

/** What the last save said about a row that was not applied. */
type RowProblem = 'notFound' | 'conflict' | 'failed';

/** Counted across the whole last save; an applied row leaves the list, so the count is what remains of it. */
interface SaveSummary {
  readonly saved: number;
  readonly unchanged: number;
  readonly notFound: number;
  readonly conflict: number;
  readonly failed: number;
  readonly invalid: number;
}

interface EditorRow {
  readonly nodeId: string;
  /** A dish, or a modifier option: the batch names which, because the platform keys a classification by both. */
  readonly nodeType: BackfillNodeType;
  readonly name: string;
  readonly categoryName: string | null;
  readonly locationCount: number;
  readonly mxik: string;
  readonly pkg: string;
  /** The dish already holds this code: the cell is shown as it is and cannot be edited here. */
  readonly mxikStored: boolean;
  readonly pkgStored: boolean;
  readonly mxikDirty: boolean;
  readonly pkgDirty: boolean;
  readonly mxikError: MessageKey | null;
  readonly pkgError: MessageKey | null;
  readonly categoryDefault: FiscalCategoryDefault | null;
  readonly canCopyDefault: boolean;
  /**
   * The package codes the ИКПУ reference lists for the code the operator picked in this row, while
   * the cell still holds that code and the package cell is open: a choice, not a default.
   */
  readonly packageSuggestions: readonly string[];
  readonly problem: RowProblem | null;
}

/**
 * A node this editor is for: a dish or a modifier option still short of an ИКПУ or a package code.
 * A modifier option linked to a sellable dish never reaches the coverage list (it is classified
 * through the link), and the delivery fee has its own control, so neither is asked about here.
 */
export function isMissingCodes(node: FiscalCoverageNode): boolean {
  return (
    (node.nodeType === 'VARIANT' || node.nodeType === 'MODIFIER_OPTION') &&
    (!node.mxikCode || !node.packageCode)
  );
}

function chunk<T>(items: readonly T[], size: number): readonly (readonly T[])[] {
  const chunks: T[][] = [];
  for (let start = 0; start < items.length; start += size) {
    chunks.push(items.slice(start, start + size));
  }
  return chunks;
}

/** Spreadsheet cells arrive with spaces, non-breaking spaces and tabs inside them. */
function normalize(raw: string): string {
  return raw.replace(/\s+/g, '');
}

/**
 * The fiscalization tab's ИКПУ / package-code backfill (gap map row `10.7c`,
 * settings.md §10.7 Tab 3): every dish and every modifier option still short of
 * a code, as a table an operator can type or paste into, with format checks per
 * cell, a «copy category default» that fills the empty cells from the codes the
 * category's classified dishes already carry, and a save that goes out in
 * batches and reports each row.
 *
 * **The ИКПУ cell is the reference typeahead.** `q-mxik-picker` (the product
 * editor's 4.2e lookup over `catalog.mxik_reference`) stands where a code used
 * to be typed: a code can still be typed or pasted into it, a column of them
 * still fills down, and a name searches the reference. Choosing a reference row
 * writes its code and brings the package codes the reference lists for it:
 * exactly one fills an open package cell, several are offered as buttons beside
 * it. A modifier option goes through the same path as a dish — one request, one
 * `MERGE` rule, one audit fact for the batch — and differs only in having no
 * category, hence no category default.
 *
 * **What it writes and what it does not.** Only the two codes, and only in the
 * platform's `MERGE` mode: a row that already holds a unit code or a fiscal
 * name keeps them when its package code is filled in. A backfill fills gaps, so
 * a cell that already holds a code is read-only here — typing, pasting and
 * clearing are all ignored for it, and a pasted column fills down around it —
 * and the platform refuses to replace a stored code with a different one (the
 * row comes back `CONFLICT`, e.g. when another operator filled it after this
 * list was read). Changing or removing a classification is the product
 * editor's. Rows that still lack a unit code or a fiscal name after this stay
 * on the coverage headline; those are set in the catalog's fiscal workbench,
 * which covers all four fields.
 *
 * **What it cannot do yet.** The official ИКПУ list has never been imported
 * (row `4.2e`), so the lookup finds nothing today and this editor says so
 * (`referenceNote`) rather than showing an empty dropdown that reads as «no such
 * product»; codes are typed or pasted until the list is loaded.
 *
 * A row with an invalid cell is not sent; the rest of the batch is. That is
 * the same «N independent outcomes» rule `POST .../orders/bulk-actions` and the
 * import wizard use.
 */
@Component({
  selector: 'q-fiscal-backfill-editor',
  imports: [TPipe, MxikPicker],
  templateUrl: './fiscal-backfill-editor.html',
  styleUrl: './fiscal-backfill-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FiscalBackfillEditor {
  private readonly api = inject(FiscalizationApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  /** The coverage read's unclassified nodes; only the variants short a code are shown. */
  readonly nodes = input.required<readonly FiscalCoverageNode[]>();
  readonly categoryDefaults = input<readonly FiscalCategoryDefault[]>([]);

  /**
   * Fired once a save has applied at least one row, or met a dish that changed under it,
   * so the page can reload the coverage.
   */
  readonly saved = output<void>();

  private readonly edits = signal<ReadonlyMap<string, RowEdit>>(new Map());
  /** The reference row chosen for a node's ИКПУ, kept for the package codes it lists. */
  private readonly picks = signal<ReadonlyMap<string, MxikReferenceRow>>(new Map());
  private readonly problems = signal<ReadonlyMap<string, RowProblem>>(new Map());

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly summary = signal<SaveSummary | null>(null);
  /** Pasted codes the last paste left out because the dish already holds a code in that cell. */
  protected readonly pasteSkipped = signal(0);
  /** Bumped by «discard»: the typeahead cells clear too, so a name half-typed into one is dropped with the edits. */
  protected readonly epoch = signal(0);

  /** The scope the ИКПУ reference is searched in: the brand's, which the location scope carries. */
  protected readonly searchScope = computed(() => this.location.scope());

  private readonly candidates = computed(() => this.nodes().filter(isMissingCodes));
  private readonly storedById = computed(
    () => new Map(this.candidates().map((node) => [node.nodeId, node] as const)),
  );

  protected readonly rows = computed<readonly EditorRow[]>(() => {
    const defaults = new Map(this.categoryDefaults().map((d) => [d.categoryId, d] as const));
    const edits = this.edits();
    const problems = this.problems();
    const picks = this.picks();
    return this.candidates().map((node) => {
      const edit = edits.get(node.nodeId);
      const storedMxik = node.mxikCode ?? '';
      const storedPkg = node.packageCode ?? '';
      // An edit for a cell that has since been filled (another operator saved, and the list
      // was reloaded) is stale: the stored code is shown and nothing is sent for it.
      const mxikEdit = storedMxik === '' ? edit?.mxik : undefined;
      const pkgEdit = storedPkg === '' ? edit?.pkg : undefined;
      const mxik = mxikEdit ?? storedMxik;
      const pkg = pkgEdit ?? storedPkg;
      const mxikDirty = mxikEdit !== undefined;
      const pkgDirty = pkgEdit !== undefined;
      const categoryDefault = node.categoryId ? (defaults.get(node.categoryId) ?? null) : null;
      const pick = picks.get(node.nodeId);
      return {
        nodeId: node.nodeId,
        nodeType: node.nodeType as BackfillNodeType,
        name: node.name ?? '—',
        categoryName: node.categoryName,
        locationCount: node.locationCount,
        mxik,
        pkg,
        mxikStored: storedMxik !== '',
        pkgStored: storedPkg !== '',
        mxikDirty,
        pkgDirty,
        mxikError: mxikDirty ? this.mxikProblem(mxik) : null,
        pkgError: pkgDirty ? this.packageProblem(pkg) : null,
        categoryDefault,
        canCopyDefault: categoryDefault !== null && this.copyable(mxik, pkg, categoryDefault),
        packageSuggestions:
          pick && pick.code === mxik && storedPkg === ''
            ? pick.defaultPackageCodes.filter((code) => code !== pkg)
            : [],
        problem: problems.get(node.nodeId) ?? null,
      };
    });
  });

  protected readonly dirtyRows = computed(() =>
    this.rows().filter((r) => r.mxikDirty || r.pkgDirty),
  );
  protected readonly invalidRows = computed(() =>
    this.dirtyRows().filter((r) => r.mxikError !== null || r.pkgError !== null),
  );
  protected readonly sendableRows = computed(() =>
    this.dirtyRows().filter((r) => r.mxikError === null && r.pkgError === null),
  );
  protected readonly copyableCount = computed(
    () => this.rows().filter((r) => r.canCopyDefault).length,
  );

  // ------------------------------------------------------------- editing

  protected onInput(nodeId: string, column: BackfillColumn, raw: string): void {
    this.edits.update((current) => this.withCell(current, nodeId, column, raw));
    this.forget(nodeId);
  }

  /**
   * The operator chose a row of the ИКПУ reference for this node (its code is already in the cell).
   * The reference lists the package codes the good is sold in: exactly one is filled into an open
   * package cell, several are offered as a choice beside it, and none leaves the cell to be typed.
   * A package cell that already holds a code, stored or typed, is not touched.
   */
  protected onMxikPicked(nodeId: string, reference: MxikReferenceRow): void {
    this.picks.update((current) => new Map(current).set(nodeId, reference));
    const row = this.rows().find((candidate) => candidate.nodeId === nodeId);
    if (!row || row.pkgStored || row.pkg !== '' || reference.defaultPackageCodes.length !== 1) {
      return;
    }
    this.edits.update((current) =>
      this.withCell(current, nodeId, 'pkg', reference.defaultPackageCodes[0]),
    );
  }

  protected usePackageCode(nodeId: string, code: string): void {
    this.edits.update((current) => this.withCell(current, nodeId, 'pkg', code));
    this.forget(nodeId);
  }

  /**
   * A paste of several rows, or of a tab-separated pair of columns, fills down
   * from the cell it landed in — the spreadsheet gesture. A single value is
   * left to the browser and arrives as an ordinary `input`.
   */
  protected onPaste(event: ClipboardEvent, rowIndex: number, column: BackfillColumn): void {
    const text = event.clipboardData?.getData('text') ?? '';
    if (!/[\r\n\t]/.test(text.trim())) {
      return;
    }
    event.preventDefault();
    const lines = text.replace(/\r/g, '').split('\n');
    while (lines.length > 0 && lines[lines.length - 1].trim() === '') {
      lines.pop();
    }
    const columns: readonly BackfillColumn[] = ['mxik', 'pkg'];
    const firstColumn = columns.indexOf(column);
    const rows = this.rows();
    const touched: string[] = [];
    let skipped = 0;
    this.edits.update((current) => {
      let next = current;
      lines.forEach((line, offset) => {
        const row = rows[rowIndex + offset];
        if (!row) {
          return;
        }
        touched.push(row.nodeId);
        line.split('\t').forEach((cell, cellOffset) => {
          const target = columns[firstColumn + cellOffset];
          if (!target) {
            return;
          }
          // A cell that already holds a code is left as it is; only a pasted value that
          // would have changed it is worth telling the operator about.
          const value = normalize(cell);
          const stored = target === 'mxik' ? row.mxikStored : row.pkgStored;
          if (stored && value !== '' && value !== (target === 'mxik' ? row.mxik : row.pkg)) {
            skipped++;
          }
          next = this.withCell(next, row.nodeId, target, cell);
        });
      });
      return next;
    });
    this.pasteSkipped.set(skipped);
    touched.forEach((id) => this.forget(id));
  }

  protected copyDefault(nodeId: string): void {
    this.edits.update((current) => this.withDefault(current, nodeId));
    this.forget(nodeId);
  }

  protected copyDefaultsToAll(): void {
    const ids = this.rows()
      .filter((row) => row.canCopyDefault)
      .map((row) => row.nodeId);
    this.edits.update((current) => ids.reduce((next, id) => this.withDefault(next, id), current));
    ids.forEach((id) => this.forget(id));
  }

  protected discard(): void {
    this.edits.set(new Map());
    this.picks.set(new Map());
    this.epoch.update((value) => value + 1);
    this.problems.set(new Map());
    this.saveError.set(null);
    this.summary.set(null);
    this.pasteSkipped.set(0);
  }

  // ---------------------------------------------------------------- saving

  protected async save(): Promise<void> {
    const scope = this.location.scope();
    const rows = this.sendableRows();
    if (!scope || rows.length === 0 || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    this.pasteSkipped.set(0);
    const counts = { saved: 0, unchanged: 0, notFound: 0, conflict: 0, failed: 0 };
    const problems = new Map(this.problems());
    let applied = false;
    let stale = false;
    for (const batch of chunk(rows, BACKFILL_BATCH_SIZE)) {
      const items: FiscalBackfillItem[] = batch.map((row) => ({
        nodeType: row.nodeType,
        nodeId: row.nodeId,
        mxikCode: row.mxikDirty ? row.mxik : undefined,
        packageCode: row.pkgDirty ? row.pkg : undefined,
      }));
      try {
        const outcomes = await this.api.backfillCodes(scope, items);
        const statusById = new Map(outcomes.map((o) => [o.nodeId, o.status] as const));
        for (const row of batch) {
          const status = statusById.get(row.nodeId);
          if (status === 'CLASSIFIED' || status === 'UNCHANGED') {
            counts[status === 'CLASSIFIED' ? 'saved' : 'unchanged']++;
            problems.delete(row.nodeId);
            this.edits.update((current) => this.without(current, row.nodeId));
            applied = true;
          } else if (status === 'NOT_FOUND') {
            counts.notFound++;
            problems.set(row.nodeId, 'notFound');
          } else if (status === 'CONFLICT') {
            // The platform never replaces a stored code: the dish got one after this list was
            // read. Keep the edit marked, and have the page reload so the cell shows it.
            counts.conflict++;
            problems.set(row.nodeId, 'conflict');
            stale = true;
          } else {
            counts.failed++;
            problems.set(row.nodeId, 'failed');
          }
        }
      } catch (error) {
        // The request itself failed, so nothing is known about these rows: they keep
        // their edits and are marked, and the batches after this one still go out.
        counts.failed += batch.length;
        batch.forEach((row) => problems.set(row.nodeId, 'failed'));
        this.saveError.set(this.describe(error));
      }
      this.problems.set(new Map(problems));
    }
    this.summary.set({ ...counts, invalid: this.invalidRows().length });
    this.saving.set(false);
    if (applied || stale) {
      this.saved.emit();
    }
  }

  // ----------------------------------------------------------- presentation

  protected rowTitle(row: EditorRow): string {
    const categoryDefault = row.categoryDefault;
    if (!categoryDefault || !row.canCopyDefault) {
      return this.i18n.t('settings.fiscalization.backfill.copyDefault.unavailable');
    }
    return this.i18n.t('settings.fiscalization.backfill.copyDefault.title', {
      category: categoryDefault.categoryName ?? '—',
      agreeing: categoryDefault.agreeingCount,
      sample: categoryDefault.sampleSize,
      mxik: categoryDefault.mxikCode,
      package: categoryDefault.packageCode,
    });
  }

  protected typeLabel(nodeType: BackfillNodeType): string {
    return this.i18n.t(`settings.fiscalization.classification.nodeType.${nodeType}` as MessageKey);
  }

  protected problemLabel(problem: RowProblem): string {
    switch (problem) {
      case 'notFound':
        return this.i18n.t('settings.fiscalization.backfill.status.notFound');
      case 'conflict':
        return this.i18n.t('settings.fiscalization.backfill.status.conflict');
      default:
        return this.i18n.t('settings.fiscalization.backfill.status.failed');
    }
  }

  // --------------------------------------------------------------- internals

  // An empty value is never a pending edit (a stored cell cannot be edited, and an
  // empty cell typed back to empty is no edit), so only the format is checked.
  private mxikProblem(value: string): MessageKey | null {
    return MXIK_PATTERN.test(value) ? null : 'settings.fiscalization.backfill.error.mxik';
  }

  private packageProblem(value: string): MessageKey | null {
    return PACKAGE_CODE_PATTERN.test(value)
      ? null
      : 'settings.fiscalization.backfill.error.packageCode';
  }

  /**
   * Whether copying would fill something and contradict nothing. A default is a
   * pair, and a package code belongs to an ИКПУ, so a dish that already holds a
   * different ИКПУ (or a different package code) is not offered a pair that
   * would sit beside it — that is a correction, and it is the operator's to make.
   */
  private copyable(mxik: string, pkg: string, categoryDefault: FiscalCategoryDefault): boolean {
    const agreesMxik = mxik === '' || mxik === categoryDefault.mxikCode;
    const agreesPkg = pkg === '' || pkg === categoryDefault.packageCode;
    return agreesMxik && agreesPkg && (mxik === '' || pkg === '');
  }

  private withCell(
    current: ReadonlyMap<string, RowEdit>,
    nodeId: string,
    column: BackfillColumn,
    raw: string,
  ): ReadonlyMap<string, RowEdit> {
    const stored = this.storedById().get(nodeId);
    if (!stored) {
      return current;
    }
    const storedValue = (column === 'mxik' ? stored.mxikCode : stored.packageCode) ?? '';
    if (storedValue !== '') {
      // A backfill fills gaps: a code the dish already holds is not editable here, so a
      // typed, pasted or cleared value for it is dropped rather than made sendable.
      return current;
    }
    const value = normalize(raw);
    const next = new Map(current);
    const edit: { mxik?: string; pkg?: string } = { ...next.get(nodeId) };
    if (value === '') {
      // Typed back to empty: the cell holds nothing, so there is nothing to send.
      delete edit[column];
    } else {
      edit[column] = value;
    }
    if (edit.mxik === undefined && edit.pkg === undefined) {
      next.delete(nodeId);
    } else {
      next.set(nodeId, edit);
    }
    return next;
  }

  private withDefault(
    current: ReadonlyMap<string, RowEdit>,
    nodeId: string,
  ): ReadonlyMap<string, RowEdit> {
    const row = this.rows().find((r) => r.nodeId === nodeId);
    const categoryDefault = row?.categoryDefault;
    if (!row || !categoryDefault || !row.canCopyDefault) {
      return current;
    }
    let next = current;
    if (row.mxik === '') {
      next = this.withCell(next, nodeId, 'mxik', categoryDefault.mxikCode);
    }
    if (row.pkg === '') {
      next = this.withCell(next, nodeId, 'pkg', categoryDefault.packageCode);
    }
    return next;
  }

  private without(
    current: ReadonlyMap<string, RowEdit>,
    nodeId: string,
  ): ReadonlyMap<string, RowEdit> {
    const next = new Map(current);
    next.delete(nodeId);
    return next;
  }

  /** A row the operator touches again is no longer «not saved». */
  private forget(nodeId: string): void {
    if (this.problems().has(nodeId)) {
      this.problems.update((current) => {
        const next = new Map(current);
        next.delete(nodeId);
        return next;
      });
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
