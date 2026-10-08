import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CursorState, firstPage, nextPage, resetOnFilterChange } from '../../core/api/page';
import { ApiError } from '../../core/api/problem-details';
import { formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { LocationView, LocationsApi } from '../settings/locations/locations-api';
import { LEAD_SOURCE_KEYS, LEAD_STATUS_KEYS } from './lead-labels';
import { LeadDetailPanel } from './lead-detail-panel';
import { LeadAccess, LeadReachResolver } from './lead-reach';
import {
  LEAD_SOURCES,
  LEAD_STATUSES,
  Lead,
  LeadFilters,
  LeadSource,
  LeadStatus,
  LeadsApi,
  REGISTRABLE_LEAD_SOURCES,
} from './leads-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';
type View = 'attention' | 'all';

const PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent';

/**
 * The call centre's callback queue (ADR 0111 §4-§7): guests who phoned in, asked for a callback or
 * enquired about catering and are not yet customers with an order.
 *
 * A call-centre operator (an owner, an administrator, a brand manager) sees the brand's whole queue,
 * unassigned leads included, and hands a lead to a branch; a branch manager sees only the leads handed
 * to her branch and works them. {@link LeadReachResolver} decides which, from the session's own
 * scopes, and the server decides again on every request — a branch's route is written against the
 * branch, so a lead of another branch is not a row it could return.
 *
 * **Nothing in the list decrypts.** A row carries the masked number the server wrote when the lead was
 * created; the number itself is one audited reveal in the panel. A catering enquiry is a lead of
 * source `B2B_CATERING_ENQUIRY` in this same queue, owned by the call centre, with no sales pipeline
 * behind it (ADR 0111's default for its fourth open input) — which is why the source filter, not a
 * second screen, is how a call centre narrows to it.
 */
@Component({
  selector: 'q-leads-page',
  imports: [TPipe, LeadDetailPanel],
  templateUrl: './leads-page.html',
  styleUrl: './leads-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LeadsPage {
  private readonly api = inject(LeadsApi);
  private readonly reachResolver = inject(LeadReachResolver);
  private readonly locationsApi = inject(LocationsApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly errorText = signal<string | null>(null);
  protected readonly access = signal<LeadAccess | null>(null);
  protected readonly leads = signal<readonly Lead[]>([]);
  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly selectedId = signal<string | null>(null);

  protected readonly view = signal<View>('attention');
  protected readonly statusFilter = signal<LeadStatus | ''>('');
  protected readonly sourceFilter = signal<LeadSource | ''>('');
  protected readonly branchFilter = signal<string>('');

  protected readonly hasMore = signal(false);
  protected readonly loadingMore = signal(false);
  private pageState: CursorState = firstPage();

  protected readonly registering = signal(false);
  protected readonly registerBusy = signal(false);
  protected readonly registerError = signal<string | null>(null);
  protected readonly newSource = signal<LeadSource>('CALLBACK_REQUEST');
  protected readonly newPhone = signal('');
  protected readonly newName = signal('');
  protected readonly newNotes = signal('');
  protected readonly newBranch = signal('');

  protected readonly statuses = LEAD_STATUSES;
  protected readonly sources = LEAD_SOURCES;
  protected readonly registrableSources = REGISTRABLE_LEAD_SOURCES;

  protected readonly brandReach = computed(() => this.access()?.reach.kind === 'BRAND');
  protected readonly selected = computed(
    () => this.leads().find((lead) => lead.id === this.selectedId()) ?? null,
  );

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  // --------------------------------------------------------------------- filters

  protected setView(view: View): void {
    this.view.set(view);
    this.reload();
  }

  protected onStatusChange(value: string): void {
    this.statusFilter.set(LEAD_STATUSES.find((status) => status === value) ?? '');
    this.reload();
  }

  protected onSourceChange(value: string): void {
    this.sourceFilter.set(LEAD_SOURCES.find((source) => source === value) ?? '');
    this.reload();
  }

  protected onBranchChange(value: string): void {
    this.branchFilter.set(value);
    this.reload();
  }

  private reload(): void {
    this.pageState = resetOnFilterChange(this.pageState);
    void this.loadFirstPage();
  }

  private filters(): LeadFilters {
    const branch = this.branchFilter();
    return {
      view: this.view() === 'attention' ? 'attention' : undefined,
      status: this.statusFilter() === '' ? undefined : [this.statusFilter() as LeadStatus],
      source: this.sourceFilter() === '' ? undefined : (this.sourceFilter() as LeadSource),
      assignedLocationId: branch && branch !== 'unassigned' ? branch : undefined,
      unassigned: branch === 'unassigned' ? true : undefined,
    };
  }

  // ------------------------------------------------------------------------ load

  private async load(): Promise<void> {
    this.state.set('loading');
    this.errorText.set(null);
    const access = await this.reachResolver.resolve();
    if (!access) {
      this.state.set('denied');
      return;
    }
    this.access.set(access);
    await this.loadFirstPage();
    if (access.reach.kind === 'BRAND') {
      void this.loadLocations(access);
    }
  }

  private async loadFirstPage(): Promise<void> {
    const access = this.access();
    if (!access) {
      return;
    }
    this.pageState = firstPage();
    try {
      const page = await this.api.list(access.reach, this.pageState, this.filters());
      this.leads.set(page.items);
      this.advance(nextPage(this.pageState, page));
      this.state.set('ready');
    } catch (failure) {
      this.failLoad(failure);
    }
  }

  protected async loadMore(): Promise<void> {
    const access = this.access();
    if (!access || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const page = await this.api.list(access.reach, this.pageState, this.filters());
      this.leads.update((current) => [...current, ...page.items]);
      this.advance(nextPage(this.pageState, page));
    } catch (failure) {
      this.errorText.set(this.describe(failure));
    } finally {
      this.loadingMore.set(false);
    }
  }

  private advance(next: CursorState | null): void {
    this.hasMore.set(next !== null);
    if (next) {
      this.pageState = next;
    }
  }

  /** The brand's branches, for the hand-over and the filter. Non-critical: the queue itself loaded. */
  private async loadLocations(access: LeadAccess): Promise<void> {
    try {
      // `OperationsBrandController.locations` never reads the location segment; see reviews-page.ts.
      this.locations.set(
        await this.locationsApi.list({
          tenantId: access.reach.tenantId,
          brandId: access.reach.brandId,
          locationId: '',
        }),
      );
    } catch {
      // The filter and the hand-over simply offer no branches.
    }
  }

  private failLoad(failure: unknown): void {
    if (failure instanceof ApiError && failure.status === 403) {
      this.state.set('denied');
      return;
    }
    this.errorText.set(this.describe(failure));
    this.state.set('error');
  }

  private describe(failure: unknown): string {
    if (failure instanceof ApiError) {
      return describeApiError(failure, (key, values) => this.i18n.t(key, values));
    }
    throw failure;
  }

  // -------------------------------------------------------------------- selection

  protected select(lead: Lead): void {
    this.selectedId.set(lead.id);
  }

  protected closePanel(): void {
    this.selectedId.set(null);
  }

  /** The panel hands back the lead after a change, so the queue replaces its row (and drops a finished one from the attention view). */
  protected onChanged(updated: Lead): void {
    const finished =
      updated.status === 'CONVERTED' || updated.status === 'DECLINED' || updated.status === 'LOST';
    if (this.view() === 'attention' && finished) {
      this.leads.update((current) => current.filter((lead) => lead.id !== updated.id));
      return;
    }
    this.leads.update((current) =>
      current.map((lead) => (lead.id === updated.id ? updated : lead)),
    );
  }

  // ------------------------------------------------------------------- registering

  protected openRegister(): void {
    this.registerError.set(null);
    this.registering.set(true);
  }

  protected cancelRegister(): void {
    this.registering.set(false);
  }

  protected onNewSource(value: string): void {
    this.newSource.set(
      REGISTRABLE_LEAD_SOURCES.find((source) => source === value) ?? 'CALLBACK_REQUEST',
    );
  }

  protected async submitRegister(): Promise<void> {
    const access = this.access();
    const phone = this.newPhone().trim();
    if (!access || !phone || this.registerBusy()) {
      return;
    }
    this.registerBusy.set(true);
    this.registerError.set(null);
    try {
      const lead = await this.api.register(access.reach, {
        source: this.newSource(),
        phone,
        displayName: this.newName().trim() || undefined,
        notes: this.newNotes().trim() || undefined,
        assignedLocationId: this.newBranch() || undefined,
      });
      this.newPhone.set('');
      this.newName.set('');
      this.newNotes.set('');
      this.newBranch.set('');
      this.registering.set(false);
      this.leads.update((current) => [lead, ...current]);
      this.selectedId.set(lead.id);
    } catch (failure) {
      this.registerError.set(this.describe(failure));
    } finally {
      this.registerBusy.set(false);
    }
  }

  // ----------------------------------------------------------------------- labels

  protected statusLabel(status: LeadStatus): string {
    return this.i18n.t(LEAD_STATUS_KEYS[status]);
  }

  protected sourceLabel(source: LeadSource): string {
    return this.i18n.t(LEAD_SOURCE_KEYS[source]);
  }

  protected branchLabel(lead: Lead): string {
    if (lead.assignedLocationId === null) {
      return this.i18n.t('customers.leads.branch.none');
    }
    return (
      this.locations().find((location) => location.id === lead.assignedLocationId)?.displayName ??
      this.i18n.t('customers.leads.branch.thisOne')
    );
  }

  protected formatWhen(instant: string): string {
    return formatDateTime(new Date(instant), PLACEHOLDER_TIME_ZONE);
  }
}
