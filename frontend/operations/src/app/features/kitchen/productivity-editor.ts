import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { ConfigurationResolutionView, ConfigurationScopeType } from '../../core/api/configuration';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { InheritedField } from '../../shared/ui/inherited-field/inherited-field';
import { describeApiError } from '../orders/order-errors';
import {
  COOKING_ROLES,
  CookingRole,
  HeadcountApi,
  PolicyScope,
  ProductivityDocument,
} from './headcount-api';
import { StationResponse } from './kitchen-api';

const MIN_FIGURE = 1;
const MAX_FIGURE = 1000;

/**
 * The tenant's own statement of how many portions one cook produces in an hour (ADR 0163 Decision 4),
 * shown as a `q-inherited-field` (set here, inherited from, trace) and edited as one whole document.
 *
 * **The platform supplies no number.** An empty document is the default, and a station with no figure
 * answers «no pace» in the plan above. The figures are a pace and never a person: nothing here names
 * or is keyed to an employee.
 *
 * **Replace, not merge** (ADR 0030). A document at a level replaces the one above it as a whole, so the
 * form opens pre-filled with what is in force at the chosen level and saves all of it; the brand level is
 * where a cuisine's different pace is stated, the location level is where one branch's stove is. There is
 * no «revert to inherit»: a published version is never withdrawn, so the field is told not to offer it.
 *
 * **Concurrency.** The form is opened at the `versionAtScope` the read reported and sends it back as
 * `If-Match`; a second manager publishing underneath gets `STALE_VERSION` here and the only way forward
 * is to reopen, which drops the stale edit.
 */
@Component({
  selector: 'q-productivity-editor',
  imports: [TPipe, InheritedField],
  templateUrl: './productivity-editor.html',
  styleUrl: './productivity-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductivityEditor implements OnInit {
  private readonly api = inject(HeadcountApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  /** The branch's stations, for the per-station overrides; the pass is never offered. */
  readonly stations = input<readonly StationResponse[]>([]);
  /** Holds `kitchen.productivity.manage`: without it the figures are shown and not editable. */
  readonly canManage = input(false);

  /** A publication landed; the plan above recomputes from it. */
  readonly saved = output<void>();

  protected readonly roles: readonly CookingRole[] = COOKING_ROLES;
  protected readonly editableScopes: readonly ConfigurationScopeType[] = [
    'TENANT',
    'BRAND',
    'LOCATION',
  ];
  protected readonly levels: readonly PolicyScope[] = ['LOCATION', 'BRAND', 'TENANT'];

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  /** What is in force at this branch: the figures the plan divides by. */
  protected readonly view = signal<ProductivityDocument | null>(null);

  protected readonly editing = signal(false);
  protected readonly level = signal<PolicyScope>('LOCATION');
  /** The document at the level being edited, and so the version the save is checked against. */
  protected readonly editView = signal<ProductivityDocument | null>(null);
  protected readonly roleDraft = signal<Readonly<Record<string, string>>>({});
  protected readonly stationDraft = signal<Readonly<Record<string, string>>>({});
  protected readonly reason = signal('');
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly stale = signal(false);
  protected readonly justSaved = signal(false);

  /** The cooking stations an override can name, in the order the branch lists them. */
  protected readonly cookingStations = computed(() =>
    this.stations().filter((station) => station.role !== 'EXPO' && station.status === 'ACTIVE'),
  );

  protected readonly resolution = computed<ConfigurationResolutionView | null>(() => {
    const current = this.view();
    if (!current) {
      return null;
    }
    return {
      keyCode: 'kitchen.station_productivity',
      value: summaryOf(current),
      cameFromDefault: current.isDefault,
      source: current.isDefault ? 'CODE_DEFAULT' : 'SCOPED_VALUE',
      winningScope: current.winningScope,
      inspectedLevels: current.levels
        .filter((level) => level.scopeType !== 'PLATFORM')
        .map((level) => ({
          scopeType: level.scopeType,
          outcome: level.authored ? ('VALUE' as const) : ('NOT_SET' as const),
          version: null,
          changedByName: null,
          changedAt: null,
        })),
      describe: `kitchen.station_productivity -> ${current.winningScope ?? 'DEFAULT'}`,
      currentVersionAtScope: current.versionAtScope > 0 ? current.versionAtScope : null,
    };
  });

  protected readonly invalid = computed(() => {
    if (this.reason().trim().length === 0) {
      return true;
    }
    return ![...Object.values(this.roleDraft()), ...Object.values(this.stationDraft())].every(
      (value) => value.trim() === '' || isFigure(value),
    );
  });

  protected readonly canSave = computed(
    () => !this.saving() && !this.stale() && !this.invalid() && this.editView() !== null,
  );

  protected readonly formatValue = (value: unknown): string =>
    typeof value === 'string' && value.length > 0 ? value : this.i18n.t('kitchen.pace.none');

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected async startEditing(): Promise<void> {
    this.level.set('LOCATION');
    this.reason.set('');
    this.saveError.set(null);
    this.stale.set(false);
    this.justSaved.set(false);
    this.editing.set(true);
    await this.openLevel('LOCATION');
  }

  protected cancelEditing(): void {
    this.editing.set(false);
    this.saveError.set(null);
    this.stale.set(false);
  }

  protected async onLevelChange(level: PolicyScope): Promise<void> {
    this.level.set(level);
    this.saveError.set(null);
    this.stale.set(false);
    await this.openLevel(level);
  }

  protected setRole(role: string, value: string): void {
    this.roleDraft.update((draft) => ({ ...draft, [role]: value }));
  }

  protected setStation(code: string, value: string): void {
    this.stationDraft.update((draft) => ({ ...draft, [code]: value }));
  }

  protected roleLabel(role: CookingRole): string {
    switch (role) {
      case 'HOT':
        return this.i18n.t('kitchen.pace.role.HOT');
      case 'COLD':
        return this.i18n.t('kitchen.pace.role.COLD');
      case 'GRILL':
        return this.i18n.t('kitchen.pace.role.GRILL');
      case 'BAR':
        return this.i18n.t('kitchen.pace.role.BAR');
      case 'BAKERY':
        return this.i18n.t('kitchen.pace.role.BAKERY');
      case 'PACKING':
        return this.i18n.t('kitchen.pace.role.PACKING');
    }
  }

  protected levelLabel(level: PolicyScope): string {
    switch (level) {
      case 'LOCATION':
        return this.i18n.t('kitchen.pace.scope.location');
      case 'BRAND':
        return this.i18n.t('kitchen.pace.scope.brand');
      case 'TENANT':
        return this.i18n.t('kitchen.pace.scope.tenant');
    }
  }

  protected stationName(station: StationResponse): string {
    switch (this.i18n.locale()) {
      case 'uz-Latn':
        return station.displayNameUz;
      case 'en':
        return station.displayNameEn;
      default:
        return station.displayNameRu;
    }
  }

  protected async save(): Promise<void> {
    const document = this.editView();
    if (!document || !this.canSave()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    try {
      await this.api.publishProductivity(
        this.scope(),
        this.level(),
        {
          roles: figuresOf(this.roleDraft()),
          stations: figuresOf(this.stationDraft()),
          reason: this.reason().trim(),
        },
        document.versionAtScope,
      );
      this.editing.set(false);
      this.justSaved.set(true);
      await this.load();
      this.saved.emit();
    } catch (error) {
      if (error instanceof ApiError && error.code === 'STALE_VERSION') {
        this.stale.set(true);
      } else if (error instanceof ApiError) {
        this.saveError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.saving.set(false);
    }
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    try {
      this.view.set(await this.api.productivity(this.scope(), 'LOCATION'));
      this.loadError.set(null);
    } catch (error) {
      if (error instanceof ApiError) {
        this.loadError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  /** Reads the document at the chosen level and pre-fills the form with it: a save replaces it whole. */
  private async openLevel(level: PolicyScope): Promise<void> {
    this.editView.set(null);
    try {
      const document = await this.api.productivity(this.scope(), level);
      this.editView.set(document);
      this.roleDraft.set(draftOf(document.roles));
      this.stationDraft.set(draftOf(document.stations));
    } catch (error) {
      if (error instanceof ApiError) {
        this.saveError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    }
  }
}

function isFigure(value: string): boolean {
  const trimmed = value.trim();
  if (!/^\d+$/.test(trimmed)) {
    return false;
  }
  const figure = Number(trimmed);
  return figure >= MIN_FIGURE && figure <= MAX_FIGURE;
}

function figuresOf(draft: Readonly<Record<string, string>>): Record<string, number> {
  const figures: Record<string, number> = {};
  for (const [key, value] of Object.entries(draft)) {
    if (value.trim() !== '') {
      figures[key] = Number(value.trim());
    }
  }
  return figures;
}

function draftOf(figures: Readonly<Record<string, number>>): Record<string, string> {
  const draft: Record<string, string> = {};
  for (const [key, value] of Object.entries(figures)) {
    draft[key] = String(value);
  }
  return draft;
}

/** «GRILL 20 · HOT 30 · GRILL-1 35»: the figures in force, roles first. Empty when none is stated. */
function summaryOf(document: ProductivityDocument): string {
  return [
    ...Object.entries(document.roles).map(([role, figure]) => `${role} ${figure}`),
    ...Object.entries(document.stations).map(([code, figure]) => `${code} ${figure}`),
  ].join(' · ');
}
