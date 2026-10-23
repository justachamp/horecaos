import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  untracked,
} from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { SentenceLocale } from './capability-sentences';
import { CatalogueRow, catalogueRow, groupByArea, matchesCatalogueRow } from './job-catalogue';
import {
  CapabilityEntry,
  CreateJobRequest,
  RoleDescriptor,
  ScopeType,
  UpdateJobRequest,
} from './staff-api';
import { StaffJobNames } from './staff-job-names';
import { roleLabel, scopeLevelLabel } from './staff-role-labels';

/** What the editor was opened for. */
export type JobEditorMode =
  | { readonly kind: 'new' }
  | { readonly kind: 'copy'; readonly source: RoleDescriptor }
  | { readonly kind: 'edit'; readonly job: RoleDescriptor; readonly holders: number };

/** The levels a company can give a job at (never the platform). */
export type JobLevel = Exclude<ScopeType, 'PLATFORM'>;

const LEVELS: readonly JobLevel[] = ['TENANT', 'BRAND', 'LOCATION'];
const MAX_NAME_LENGTH = 80;

/** What the editor asks its host to do. The host owns the calls, as the job dialog's host does. */
export type JobSubmission =
  | { readonly kind: 'create'; readonly request: CreateJobRequest }
  | {
      readonly kind: 'update';
      readonly code: string;
      readonly request: UpdateJobRequest;
      readonly expectedVersion: number;
    };

/**
 * The job editor (ADR 0186, gap-map row `9.1b`): a name, the level a job is given at, the things it can
 * do, and why - for a new job, a copy of an existing one (a "template" is a copy), or a change to one the
 * company already made.
 *
 * **What a person may tick is what she holds.** Every capability in a job must be one its author holds
 * company-wide (`TenantRoleService`), and the catalogue says which she holds and which a company's job can
 * carry at all. A row she cannot give is shown with the reason and cannot be ticked - the same "never
 * taunt her with what she cannot do" rule as the job picker (staff-and-access.md section 0), except that
 * here the row stays, locked, because the table is the vocabulary and a missing row would misreport what
 * the owner's own job holds.
 *
 * **An edit is live.** The note under the name says how many people hold the job and that a change reaches
 * them at their next request; the reason is required and goes to the activity log.
 *
 * **A copy is trimmed, not refused.** Copying a job whose capabilities she does not all hold carries over
 * the ones she may give and says how many were left out.
 */
@Component({
  selector: 'q-staff-job-editor',
  imports: [TPipe],
  templateUrl: './staff-job-editor.html',
  styleUrl: './staff-job-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffJobEditor {
  readonly catalogue = input.required<readonly CapabilityEntry[]>();
  readonly mode = input.required<JobEditorMode>();
  readonly busy = input(false);
  readonly serverError = input<string | null>(null);

  readonly submitted = output<JobSubmission>();
  readonly retired = output<{
    readonly code: string;
    readonly reason: string;
    readonly version: number;
  }>();
  readonly dismiss = output<void>();

  protected readonly i18n = inject(I18n);
  private readonly jobNames = inject(StaffJobNames);

  protected readonly levels = LEVELS;
  protected readonly maxNameLength = MAX_NAME_LENGTH;

  // Each field is seeded from the mode it was opened for, and starts over when the host opens it for
  // another job or another mode. Not an effect, and not written from the template.
  protected readonly name = linkedSignal<string>(() => {
    const mode = this.mode();
    return mode.kind === 'edit' ? (mode.job.name ?? '') : '';
  });
  protected readonly level = linkedSignal<JobLevel>(() => {
    const mode = this.mode();
    switch (mode.kind) {
      case 'new':
        return 'LOCATION';
      case 'copy':
        return levelOf(mode.source.scopeType);
      case 'edit':
        return levelOf(mode.job.scopeType);
    }
  });
  protected readonly ticked = linkedSignal<ReadonlySet<string>>(() => {
    const mode = this.mode();
    switch (mode.kind) {
      case 'new':
        return new Set<string>();
      case 'copy':
        // The catalogue is read untracked: a reload of the vocabulary must not wipe a half-made copy.
        return new Set(
          untracked(() => mode.source.capabilities.filter((code) => this.mayGive(code))),
        );
      case 'edit':
        return new Set(mode.job.capabilities);
    }
  });
  protected readonly search = linkedSignal<string>(() => {
    this.mode();
    return '';
  });
  protected readonly reason = linkedSignal<string>(() => {
    this.mode();
    return '';
  });
  protected readonly retireReason = linkedSignal<string>(() => {
    this.mode();
    return '';
  });
  protected readonly touched = linkedSignal<boolean>(() => {
    this.mode();
    return false;
  });
  protected readonly retiring = linkedSignal<boolean>(() => {
    this.mode();
    return false;
  });

  protected readonly rows = computed<readonly CatalogueRow[]>(() => {
    const locale = this.i18n.locale() as SentenceLocale;
    return this.catalogue().map((entry) => catalogueRow(entry, locale));
  });

  private readonly rowByCode = computed(() => new Map(this.rows().map((row) => [row.code, row])));

  protected readonly groups = computed(() => {
    const needle = this.search().trim().toLowerCase();
    const matching = this.rows().filter((row) => matchesCatalogueRow(row, needle));
    return groupByArea(matching, this.i18n.locale());
  });

  /** Everything ticked that the author does not hold: it can be taken out and not put back. */
  protected readonly cannotGive = computed(() =>
    [...this.ticked()].filter((code) => {
      const row = this.rowByCode().get(code);
      return row !== undefined && (!row.held || !row.composable);
    }),
  );

  /** In a copy: how many of the source's capabilities were left out because she cannot give them. */
  protected readonly trimmed = computed(() => {
    const mode = this.mode();
    if (mode.kind !== 'copy') {
      return 0;
    }
    return mode.source.capabilities.filter((code) => !this.mayGive(code)).length;
  });

  protected readonly nameMissing = computed(() => this.touched() && this.name().trim() === '');
  protected readonly reasonMissing = computed(() => this.touched() && this.reason().trim() === '');
  protected readonly noneTicked = computed(() => this.touched() && this.ticked().size === 0);

  protected readonly canSave = computed(
    () =>
      !this.busy() &&
      this.name().trim() !== '' &&
      this.name().trim().length <= MAX_NAME_LENGTH &&
      this.ticked().size > 0 &&
      this.reason().trim() !== '' &&
      this.cannotGive().length === 0,
  );

  protected readonly editing = computed(() => {
    const mode = this.mode();
    return mode.kind === 'edit' ? mode : null;
  });

  protected readonly heading = computed(() => {
    const mode = this.mode();
    switch (mode.kind) {
      case 'new':
        return this.i18n.t('staff.jobEditor.title.new');
      case 'copy':
        return this.i18n.t('staff.jobEditor.title.copy', { name: this.sourceName(mode.source) });
      case 'edit':
        return this.i18n.t('staff.jobEditor.title.edit', { name: this.sourceName(mode.job) });
    }
  });

  protected readonly canRetire = computed(() => {
    const mode = this.editing();
    return mode !== null && mode.holders === 0 && this.retireReason().trim() !== '' && !this.busy();
  });

  protected scopeLabel(level: ScopeType): string {
    return scopeLevelLabel(level, (key) => this.i18n.t(key));
  }

  protected lockReason(row: CatalogueRow): string | null {
    if (!row.composable) {
      return this.i18n.t('staff.jobEditor.capabilities.never');
    }
    if (!row.held) {
      return this.i18n.t('staff.jobEditor.capabilities.notHeld');
    }
    return null;
  }

  protected isTicked(code: string): boolean {
    return this.ticked().has(code);
  }

  /** A row can be ticked when she holds it and a company's job may carry it; any ticked row can be unticked. */
  protected canToggle(row: CatalogueRow): boolean {
    return this.isTicked(row.code) || (row.held && row.composable);
  }

  protected toggle(row: CatalogueRow): void {
    if (!this.canToggle(row)) {
      return;
    }
    this.ticked.update((previous) => {
      const next = new Set(previous);
      if (next.has(row.code)) {
        next.delete(row.code);
      } else {
        next.add(row.code);
      }
      return next;
    });
  }

  protected onSearch(value: string): void {
    this.search.set(value);
  }

  protected save(): void {
    this.touched.set(true);
    if (!this.canSave()) {
      return;
    }
    const capabilities = [...this.ticked()].sort();
    const name = this.name().trim();
    const reason = this.reason().trim();
    const mode = this.mode();
    if (mode.kind === 'edit') {
      this.submitted.emit({
        kind: 'update',
        code: mode.job.code,
        request: { name, capabilities, reason },
        expectedVersion: mode.job.version ?? 0,
      });
      return;
    }
    const copiedFrom = mode.kind === 'copy' ? mode.source.code : undefined;
    this.submitted.emit({
      kind: 'create',
      request: {
        name,
        scopeType: this.level(),
        capabilities,
        ...(copiedFrom === undefined ? {} : { copiedFrom }),
        reason,
      },
    });
  }

  protected confirmRetire(): void {
    const mode = this.editing();
    if (mode === null || !this.canRetire()) {
      return;
    }
    this.retired.emit({
      code: mode.job.code,
      reason: this.retireReason().trim(),
      version: mode.job.version ?? 0,
    });
  }

  private mayGive(code: string): boolean {
    const row = this.rowByCode().get(code);
    return row !== undefined && row.held && row.composable;
  }

  private sourceName(job: RoleDescriptor): string {
    return roleLabel(
      job.code,
      (key) => this.i18n.t(key),
      job.name ?? this.jobNames.nameOf(job.code),
    );
  }
}

function levelOf(scopeType: ScopeType): JobLevel {
  return scopeType === 'PLATFORM' ? 'TENANT' : scopeType;
}
