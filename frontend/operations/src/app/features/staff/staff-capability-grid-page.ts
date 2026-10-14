import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { DeniedState } from '../../shared/ui/denied-state';
import { LockedState } from '../../shared/ui/locked-state';
import { MatrixGrid } from '../../shared/ui/matrix-grid/matrix-grid';
import { MatrixCell, MatrixHeader } from '../../shared/ui/matrix-grid/matrix-grid-types';
import { AccessRefusal, accessRefusal, describeApiError } from '../orders/order-errors';
import { SentenceLocale } from './capability-sentences';
import { CatalogueRow, catalogueRow, groupByArea, matchesCatalogueRow } from './job-catalogue';
import { CapabilityEntry, GrantView, RoleDescriptor, StaffApi } from './staff-api';
import { JobEditorMode, JobSubmission, StaffJobEditor } from './staff-job-editor';
import { StaffJobNames } from './staff-job-names';
import { roleLabel } from './staff-role-labels';

/** One area of the table: its heading and the matrix of its capabilities against every job. */
interface AreaGrid {
  readonly area: string;
  readonly rowHeaders: readonly MatrixHeader[];
  readonly cells: readonly MatrixCell[];
}

/**
 * «Таблица прав» (ADR 0186, gap-map row `9.1b`, staff-and-access.md section 5): every job across, every
 * thing a job can do down, grouped by area, with a search over the list itself and the editor for the
 * company's own jobs.
 *
 * **The spec said the grid "earns its place exactly once - when tenant-defined jobs ship"**, and this is
 * that screen. It is a read-only matrix over the whole vocabulary (`q-matrix-grid`, disabled: a cell is
 * never a button here) and, beside it, an editor for the jobs the company made. Editing is a checklist in
 * the editor and not cell by cell in the matrix, because an edit is one versioned write with a name and a
 * reason, and a matrix of clickable cells would invite ticking forty boxes across six jobs and saving
 * none of them.
 *
 * **What is read:** the jobs (`GET .../roles`, code-owned and the company's own), the vocabulary (`GET
 * .../capabilities`, with what the reader holds and what a company's job may carry) and the grants, only to
 * tell the editor how many people a change will reach. Those are `iam.grant.manage` reads; defining,
 * editing and closing need `iam.role.manage`, and a person without it sees the table and no editor.
 *
 * The editor is opened from the address (`?new`, `?copy=<code>`, `?edit=<code>`), so the library's buttons
 * are plain links and a refresh keeps the form where it was.
 */
@Component({
  selector: 'q-staff-capability-grid-page',
  imports: [TPipe, RouterLink, DeniedState, LockedState, MatrixGrid, StaffJobEditor],
  templateUrl: './staff-capability-grid-page.html',
  styleUrl: './staff-capability-grid-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffCapabilityGridPage {
  private readonly api = inject(StaffApi);
  private readonly tenant = inject(CurrentTenant);
  private readonly capabilities = inject(SessionCapabilities);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly jobNames = inject(StaffJobNames);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly refusal = signal<AccessRefusal | null>(null);
  protected readonly loadError = signal<string | null>(null);

  protected readonly roles = signal<readonly RoleDescriptor[]>([]);
  protected readonly catalogue = signal<readonly CapabilityEntry[]>([]);
  private readonly grants = signal<readonly GrantView[]>([]);

  protected readonly query = signal('');
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly editorError = signal<string | null>(null);

  protected readonly canManage = computed(() => this.capabilities.has('IAM_ROLE_MANAGE'));

  private readonly params = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  /** The editor's mode from the address, resolved against the jobs that were read. Null when it is closed. */
  protected readonly editorMode = computed<JobEditorMode | null>(() => {
    if (!this.canManage()) {
      return null;
    }
    const params = this.params();
    const roles = this.roles();
    if (params.has('new')) {
      return { kind: 'new' };
    }
    const copy = params.get('copy');
    if (copy !== null) {
      const source = roles.find((role) => role.code === copy);
      return source ? { kind: 'copy', source } : null;
    }
    const edit = params.get('edit');
    if (edit !== null) {
      const job = roles.find((role) => role.code === edit && role.custom === true);
      return job ? { kind: 'edit', job, holders: this.holdersOf(job.code) } : null;
    }
    return null;
  });

  private readonly locale = computed(() => this.i18n.locale() as SentenceLocale);

  private readonly rows = computed<readonly CatalogueRow[]>(() =>
    this.catalogue().map((entry) => catalogueRow(entry, this.locale())),
  );

  /** The jobs across the top: the code-owned ones first, as the library lists them, then the company's own. */
  protected readonly columnHeaders = computed<readonly MatrixHeader[]>(() =>
    [...this.roles()]
      .sort((a, b) => Number(a.custom === true) - Number(b.custom === true))
      .map((role) => ({ id: role.code, label: this.jobLabel(role) })),
  );

  /** One matrix per area of what the search leaves, each against every job. */
  protected readonly areas = computed<readonly AreaGrid[]>(() => {
    const needle = this.query().trim().toLowerCase();
    const carried = new Map(this.roles().map((role) => [role.code, new Set(role.capabilities)]));
    const matching = this.rows().filter((row) => matchesCatalogueRow(row, needle));
    return groupByArea(matching, this.i18n.locale()).map((group) => ({
      area: group.area,
      rowHeaders: group.rows.map((row) => ({ id: row.code, label: row.sentence })),
      cells: group.rows.flatMap((row) =>
        this.roles()
          .filter((role) => carried.get(role.code)?.has(row.code) === true)
          .map((role) => ({ rowId: row.code, colId: role.code, state: 'ON' as const })),
      ),
    }));
  });

  constructor() {
    void this.load();
  }

  protected onQueryInput(value: string): void {
    this.query.set(value);
  }

  protected newJob(): void {
    void this.router.navigate([], { queryParams: { new: 1 } });
  }

  protected closeEditor(): void {
    this.editorError.set(null);
    void this.router.navigate([], { queryParams: {} });
  }

  protected async submit(submission: JobSubmission): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    this.busy.set(true);
    this.editorError.set(null);
    try {
      const saved =
        submission.kind === 'create'
          ? await this.api.createJob(tenantId, submission.request)
          : await this.api.updateJob(
              tenantId,
              submission.code,
              submission.request,
              submission.expectedVersion,
            );
      this.notice.set(this.i18n.t('staff.jobEditor.saved', { name: saved.name }));
      this.closeEditor();
      await this.reload(tenantId);
    } catch (error) {
      this.editorError.set(this.describe(error));
      if (error instanceof ApiError && error.code === ApiErrorCode.STALE_VERSION) {
        await this.reload(tenantId);
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected async retire(request: {
    readonly code: string;
    readonly reason: string;
    readonly version: number;
  }): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    this.busy.set(true);
    this.editorError.set(null);
    try {
      const closed = await this.api.retireJob(
        tenantId,
        request.code,
        request.reason,
        request.version,
      );
      this.notice.set(this.i18n.t('staff.jobEditor.retired', { name: closed.name }));
      this.closeEditor();
      await this.reload(tenantId);
    } catch (error) {
      this.editorError.set(this.describe(error));
      await this.reload(tenantId);
    } finally {
      this.busy.set(false);
    }
  }

  private holdersOf(code: string): number {
    return new Set(
      this.grants()
        .filter((grant) => grant.status === 'ACTIVE' && grant.roleCode === code)
        .map((grant) => grant.principalSubject),
    ).size;
  }

  private jobLabel(role: RoleDescriptor): string {
    return roleLabel(
      role.code,
      (key) => this.i18n.t(key),
      role.name ?? this.jobNames.nameOf(role.code),
    );
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    throw error;
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.denied.set(this.tenant.denied());
      this.loading.set(false);
      return;
    }
    try {
      await this.reload(tenantId);
    } catch (error) {
      if (error instanceof ApiError) {
        const refused = accessRefusal(error);
        if (refused) {
          this.refusal.set(refused);
        } else {
          this.loadError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
        }
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async reload(tenantId: string): Promise<void> {
    const [roles, catalogue, grants] = await Promise.all([
      this.api.roles(tenantId),
      this.api.capabilityCatalogue(tenantId),
      this.api.listGrants(tenantId, false),
    ]);
    this.roles.set(roles);
    this.catalogue.set(catalogue);
    this.grants.set(grants);
  }
}
