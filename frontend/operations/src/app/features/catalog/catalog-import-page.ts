import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ApiError } from '../../core/api/problem-details';
import { firstPage } from '../../core/api/page';
import { DeniedState } from '../../shared/ui/denied-state';
import { EmptyState } from '../../shared/ui/empty-state';
import { InlineAlert } from '../../shared/ui/inline-alert';
import { StatusPill } from '../../shared/ui/status-pill';
import {
  MappingPane,
  MappingPaneConflict,
  MappingPaneLinkIntent,
  MappingPaneRow,
} from '../../shared/ui/mapping-pane';
import {
  IntegrationsApi,
  InstallationView,
  BindingView,
} from '../settings/integrations/integrations-api';
import { describeApiError } from '../orders/order-errors';
import { PosSyncApi, SyncRunSummary, SyncRunDetail, ApplyItemOutcome } from './pos-sync-api';
import { PosMappingApi, UnmappedExternalResponse, MappingView } from './pos-mapping-api';

/** See `order-queue.ts`'s identical constant — no location carries a timezone on any response this page reaches yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

type Tab = 'runs' | 'mapping';

interface BindingOption {
  readonly bindingId: string;
  readonly label: string;
}

/**
 * catalog.md §4.11 (Import: Excel and POS), gap-map rows 4.5a/10.8b/X.24.
 *
 * **POS only — Excel import (`4.5b`) is a separate row and stays deferred.**
 * `PosSyncRunController`'s start/differences/review-decisions/apply/resume
 * were all real and reachable from no screen; this page is the run list, the
 * start form with the import-language and price-re-import choices, a run's
 * detail with its per-item outcomes, and — the Соответствия tab — the
 * product mapping pane over the same binding.
 */
@Component({
  selector: 'q-catalog-import-page',
  imports: [TPipe, DeniedState, EmptyState, InlineAlert, StatusPill, MappingPane],
  templateUrl: './catalog-import-page.html',
  styleUrl: './catalog-import-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CatalogImportPage implements OnInit {
  private readonly location = inject(CurrentLocation);
  private readonly integrationsApi = inject(IntegrationsApi);
  private readonly syncApi = inject(PosSyncApi);
  private readonly mappingApi = inject(PosMappingApi);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly bindingOptions = signal<readonly BindingOption[]>([]);
  protected readonly selectedBindingId = signal<string | null>(null);

  protected readonly runs = signal<readonly SyncRunSummary[]>([]);
  protected readonly runsLoading = signal(false);

  protected readonly selectedRunId = signal<string | null>(null);
  protected readonly runDetail = signal<SyncRunDetail | null>(null);
  protected readonly applyItems = signal<readonly ApplyItemOutcome[]>([]);
  protected readonly detailLoading = signal(false);

  protected readonly starting = signal(false);
  protected readonly startError = signal<string | null>(null);
  protected readonly dryRun = signal(true);
  protected readonly importLanguage = signal('');
  protected readonly priceReImport = signal(false);

  protected readonly activeTab = signal<Tab>('runs');

  protected readonly mappingRows = signal<readonly MappingView[]>([]);
  protected readonly mappingUnmapped = signal<UnmappedExternalResponse | null>(null);
  protected readonly mappingConflicts = signal<readonly MappingPaneConflict[]>([]);
  protected readonly mappingLoading = signal(false);
  protected readonly mappingBusy = signal(false);
  protected readonly mappingError = signal<string | null>(null);

  protected readonly hasBinding = computed(() => this.selectedBindingId() !== null);

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      const installations = await this.integrationsApi.listInstallations(scope);
      const posInstallations = installations.filter((i: InstallationView) => i.category === 'POS');
      const options: BindingOption[] = [];
      for (const installation of posInstallations) {
        const bindings = await this.integrationsApi.listBindings(scope, installation.id);
        for (const binding of bindings as readonly BindingView[]) {
          options.push({
            bindingId: binding.id,
            label: `${installation.displayName} (${installation.providerType})`,
          });
        }
      }
      this.bindingOptions.set(options);
      const current = this.selectedBindingId();
      const nextBinding =
        current && options.some((o) => o.bindingId === current)
          ? current
          : (options[0]?.bindingId ?? null);
      this.selectedBindingId.set(nextBinding);
      this.denied.set(false);
      if (nextBinding) {
        await this.loadRuns();
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  protected async selectBinding(bindingId: string): Promise<void> {
    this.selectedBindingId.set(bindingId);
    this.selectedRunId.set(null);
    this.runDetail.set(null);
    this.applyItems.set([]);
    await this.loadRuns();
    if (this.activeTab() === 'mapping') {
      await this.loadMapping();
    }
  }

  private async loadRuns(): Promise<void> {
    const bindingId = this.selectedBindingId();
    const scope = this.location.scope();
    if (!bindingId || !scope) {
      return;
    }
    this.runsLoading.set(true);
    try {
      const page = await firstValueFrom(
        this.syncApi.listRuns({ tenantId: scope.tenantId }, bindingId, firstPage()),
      );
      this.runs.set(page.items);
    } catch (error) {
      this.loadError.set(this.describe(error));
    } finally {
      this.runsLoading.set(false);
    }
  }

  protected async selectRun(runId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.selectedRunId.set(runId);
    this.detailLoading.set(true);
    try {
      const [detail, itemsPage] = await Promise.all([
        firstValueFrom(this.syncApi.runDetail({ tenantId: scope.tenantId }, runId)),
        firstValueFrom(this.syncApi.applyItems({ tenantId: scope.tenantId }, runId, firstPage())),
      ]);
      this.runDetail.set(detail);
      this.applyItems.set(itemsPage.items);
    } catch (error) {
      this.loadError.set(this.describe(error));
    } finally {
      this.detailLoading.set(false);
    }
  }

  protected async startImport(): Promise<void> {
    const bindingId = this.selectedBindingId();
    const scope = this.location.scope();
    if (!bindingId || !scope || this.starting()) {
      return;
    }
    this.starting.set(true);
    this.startError.set(null);
    try {
      const language = this.importLanguage().trim();
      await firstValueFrom(
        this.syncApi.start(
          { tenantId: scope.tenantId },
          bindingId,
          this.dryRun(),
          language === '' ? null : language,
          this.priceReImport(),
        ),
      );
      await this.loadRuns();
    } catch (error) {
      this.startError.set(this.describe(error));
    } finally {
      this.starting.set(false);
    }
  }

  protected async selectTab(tab: Tab): Promise<void> {
    this.activeTab.set(tab);
    if (tab === 'mapping' && this.mappingRows().length === 0 && this.mappingUnmapped() === null) {
      await this.loadMapping();
    }
  }

  private async loadMapping(): Promise<void> {
    const bindingId = this.selectedBindingId();
    const scope = this.location.scope();
    if (!bindingId || !scope) {
      return;
    }
    this.mappingLoading.set(true);
    this.mappingError.set(null);
    try {
      const [page, unmapped] = await Promise.all([
        firstValueFrom(
          this.mappingApi.list(
            { tenantId: scope.tenantId },
            bindingId,
            'PRODUCT',
            'ACTIVE',
            firstPage(),
          ),
        ),
        firstValueFrom(
          this.mappingApi.unmapped({ tenantId: scope.tenantId }, bindingId, 'PRODUCT'),
        ),
      ]);
      this.mappingRows.set(page.items);
      this.mappingUnmapped.set(unmapped);
    } catch (error) {
      this.mappingError.set(this.describe(error));
    } finally {
      this.mappingLoading.set(false);
    }
  }

  protected async onMappingLink(intent: MappingPaneLinkIntent): Promise<void> {
    const bindingId = this.selectedBindingId();
    const scope = this.location.scope();
    if (!bindingId || !scope || this.mappingBusy()) {
      return;
    }
    this.mappingBusy.set(true);
    this.mappingError.set(null);
    try {
      await firstValueFrom(
        this.mappingApi.create(
          { tenantId: scope.tenantId },
          bindingId,
          'PRODUCT',
          intent.horecaosId,
          intent.externalId,
          null,
        ),
      );
      await this.loadMapping();
    } catch (error) {
      this.mappingError.set(this.describe(error));
    } finally {
      this.mappingBusy.set(false);
    }
  }

  protected async onMappingUnlink(row: MappingPaneRow): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.mappingBusy()) {
      return;
    }
    this.mappingBusy.set(true);
    this.mappingError.set(null);
    try {
      await firstValueFrom(
        this.mappingApi.retire({ tenantId: scope.tenantId }, row.mappingId, row.version),
      );
      await this.loadMapping();
    } catch (error) {
      this.mappingError.set(this.describe(error));
    } finally {
      this.mappingBusy.set(false);
    }
  }

  protected async onMappingBulkAutoMatch(): Promise<void> {
    const bindingId = this.selectedBindingId();
    const scope = this.location.scope();
    if (!bindingId || !scope || this.mappingBusy()) {
      return;
    }
    this.mappingBusy.set(true);
    this.mappingError.set(null);
    try {
      const result = await firstValueFrom(
        this.mappingApi.bulkAutoMatch({ tenantId: scope.tenantId }, bindingId, 'PRODUCT'),
      );
      this.mappingConflicts.set(result.conflicts);
      await this.loadMapping();
    } catch (error) {
      this.mappingError.set(this.describe(error));
    } finally {
      this.mappingBusy.set(false);
    }
  }

  protected onMappingDismissConflict(conflict: MappingPaneConflict): void {
    this.mappingConflicts.update((conflicts) => conflicts.filter((c) => c !== conflict));
  }

  protected timeLabel(iso: string | null | undefined): string {
    return iso ? formatDateTime(new Date(iso), PLACEHOLDER_TIME_ZONE) : '—';
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
