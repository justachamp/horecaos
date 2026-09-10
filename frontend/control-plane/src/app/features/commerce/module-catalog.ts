import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { asDate } from '../../core/api/dates';
import { ENTRY_CURRENCIES, parseAmount } from '../../core/api/money';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import { BILLING_UNITS, CommerceApi, EntitlementKeyView, ModuleView, TenantModuleView } from './commerce-api';

const MODULE_CODE = /^[a-z][a-z0-9_-]{0,63}$/;

/** A pending act on one row: which row, and what is being done to it. */
interface Acting {
  readonly id: string;
  readonly mode: 'activate' | 'retire' | 'end';
}

/**
 * IA 5.2 Module catalog -- modules sold beside the plans, each on its own
 * billing unit, and which modules each tenant has.
 *
 * A module is billed per tenant, per brand, per branch, per unit (a kiosk, a
 * courier service) or once (a white-label app), and may switch on features the
 * plan leaves off. Like a plan version it is drafted by one person and put on
 * sale by another, and its price never changes after that.
 */
@Component({
  selector: 'app-module-catalog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker],
  templateUrl: './module-catalog.html',
  styleUrls: ['./plan-catalog.css', './module-catalog.css'],
})
export class ModuleCatalog {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(CommerceApi);
  private readonly directory = inject(TenantDirectory);

  protected readonly currencies = ENTRY_CURRENCIES;
  protected readonly units = BILLING_UNITS;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly modules = signal<readonly ModuleView[]>([]);
  protected readonly features = signal<readonly EntitlementKeyView[]>([]);

  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);
  protected readonly acting = signal<Acting | null>(null);
  protected readonly actingReason = signal('');

  protected readonly drafting = signal(false);
  protected readonly code = signal('');
  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly unit = signal<string>('PER_LOCATION');
  protected readonly currency = signal('UZS');
  protected readonly price = signal('');
  protected readonly chosenFeatures = signal<ReadonlySet<string>>(new Set());
  protected readonly draftReason = signal('');

  protected readonly tenantId = signal(this.directory.selected());
  protected readonly tenantModules = signal<readonly TenantModuleView[]>([]);
  protected readonly tenantError = signal<string | null>(null);
  protected readonly addModule = signal('');
  protected readonly addQuantity = signal('');
  protected readonly addReason = signal('');

  protected readonly onSale = computed(() => this.modules().filter((module) => module.status === 'ACTIVE'));
  protected readonly chosenForAdd = computed(() => this.onSale().find((module) => module.moduleId === this.addModule()));

  constructor() {
    void this.load();
    if (this.tenantId().length > 0) {
      void this.loadTenant();
    }
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [modules, keys] = await Promise.all([this.api.listModules(), this.api.entitlementKeys()]);
      this.modules.set(modules);
      this.features.set(keys.filter((key) => !key.counted));
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected unitKey(unit: string): MessageKey {
    return `moduleCatalog.unit.${unit}` as MessageKey;
  }

  protected statusKey(status: string): MessageKey {
    return `planCatalog.status.${status}` as MessageKey;
  }

  protected who(subject: string | null): string {
    if (subject === null || subject.length === 0) {
      return '—';
    }
    return subject === this.session.current()?.subject ? this.i18n.t('planCatalog.you') : subject;
  }

  protected draftedByMe(module: ModuleView): boolean {
    return module.createdBy === this.session.current()?.subject;
  }

  // ------------------------------------------------------------- draft

  protected toggleDraft(): void {
    this.drafting.set(!this.drafting());
    this.actionError.set(null);
  }

  protected toggleFeature(code: string, on: boolean): void {
    const next = new Set(this.chosenFeatures());
    if (on) {
      next.add(code);
    } else {
      next.delete(code);
    }
    this.chosenFeatures.set(next);
  }

  protected priceMinor(): number | null {
    return parseAmount(this.price(), this.currency());
  }

  protected canDraft(): boolean {
    return (
      !this.busy() &&
      MODULE_CODE.test(this.code()) &&
      this.name().trim().length > 0 &&
      this.priceMinor() !== null &&
      this.draftReason().trim().length > 0
    );
  }

  protected async draft(event: Event): Promise<void> {
    event.preventDefault();
    const unitPriceMinor = this.priceMinor();
    if (!this.canDraft() || unitPriceMinor === null) {
      return;
    }
    const description = this.description().trim();
    await this.run(async () => {
      await this.api.draftModule({
        code: this.code(),
        name: this.name().trim(),
        description: description.length > 0 ? description : undefined,
        billingUnit: this.unit(),
        currency: this.currency(),
        unitPriceMinor,
        featureKeys: [...this.chosenFeatures()],
        reason: this.draftReason().trim(),
      });
      this.drafting.set(false);
      this.code.set('');
      this.name.set('');
      this.description.set('');
      this.price.set('');
      this.chosenFeatures.set(new Set());
      this.draftReason.set('');
      return this.i18n.t('moduleCatalog.draft.done');
    });
  }

  // ---------------------------------------------------- activate, retire, end

  protected open(id: string, mode: Acting['mode']): void {
    const current = this.acting();
    this.acting.set(current?.id === id && current.mode === mode ? null : { id, mode });
    this.actingReason.set('');
    this.actionError.set(null);
  }

  protected isActing(id: string, mode: Acting['mode']): boolean {
    const current = this.acting();
    return current !== null && current.id === id && current.mode === mode;
  }

  protected async confirmModule(module: ModuleView): Promise<void> {
    const action = this.acting();
    const reason = this.actingReason().trim();
    if (action === null || reason.length === 0 || this.busy()) {
      return;
    }
    await this.run(async () => {
      if (action.mode === 'activate') {
        await this.api.activateModule(module.moduleId, reason);
      } else {
        await this.api.retireModule(module.moduleId, reason);
      }
      this.acting.set(null);
      return this.i18n.t(action.mode === 'activate' ? 'moduleCatalog.activate.done' : 'moduleCatalog.retire.done', {
        code: module.code,
      });
    });
  }

  // ------------------------------------------------------------- tenants

  protected chooseTenant(tenantId: string): void {
    this.tenantId.set(tenantId);
    this.tenantModules.set([]);
    this.tenantError.set(null);
    if (tenantId.length > 0) {
      void this.loadTenant();
    }
  }

  private async loadTenant(): Promise<void> {
    try {
      this.tenantModules.set(await this.api.tenantModules(this.tenantId()));
    } catch (error) {
      this.tenantError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected canAdd(): boolean {
    const module = this.chosenForAdd();
    if (this.busy() || module === undefined || this.addReason().trim().length === 0) {
      return false;
    }
    return module.billingUnit !== 'PER_UNIT' || /^[1-9]\d*$/.test(this.addQuantity().trim());
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    const module = this.chosenForAdd();
    if (!this.canAdd() || module === undefined) {
      return;
    }
    const quantity = module.billingUnit === 'PER_UNIT' ? Number(this.addQuantity().trim()) : undefined;
    await this.run(async () => {
      await this.api.addTenantModule(this.tenantId(), module.moduleId, this.addReason().trim(), quantity);
      this.addModule.set('');
      this.addQuantity.set('');
      this.addReason.set('');
      await this.loadTenant();
      return this.i18n.t('moduleCatalog.tenant.added', { code: module.code });
    });
  }

  protected async end(held: TenantModuleView): Promise<void> {
    const reason = this.actingReason().trim();
    if (reason.length === 0 || this.busy()) {
      return;
    }
    await this.run(async () => {
      await this.api.endTenantModule(this.tenantId(), held.tenantModuleId, reason);
      this.acting.set(null);
      await this.loadTenant();
      return this.i18n.t('moduleCatalog.tenant.ended', { code: held.moduleCode });
    });
  }

  private async run(write: () => Promise<string>): Promise<void> {
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      this.actionMessage.set(await write());
      await this.load();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
