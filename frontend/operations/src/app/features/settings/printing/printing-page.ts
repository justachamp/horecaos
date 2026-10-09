import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';

import { BrandScope } from '../../../core/api/catalog-paths';
import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { FeatureFlags } from '../../../core/feature-flags';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { KitchenApi, StationResponse } from '../../kitchen/kitchen-api';
import { SettingsScope } from '../settings-scope';
import { AgentsCard } from './agents-card';
import { FiscalReceiptCard } from './fiscal-receipt-card';
import { PrintQueueCard } from './print-queue-card';
import { AgentView, PrinterView, PrintingApi, RouteView } from './printing-api';
import { describePrintError } from './printing-errors';
import { PrintersCard } from './printers-card';
import { ReceiptProfileCard } from './receipt-profile-card';
import { RoutesCard } from './routes-card';

type PageState = 'loading' | 'ready' | 'denied' | 'error';

/**
 * Settings · Money and tax · Printing and receipts (ADR 0154, gap-map row 10.14).
 *
 * The screen where a manager decides what comes out of a printer: which printers a branch has, which document
 * goes to which printer, what the brand's receipt carries at the foot, and what happened to the jobs that
 * did not print. It is the one place the module is operated; nothing prints at a branch until a manager creates
 * a route here, and the page says so as its first line.
 *
 * Printers, routes and agents belong to a branch, so the branch comes from the settings scope bar (a link pasted
 * into a chat opens the same one). The receipt profile belongs to the brand and is shown whether or not a branch
 * is picked. This page owns the branch's lists and gives each card what it needs; a card raises `changed` after a
 * write and the page reads them again, so the cards never disagree about what exists.
 *
 * The feature is dark by default (`feature.printing`, ADR 0082): with it off the page still reads and says so,
 * and every write is refused by the platform with a sentence that names the flag.
 */
@Component({
  selector: 'q-printing-page',
  imports: [
    TPipe,
    PrintersCard,
    AgentsCard,
    RoutesCard,
    FiscalReceiptCard,
    ReceiptProfileCard,
    PrintQueueCard,
  ],
  templateUrl: './printing-page.html',
  styleUrl: './printing-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PrintingPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly settingsScope = inject(SettingsScope);
  private readonly flags = inject(FeatureFlags);
  private readonly api = inject(PrintingApi);
  private readonly kitchen = inject(KitchenApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<PageState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);
  protected readonly flagsLoaded = signal(false);
  protected readonly printers = signal<readonly PrinterView[]>([]);
  protected readonly routes = signal<readonly RouteView[]>([]);
  protected readonly agents = signal<readonly AgentView[]>([]);
  protected readonly stations = signal<readonly StationResponse[]>([]);
  protected readonly posMayPrint = signal(false);
  protected readonly branchLoading = signal(false);

  /** Whether the platform has printing switched on for this tenant. Unknown reads as on, so no banner flashes. */
  protected readonly off = computed(
    () => this.flagsLoaded() && !this.flags.isOn('feature.printing'),
  );

  protected readonly brandScope = computed<BrandScope | null>(() => {
    const tenantId = this.tenant.tenantId();
    const brandId = this.settingsScope.brandId();
    return tenantId && brandId ? { tenantId, brandId } : null;
  });

  protected readonly locationScope = computed<LocationScope | null>(() => {
    const brand = this.brandScope();
    const locationId = this.settingsScope.locationId();
    return brand && locationId ? { ...brand, locationId } : null;
  });

  /** A ticket for the latest branch read: a reply under an older branch belongs to one already left. */
  private seq = 0;

  constructor() {
    void this.start();
    effect(() => {
      const scope = this.locationScope();
      untracked(() => {
        if (scope === null) {
          this.clear();
        } else {
          void this.loadBranch(scope);
        }
      });
    });
  }

  protected reload(): void {
    const scope = this.locationScope();
    if (scope !== null) {
      void this.loadBranch(scope);
    }
  }

  protected retry(): void {
    void this.start();
    this.reload();
  }

  private async start(): Promise<void> {
    this.state.set('loading');
    await Promise.all([this.tenant.ensureLoaded(), this.flags.ensureLoaded()]);
    this.flagsLoaded.set(true);
    if (!this.tenant.tenantId()) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    this.state.set('ready');
  }

  private clear(): void {
    this.seq++;
    this.printers.set([]);
    this.routes.set([]);
    this.agents.set([]);
    this.stations.set([]);
    this.posMayPrint.set(false);
    this.branchLoading.set(false);
  }

  private async loadBranch(scope: LocationScope): Promise<void> {
    const ticket = ++this.seq;
    this.branchLoading.set(true);
    this.loadErrorText.set(null);
    try {
      // The five reads are independent; a station list that cannot be read (a manager without kitchen access)
      // only costs the station picker, never the page, and the till statement is a nicety.
      const [printers, routes, agents, stations, pos] = await Promise.all([
        this.api.printers(scope),
        this.api.routes(scope),
        this.api.agents(scope),
        this.kitchen.stations(scope).catch(() => [] as readonly StationResponse[]),
        this.api.posExportActive(scope).catch(() => false),
      ]);
      if (ticket !== this.seq) {
        return;
      }
      this.printers.set(printers);
      this.routes.set(routes);
      this.agents.set(agents);
      this.stations.set(stations.filter((station) => station.status === 'ACTIVE'));
      this.posMayPrint.set(pos);
    } catch (error) {
      if (ticket !== this.seq) {
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.loadErrorText.set(
          describePrintError(error, this.i18n, {
            off: 'settings.printing.error.off',
            noRoute: 'settings.printing.error.noRoute',
          }),
        );
      }
    } finally {
      if (ticket === this.seq) {
        this.branchLoading.set(false);
      }
    }
  }
}
