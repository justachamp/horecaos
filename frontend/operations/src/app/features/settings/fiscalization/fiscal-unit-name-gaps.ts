import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { CatalogApi } from '../../catalog/catalog-api';
import { describeApiError } from '../../orders/order-errors';
import { BACKFILL_BATCH_SIZE } from './fiscal-backfill-editor';
import {
  FiscalBackfillItem,
  FiscalBackfillOutcome,
  FiscalCoverageNode,
  FiscalizationApi,
} from './fiscalization-api';

/** The longest name the platform accepts for the receipt line (`catalog.fiscal_classifications.fiscal_name`). */
export const FISCAL_NAME_MAX = 63;

/** A positive whole number, as `fiscal_unit_code > 0` requires. */
export const UNIT_CODE_PATTERN = /^[1-9]\d{0,8}$/;

/**
 * A node this section is for: a dish or a modifier option that holds **both** codes and still lacks the unit
 * code or the name printed on its receipt line (gap map row `10.7c`, ADR 0038: all four fields make a node
 * classified, and a receipt line requires the name). The coverage headline already counts such a node as
 * unclassified; nothing on the tab found it until this section.
 *
 * `null` is the platform saying the node holds none; `undefined` is an older server that does not say, and is
 * never read as a gap: a gap that is not there would send an operator to fill a field that is already set.
 */
export function lacksUnitOrName(node: FiscalCoverageNode): boolean {
  return (
    (node.nodeType === 'VARIANT' || node.nodeType === 'MODIFIER_OPTION') &&
    !!node.mxikCode &&
    !!node.packageCode &&
    (node.fiscalUnitCode === null || node.fiscalName === null || node.fiscalName === '')
  );
}

interface RowEdit {
  readonly unit?: string;
  readonly name?: string;
}

type RowProblem = 'notFound' | 'conflict' | 'failed';

interface GapRow {
  readonly nodeId: string;
  readonly nodeType: 'VARIANT' | 'MODIFIER_OPTION';
  readonly name: string;
  readonly categoryName: string | null;
  readonly mxik: string;
  readonly pkg: string;
  readonly lacksUnit: boolean;
  readonly lacksName: boolean;
  readonly unit: string;
  readonly fiscalName: string;
  readonly unitError: MessageKey | null;
  readonly nameError: MessageKey | null;
  /** The unit codes the reference lists for this ИКПУ when it lists several (a choice, not a guess). */
  readonly unitChoices: readonly number[];
  /** What the reference said when it was asked for this row's unit, if it was. */
  readonly referenceNote: 'none' | 'unavailable' | null;
  readonly problem: RowProblem | null;
}

interface SaveSummary {
  readonly saved: number;
  readonly unchanged: number;
  readonly failed: number;
}

function chunk<T>(items: readonly T[], size: number): readonly (readonly T[])[] {
  const chunks: T[][] = [];
  for (let start = 0; start < items.length; start += size) {
    chunks.push(items.slice(start, start + size));
  }
  return chunks;
}

/**
 * The fiscalization tab's second backfill: the items that already hold an ИКПУ and a package code and still
 * lack the **unit code** or the **name on the receipt line** (gap map row `10.7c`, ADR 0038).
 *
 * The first editor fills the two codes and says in its own doc that rows short of the other two «stay on the
 * coverage headline». After a full backfill that left the headline above zero with nothing on the tab finding
 * those items, while the receipt line requires the name. This section lists them, lets the operator fill the
 * two fields in the same `MERGE` batch the codes go in (a field the item already holds is never replaced), and
 * hands the rest to the catalog's fiscal workbench, which edits all four fields at once.
 *
 * **The reference suggests; the operator decides.** «Unit from the reference» asks the national list for the
 * ИКПУ the item holds and fills the unit when the list names exactly one; several are offered as buttons and
 * none says so. «Use the item's name» offers the name the menu shows, cut to the receipt's 63 characters: a
 * receipt name is the restaurant's legal wording, so nothing is filled without a click.
 */
@Component({
  selector: 'q-fiscal-unit-name-gaps',
  imports: [TPipe, RouterLink],
  templateUrl: './fiscal-unit-name-gaps.html',
  styleUrl: './fiscal-unit-name-gaps.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FiscalUnitNameGaps {
  private readonly api = inject(FiscalizationApi);
  private readonly catalog = inject(CatalogApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  readonly nodes = input.required<readonly FiscalCoverageNode[]>();

  /** Fired once a save has applied at least one row, or met an item that changed under it. */
  readonly saved = output<void>();

  private readonly edits = signal<ReadonlyMap<string, RowEdit>>(new Map());
  private readonly problems = signal<ReadonlyMap<string, RowProblem>>(new Map());
  /** The unit codes the reference lists, by ИКПУ; an ИКПУ that was asked for and listed none maps to an empty list. */
  private readonly listedUnits = signal<ReadonlyMap<string, readonly number[]>>(new Map());
  private readonly unreadable = signal<ReadonlySet<string>>(new Set());

  protected readonly saving = signal(false);
  protected readonly lookingUp = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly summary = signal<SaveSummary | null>(null);
  protected readonly fillSummary = signal<{ filled: number; open: number } | null>(null);

  private readonly candidates = computed(() => this.nodes().filter(lacksUnitOrName));
  private readonly byId = computed(
    () => new Map(this.candidates().map((node) => [node.nodeId, node] as const)),
  );

  protected readonly rows = computed<readonly GapRow[]>(() => {
    const edits = this.edits();
    const problems = this.problems();
    const listed = this.listedUnits();
    const unreadable = this.unreadable();
    return this.candidates().map((node) => {
      const lacksUnit = node.fiscalUnitCode === null;
      const lacksName = node.fiscalName === null || node.fiscalName === '';
      const edit = edits.get(node.nodeId);
      const unit = lacksUnit ? (edit?.unit ?? '') : String(node.fiscalUnitCode);
      const fiscalName = lacksName ? (edit?.name ?? '') : (node.fiscalName ?? '');
      const mxik = node.mxikCode ?? '';
      const units = listed.get(mxik);
      return {
        nodeId: node.nodeId,
        nodeType: node.nodeType as 'VARIANT' | 'MODIFIER_OPTION',
        name: node.name ?? '—',
        categoryName: node.categoryName,
        mxik,
        pkg: node.packageCode ?? '',
        lacksUnit,
        lacksName,
        unit,
        fiscalName,
        unitError:
          lacksUnit && edit?.unit !== undefined && !UNIT_CODE_PATTERN.test(edit.unit)
            ? 'settings.fiscalization.gaps.error.unit'
            : null,
        nameError:
          lacksName && edit?.name !== undefined && !this.validName(edit.name)
            ? 'settings.fiscalization.gaps.error.name'
            : null,
        unitChoices: lacksUnit && units !== undefined && units.length > 1 ? units : [],
        referenceNote:
          lacksUnit && units !== undefined && units.length === 0
            ? 'none'
            : lacksUnit && unreadable.has(mxik)
              ? 'unavailable'
              : null,
        problem: problems.get(node.nodeId) ?? null,
      };
    });
  });

  protected readonly dirtyRows = computed(() =>
    this.rows().filter(
      (row) =>
        (row.lacksUnit && this.edits().get(row.nodeId)?.unit !== undefined) ||
        (row.lacksName && this.edits().get(row.nodeId)?.name !== undefined),
    ),
  );
  protected readonly invalidRows = computed(() =>
    this.dirtyRows().filter((row) => row.unitError !== null || row.nameError !== null),
  );
  protected readonly sendableRows = computed(() =>
    this.dirtyRows().filter((row) => row.unitError === null && row.nameError === null),
  );
  protected readonly unitFillable = computed(
    () => this.rows().filter((row) => row.lacksUnit && row.unit === '').length,
  );
  protected readonly nameFillable = computed(
    () => this.rows().filter((row) => row.lacksName && row.fiscalName === '').length,
  );

  protected readonly nameMax = FISCAL_NAME_MAX;

  // -------------------------------------------------------------- editing

  protected onUnit(nodeId: string, raw: string): void {
    this.setCell(nodeId, 'unit', raw.replace(/\s+/g, ''));
  }

  protected onName(nodeId: string, raw: string): void {
    this.setCell(nodeId, 'name', raw);
  }

  protected useItemName(nodeId: string): void {
    const node = this.byId().get(nodeId);
    if (node?.name) {
      this.setCell(nodeId, 'name', this.nameFrom(node.name));
    }
  }

  protected useNameForAll(): void {
    for (const row of this.rows()) {
      if (row.lacksName && row.fiscalName === '') {
        this.useItemName(row.nodeId);
      }
    }
  }

  protected useUnit(nodeId: string, unit: number): void {
    this.setCell(nodeId, 'unit', String(unit));
  }

  /** Asks the reference for the unit this row's ИКПУ lists; one fills the cell, several are offered. */
  protected async useReferenceUnit(nodeId: string): Promise<void> {
    const node = this.byId().get(nodeId);
    if (!node?.mxikCode) {
      return;
    }
    this.lookingUp.set(true);
    try {
      const units = await this.unitsListedFor(node.mxikCode);
      if (units?.length === 1) {
        this.setCell(nodeId, 'unit', String(units[0]));
      }
    } finally {
      this.lookingUp.set(false);
    }
  }

  /** One lookup per distinct ИКПУ, then every empty unit cell whose ИКПУ lists exactly one is filled. */
  protected async fillUnitsFromReference(): Promise<void> {
    const wanted = this.rows().filter((row) => row.lacksUnit && row.unit === '');
    if (wanted.length === 0 || this.lookingUp()) {
      return;
    }
    this.lookingUp.set(true);
    let filled = 0;
    try {
      for (const mxik of new Set(wanted.map((row) => row.mxik))) {
        await this.unitsListedFor(mxik);
      }
      const listed = this.listedUnits();
      for (const row of wanted) {
        const units = listed.get(row.mxik);
        if (units?.length === 1) {
          this.setCell(row.nodeId, 'unit', String(units[0]));
          filled++;
        }
      }
    } finally {
      this.lookingUp.set(false);
    }
    this.fillSummary.set({ filled, open: wanted.length - filled });
  }

  protected discard(): void {
    this.edits.set(new Map());
    this.problems.set(new Map());
    this.saveError.set(null);
    this.summary.set(null);
    this.fillSummary.set(null);
  }

  // --------------------------------------------------------------- saving

  protected async save(): Promise<void> {
    const scope = this.location.scope();
    const rows = this.sendableRows();
    if (!scope || rows.length === 0 || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    const counts = { saved: 0, unchanged: 0, failed: 0 };
    const problems = new Map(this.problems());
    let applied = false;
    let stale = false;
    for (const batch of chunk(rows, BACKFILL_BATCH_SIZE)) {
      const items: FiscalBackfillItem[] = batch.map((row) => {
        const edit = this.edits().get(row.nodeId);
        return {
          nodeType: row.nodeType,
          nodeId: row.nodeId,
          fiscalUnitCode:
            row.lacksUnit && edit?.unit !== undefined ? Number.parseInt(edit.unit, 10) : undefined,
          fiscalName: row.lacksName && edit?.name !== undefined ? edit.name.trim() : undefined,
        };
      });
      try {
        const outcomes: readonly FiscalBackfillOutcome[] = await this.api.backfillCodes(
          scope,
          items,
        );
        const statusById = new Map(outcomes.map((o) => [o.nodeId, o.status] as const));
        for (const row of batch) {
          const status = statusById.get(row.nodeId);
          if (status === 'CLASSIFIED' || status === 'UNCHANGED') {
            counts[status === 'CLASSIFIED' ? 'saved' : 'unchanged']++;
            problems.delete(row.nodeId);
            this.edits.update((current) => {
              const next = new Map(current);
              next.delete(row.nodeId);
              return next;
            });
            applied = true;
          } else if (status === 'NOT_FOUND') {
            counts.failed++;
            problems.set(row.nodeId, 'notFound');
          } else if (status === 'CONFLICT') {
            counts.failed++;
            problems.set(row.nodeId, 'conflict');
            stale = true;
          } else {
            counts.failed++;
            problems.set(row.nodeId, 'failed');
          }
        }
      } catch (error) {
        counts.failed += batch.length;
        batch.forEach((row) => problems.set(row.nodeId, 'failed'));
        this.saveError.set(this.describe(error));
      }
      this.problems.set(new Map(problems));
    }
    this.summary.set(counts);
    this.saving.set(false);
    if (applied || stale) {
      this.saved.emit();
    }
  }

  // --------------------------------------------------------- presentation

  protected typeLabel(nodeType: 'VARIANT' | 'MODIFIER_OPTION'): string {
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

  // ------------------------------------------------------------- internals

  private validName(value: string): boolean {
    const trimmed = value.trim();
    return trimmed.length >= 1 && trimmed.length <= FISCAL_NAME_MAX;
  }

  /** The menu's name cut to the receipt's width, on a word where one fits. */
  private nameFrom(name: string): string {
    const trimmed = name.trim();
    if (trimmed.length <= FISCAL_NAME_MAX) {
      return trimmed;
    }
    const cut = trimmed.slice(0, FISCAL_NAME_MAX);
    const space = cut.lastIndexOf(' ');
    return (space > FISCAL_NAME_MAX / 2 ? cut.slice(0, space) : cut).trim();
  }

  private setCell(nodeId: string, column: 'unit' | 'name', raw: string): void {
    const node = this.byId().get(nodeId);
    if (!node) {
      return;
    }
    // A field the item already holds is not editable here: a gap-filling save never replaces it.
    const holds =
      column === 'unit'
        ? node.fiscalUnitCode !== null
        : node.fiscalName !== null && node.fiscalName !== '';
    if (holds) {
      return;
    }
    this.edits.update((current) => {
      const next = new Map(current);
      const edit: { unit?: string; name?: string } = { ...next.get(nodeId) };
      if (raw === '') {
        delete edit[column];
      } else {
        edit[column] = raw;
      }
      if (edit.unit === undefined && edit.name === undefined) {
        next.delete(nodeId);
      } else {
        next.set(nodeId, edit);
      }
      return next;
    });
    if (this.problems().has(nodeId)) {
      this.problems.update((current) => {
        const next = new Map(current);
        next.delete(nodeId);
        return next;
      });
    }
  }

  /** The unit codes the national list gives for one ИКПУ, asked once. `undefined` when the list could not be read. */
  private async unitsListedFor(mxik: string): Promise<readonly number[] | undefined> {
    const known = this.listedUnits().get(mxik);
    if (known !== undefined) {
      return known;
    }
    const scope = this.location.scope();
    if (!scope) {
      return undefined;
    }
    try {
      const found = await firstValueFrom(this.catalog.searchMxikReference(scope, mxik));
      const units = found.find((row) => row.code === mxik)?.defaultUnitCodes ?? [];
      this.listedUnits.update((current) => new Map(current).set(mxik, units));
      this.unreadable.update((current) => {
        const next = new Set(current);
        next.delete(mxik);
        return next;
      });
      return units;
    } catch {
      this.unreadable.update((current) => new Set(current).add(mxik));
      return undefined;
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
