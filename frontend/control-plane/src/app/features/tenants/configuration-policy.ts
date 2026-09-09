import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import {
  ConfigurationApi,
  ConfigurationKeyView,
  ConfigurationResolutionView,
  ScopeType,
  SetConfigurationValueRequest,
} from '../platform-config/configuration-api';

/**
 * Key codes this screen offers a Save control for.
 *
 * `ConfigurationKeys` (ADR 0030) declares fourteen keys as of 2026-09-09.
 * Registration alone is not the same as a key doing anything: a
 * repository-wide search for each key's literal code turns up a live
 * `ConfigurationResolver` consumer -- or, for the two audit-retention keys,
 * `AuditPartitionArchiver`'s documented direct-SQL escape hatch, the same
 * one `TrackRetentionSweeper` uses for `telemetry.track_retention_days` --
 * for exactly these seven. The other seven (the ordering, pricing,
 * inventory, platform-locale and notifications keys) pass ADR 0030's
 * startup validator and resolve correctly, so they are safe to read and
 * explain here, but nothing in this build ever reads the stored value back
 * on any request path: a Save button for one of them would write a durable,
 * audited row that the running process will never re-read, exactly the
 * "lie told to an operator" this screen exists not to tell. They stay
 * read-only until a module actually consumes one -- move a code here in the
 * same change that wires the first consumer, not before.
 */
const WRITABLE_KEY_CODES: ReadonlySet<string> = new Set([
  'commercial.enforcement_ceiling',
  'telemetry.courier_collection_gate',
  'telemetry.track_retention_days',
  'audit.security_retention_days',
  'audit.business_retention_days',
  'customers.telegram_auth_phone_pattern',
  'customers.otp_delivery_channel_order',
]);

/**
 * A caution shown above the value field for a key a startup check reads.
 *
 * Writing here still takes effect on the very next resolution -- the
 * resolver's cache is evicted synchronously in the same call that writes
 * (ADR 0030, ADR 0033) -- so these are not read-only. What each note names
 * is a *later* risk: a value that clears today's floor can still fail a
 * future production start if the floor itself moves, or an audit reader
 * that depends on the value staying honest.
 */
const STARTUP_NOTE_KEYS: ReadonlyMap<string, MessageKey> = new Map([
  ['telemetry.track_retention_days', 'configurationPolicy.write.note.trackRetentionFloor'],
  ['audit.security_retention_days', 'configurationPolicy.write.note.auditRetention'],
  ['audit.business_retention_days', 'configurationPolicy.write.note.auditRetention'],
]);

/** What was actually resolved -- the exact key and scope the write form must not drift from. */
interface ResolvedScope {
  readonly code: string;
  readonly scopeType: ScopeType;
  readonly tenantId: string;
  readonly brandId: string;
  readonly locationId: string;
}

/**
 * IA 2.7 Configuration & policy -- platform defaults, tenant overrides, a
 * resolution trace, and now a writer, for any key (ADR 0030: platform ->
 * tenant -> brand -> location -> channel).
 *
 * "-> channel" in the IA row's own parenthetical is not a real level:
 * `ResourceScope.ScopeType` has exactly four (platform, tenant, brand,
 * location), and this picker offers exactly those. There is no fifth,
 * channel-scoped configuration level anywhere in the code this row could
 * point at.
 *
 * <p><strong>The write form only ever appears under a value this screen just
 * resolved.</strong> Resolving is how the operator sees the level they are
 * about to write at, spelled out with its ids rather than left implicit, and
 * {@link scopeMatchesResolution} throws that form away the instant any
 * picker field changes -- so a write can never be submitted against a scope
 * the operator has not just looked at. There is no separate write-only path.
 *
 * <p>Capability is not re-checked here. `app.routes.ts` already gates this
 * whole route on `PLATFORM_ADMIN`, matching the same capability the server
 * requires on both `GET` and `POST` under this controller -- a second,
 * component-local check would be a second mechanism to keep in step with the
 * route guard for no benefit (`core/auth/capability.ts`'s own doc comment:
 * a client-side check is a usability affordance, the API is the enforcement
 * point).
 */
@Component({
  selector: 'app-configuration-policy',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './configuration-policy.html',
  styleUrl: './configuration-policy.css',
})
export class ConfigurationPolicy {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(ConfigurationApi);

  protected readonly loadingKeys = signal(true);
  protected readonly keysError = signal<string | null>(null);
  protected readonly keys = signal<readonly ConfigurationKeyView[]>([]);

  protected readonly keyCode = signal('');
  protected readonly scopeType = signal<ScopeType>('TENANT');
  protected readonly tenantId = signal('');
  protected readonly brandId = signal('');
  protected readonly locationId = signal('');

  protected readonly resolving = signal(false);
  protected readonly resolveError = signal<string | null>(null);
  protected readonly resolution = signal<ConfigurationResolutionView | null>(null);
  private readonly resolvedFor = signal<ResolvedScope | null>(null);

  /** The declared key backing {@link keyCode}, or null while it is blank or unrecognised. */
  protected readonly selectedKey = computed<ConfigurationKeyView | null>(() => {
    const code = this.keyCode().trim();
    return this.keys().find((key) => key.code === code) ?? null;
  });

  protected readonly isWritable = computed(() => {
    const key = this.selectedKey();
    return key !== null && WRITABLE_KEY_CODES.has(key.code);
  });

  protected readonly startupNoteKey = computed(() => {
    const key = this.selectedKey();
    return key === null ? null : (STARTUP_NOTE_KEYS.get(key.code) ?? null);
  });

  protected readonly settableAtCurrentScope = computed(() => {
    const key = this.selectedKey();
    return key !== null && key.settableScopes.includes(this.scopeType());
  });

  protected readonly settableScopesLabel = computed(() => {
    const key = this.selectedKey();
    return key === null ? '' : key.settableScopes.map((scope) => this.scopeLabel(scope)).join(', ');
  });

  /** "Tenant 3f2a…" / "Platform" -- the unmistakable statement of where a write lands. */
  protected readonly currentScopeDescription = computed(() => {
    const label = this.scopeLabel(this.scopeType());
    switch (this.scopeType()) {
      case 'PLATFORM':
        return label;
      case 'TENANT':
        return `${label} ${this.tenantId().trim()}`;
      case 'BRAND':
        return `${label} ${this.brandId().trim()} (${this.i18n.t('configurationPolicy.picker.tenantId')} ${this.tenantId().trim()})`;
      case 'LOCATION':
        return `${label} ${this.locationId().trim()} (${this.i18n.t('configurationPolicy.picker.tenantId')} ${this.tenantId().trim()}, ${this.i18n.t('configurationPolicy.picker.brandId')} ${this.brandId().trim()})`;
    }
  });

  protected scopeLabel(scope: ScopeType): string {
    switch (scope) {
      case 'PLATFORM':
        return this.i18n.t('configurationPolicy.scope.platform');
      case 'TENANT':
        return this.i18n.t('configurationPolicy.scope.tenant');
      case 'BRAND':
        return this.i18n.t('configurationPolicy.scope.brand');
      case 'LOCATION':
        return this.i18n.t('configurationPolicy.scope.location');
    }
  }

  /** True once the picker's fields stop matching what {@link resolution} was actually fetched for. */
  protected readonly scopeMatchesResolution = computed(() => {
    const snapshot = this.resolvedFor();
    return (
      snapshot !== null &&
      snapshot.code === this.keyCode().trim() &&
      snapshot.scopeType === this.scopeType() &&
      snapshot.tenantId === this.tenantId().trim() &&
      snapshot.brandId === this.brandId().trim() &&
      snapshot.locationId === this.locationId().trim()
    );
  });

  protected readonly explicitNull = signal(false);
  protected readonly boolValue = signal(false);
  protected readonly intValue = signal('');
  protected readonly decValue = signal('');
  protected readonly strValue = signal('');
  protected readonly reason = signal('');

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly saveSuccess = signal(false);

  /** The version the write form has to send: what was last read at exactly this key and scope. */
  private readonly expectedVersion = computed(() => this.resolution()?.currentVersionAtScope ?? null);

  protected readonly valueValid = computed(() => {
    if (this.explicitNull()) {
      return true;
    }
    switch (this.selectedKey()?.valueType) {
      case 'Boolean':
        return true;
      case 'Integer':
      case 'Long':
        return /^-?\d+$/.test(this.intValue().trim());
      case 'BigDecimal':
        return /^-?\d+(\.\d+)?$/.test(this.decValue().trim());
      case 'String':
        return this.strValue().trim().length > 0;
      default:
        return false;
    }
  });

  protected readonly canSave = computed(() => {
    return (
      !this.saving() &&
      this.isWritable() &&
      this.settableAtCurrentScope() &&
      this.scopeMatchesResolution() &&
      this.valueValid() &&
      this.reason().trim().length > 0 &&
      this.reason().trim().length <= 1000
    );
  });

  constructor() {
    void this.loadKeys();
  }

  private async loadKeys(): Promise<void> {
    this.loadingKeys.set(true);
    this.keysError.set(null);
    try {
      this.keys.set(await this.api.listKeys());
    } catch (error) {
      this.keysError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loadingKeys.set(false);
    }
  }

  protected canResolve(): boolean {
    if (this.resolving() || this.keyCode().trim().length === 0) {
      return false;
    }
    switch (this.scopeType()) {
      case 'PLATFORM':
        return true;
      case 'TENANT':
        return this.tenantId().trim().length > 0;
      case 'BRAND':
        return this.tenantId().trim().length > 0 && this.brandId().trim().length > 0;
      case 'LOCATION':
        return (
          this.tenantId().trim().length > 0 &&
          this.brandId().trim().length > 0 &&
          this.locationId().trim().length > 0
        );
    }
  }

  protected async resolve(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canResolve()) {
      return;
    }
    this.resolving.set(true);
    this.resolveError.set(null);
    this.saveSuccess.set(false);
    try {
      await this.fetchResolution();
    } catch (error) {
      this.resolveError.set(this.i18n.describe(error as ApiError));
      this.resolution.set(null);
      this.resolvedFor.set(null);
    } finally {
      this.resolving.set(false);
    }
  }

  /** Shared by {@link resolve} and {@link save}'s post-write refresh -- both fetch the same trace. */
  private async fetchResolution(): Promise<void> {
    const code = this.keyCode().trim();
    const scopeType = this.scopeType();
    const tenantId = this.tenantId().trim();
    const brandId = this.brandId().trim();
    const locationId = this.locationId().trim();
    this.resolution.set(
      await this.api.resolve(
        code,
        scopeType,
        tenantId || undefined,
        brandId || undefined,
        locationId || undefined,
      ),
    );
    this.resolvedFor.set({ code, scopeType, tenantId, brandId, locationId });
  }

  /**
   * Resets the write form's own fields after a successful save.
   *
   * Not called on a scope or key change: the template simply stops rendering
   * the form once {@link scopeMatchesResolution} goes false, so there is
   * nothing to clear until the operator resolves again and the form
   * reappears -- clearing it here too would just discard a value still being
   * typed the moment a stray keystroke touched a picker field.
   */
  protected resetWriteForm(): void {
    this.explicitNull.set(false);
    this.boolValue.set(false);
    this.intValue.set('');
    this.decValue.set('');
    this.strValue.set('');
    this.reason.set('');
    this.saveError.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    const key = this.selectedKey();
    if (!this.canSave() || key === null) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    this.saveSuccess.set(false);
    try {
      await this.api.setValue(key.code, this.buildRequest(key));
      // Point 5: show what the platform will actually resolve after the
      // write, not just the row the write itself echoed back.
      await this.fetchResolution();
      this.saveSuccess.set(true);
      this.resetWriteForm();
    } catch (error) {
      this.saveError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.saving.set(false);
    }
  }

  private buildRequest(key: ConfigurationKeyView): SetConfigurationValueRequest {
    const base = {
      scopeType: this.scopeType(),
      tenantId: this.tenantId().trim() || undefined,
      brandId: this.brandId().trim() || undefined,
      locationId: this.locationId().trim() || undefined,
      explicitNull: this.explicitNull(),
      expectedVersion: this.expectedVersion(),
      reason: this.reason().trim(),
    };
    if (this.explicitNull()) {
      return base;
    }
    switch (key.valueType) {
      case 'Boolean':
        return { ...base, booleanValue: this.boolValue() };
      case 'Integer':
      case 'Long':
        return { ...base, integerValue: Number(this.intValue().trim()) };
      case 'BigDecimal':
        return { ...base, decimalValue: Number(this.decValue().trim()) };
      case 'String':
      default:
        return { ...base, stringValue: this.strValue().trim() };
    }
  }
}
