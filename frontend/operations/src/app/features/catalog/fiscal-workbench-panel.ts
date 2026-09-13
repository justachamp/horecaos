import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { Observable, firstValueFrom, map, of } from 'rxjs';

import { BrandScope } from '../../core/api/catalog-paths';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { DataGrid } from '../../shared/ui/data-grid/data-grid';
import {
  DataGridBatchResult,
  DataGridColumn,
  DataGridRowEdit,
  DataGridSaveFn,
} from '../../shared/ui/data-grid/data-grid-types';
import { Drawer } from '../../shared/ui/drawer';
import { InlineAlert } from '../../shared/ui/inline-alert';
import { CatalogApi } from './catalog-api';
import {
  FiscalCoverageNode,
  FiscalCoverageSummary,
  PriceableType,
  UNCLASSIFIED,
} from './catalog-domain';
import { describeApiError } from '../orders/order-errors';

/**
 * Joins a node's type and id into `q-data-grid`'s single opaque `rowId`
 * string, and back. Not `:` or anything containing it — `q-data-grid` builds
 * its own pending-edit keys as `` `${rowId}:${columnKey}` `` and splits on the
 * *first* colon, so a rowId that itself contains one gets cut at the wrong
 * point (`VARIANT:v1:mxikCode` reads back as rowId `VARIANT`, not
 * `VARIANT:v1`). `|` appears in neither a `PriceableType` name nor a UUID.
 */
const ROW_ID_SEPARATOR = '|';

function rowIdOf(node: FiscalCoverageNode): string {
  return `${node.nodeType}${ROW_ID_SEPARATOR}${node.nodeId}`;
}

function parseRowId(rowId: string): { readonly nodeType: PriceableType; readonly nodeId: string } {
  const index = rowId.indexOf(ROW_ID_SEPARATOR);
  return {
    nodeType: rowId.slice(0, index) as PriceableType,
    nodeId: rowId.slice(index + ROW_ID_SEPARATOR.length),
  };
}

/**
 * catalog.md §4.1a — the fiscal workbench: "N of M priceable nodes
 * unclassified", an unclassified worklist spanning `VARIANT`, `MODIFIER_OPTION`
 * and `FEE` nodes alike, and a `q-data-grid` fill-down over ИКПУ, package
 * code, fiscal unit and fiscal name — so a 600-item menu can be onboarded in
 * a day instead of one variant at a time in the product editor.
 *
 * The coverage read (`CatalogQueryController.fiscalCoverage`) already
 * existed before this wave — P34 built it locally for Settings 10.7 Tab 3 "in
 * place of P21's not-yet-merged fiscal workbench" — so this reuses it rather
 * than adding a second endpoint. The same trap P34's own doc names applies
 * here: this worklist is node-level and must never be shown as, or confused
 * with, the products list's product-level `NO_MXIK` tab count.
 *
 * Each grid row starts blank in the four editable columns even when a node
 * already carries a partial classification (say, an ИКПУ with no package
 * code) — the coverage read reports only whether a node is unclassified, not
 * which of its fields are already set, so this is a fill-in-the-gaps tool,
 * not a full editor. The product editor (`P22`) remains where an operator
 * reviews what a node already has.
 */
@Component({
  selector: 'q-fiscal-workbench-panel',
  imports: [TPipe, Drawer, InlineAlert, DataGrid],
  templateUrl: './fiscal-workbench-panel.html',
  styleUrl: './fiscal-workbench-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FiscalWorkbenchPanel {
  private readonly api = inject(CatalogApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input<BrandScope | null>(null);
  readonly open = input(false);
  readonly dismiss = output<void>();

  protected readonly loading = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<string | null>(null);
  protected readonly coverage = signal<FiscalCoverageSummary | null>(null);

  protected readonly rowIdFn = rowIdOf;
  protected readonly saveFn: DataGridSaveFn = (edits) => this.save(edits);

  protected readonly columns = computed<readonly DataGridColumn<FiscalCoverageNode>[]>(() => {
    this.i18n.locale();
    return [
      {
        key: 'node',
        header: this.i18n.t('catalog.fiscalWorkbench.column.node'),
        getValue: (node) => node.name ?? node.nodeId,
      },
      {
        key: 'type',
        header: this.i18n.t('catalog.fiscalWorkbench.column.type'),
        getValue: (node) => this.typeLabel(node.nodeType),
      },
      {
        key: 'mxikCode',
        header: this.i18n.t('catalog.fiscalWorkbench.column.mxik'),
        editable: true,
        getValue: () => '',
      },
      {
        key: 'packageCode',
        header: this.i18n.t('catalog.fiscalWorkbench.column.packageCode'),
        editable: true,
        getValue: () => '',
      },
      {
        key: 'fiscalUnitCode',
        header: this.i18n.t('catalog.fiscalWorkbench.column.fiscalUnitCode'),
        editable: true,
        numeric: true,
        getValue: () => '',
      },
      {
        key: 'fiscalName',
        header: this.i18n.t('catalog.fiscalWorkbench.column.fiscalName'),
        editable: true,
        getValue: () => '',
      },
    ];
  });

  constructor() {
    // Loads (and reloads, on every re-open) whenever the panel becomes
    // visible — a coverage report from the last time it was open is stale
    // the moment the product editor or another tab classifies something.
    effect(() => {
      if (this.open()) {
        void this.load();
      }
    });
  }

  private async load(): Promise<void> {
    const scope = this.scope();
    if (!scope) {
      this.denied.set(true);
      return;
    }
    this.loading.set(true);
    try {
      const summary = await firstValueFrom(this.api.fiscalCoverage(scope));
      this.coverage.set(summary);
      this.denied.set(false);
      this.lastError.set(null);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
        this.lastError.set(null);
      } else if (error instanceof ApiError) {
        this.lastError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * One `PUT .../fiscal-classifications/bulk` call for the whole batch of
   * pending cells, rather than one classification endpoint call per row —
   * exactly what this wave's row action / bulk-classify split is for.
   */
  private save(edits: readonly DataGridRowEdit[]): Observable<DataGridBatchResult> {
    const scope = this.scope();
    if (!scope) {
      return of({ items: [] });
    }
    const items = edits.map((edit) => {
      const { nodeType, nodeId } = parseRowId(edit.rowId);
      const fiscalUnitCode = edit.changes['fiscalUnitCode']?.trim();
      return {
        nodeType,
        nodeId,
        fiscal: {
          ...UNCLASSIFIED,
          mxikCode: edit.changes['mxikCode']?.trim() || undefined,
          packageCode: edit.changes['packageCode']?.trim() || undefined,
          fiscalUnitCode: fiscalUnitCode ? Number(fiscalUnitCode) : undefined,
          fiscalName: edit.changes['fiscalName']?.trim() || undefined,
        },
      };
    });
    return this.api.bulkClassify(scope, items).pipe(
      map((result) => ({
        items: result.outcomes.map((outcome) => ({
          rowId: `${outcome.nodeType}${ROW_ID_SEPARATOR}${outcome.nodeId}`,
          status: outcome.status === 'CLASSIFIED' ? ('APPLIED' as const) : ('FAILED' as const),
          problemCode: outcome.status === 'CLASSIFIED' ? null : outcome.status,
        })),
      })),
    );
  }

  /** A classified row drops off the next load's unclassified list — reload to reflect it. */
  protected onSaved(): void {
    void this.load();
  }

  protected close(): void {
    this.dismiss.emit();
  }

  protected typeLabel(type: PriceableType): string {
    switch (type) {
      case 'VARIANT':
        return this.i18n.t('catalog.fiscalWorkbench.nodeType.VARIANT');
      case 'MODIFIER_OPTION':
        return this.i18n.t('catalog.fiscalWorkbench.nodeType.MODIFIER_OPTION');
      case 'FEE':
        return this.i18n.t('catalog.fiscalWorkbench.nodeType.FEE');
    }
  }
}
