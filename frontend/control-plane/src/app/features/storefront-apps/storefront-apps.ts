import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import {
  AppStatus,
  ClientType,
  StorefrontAppDetail,
  StorefrontAppSummary,
  StorefrontAppsApi,
} from './storefront-apps-api';

/** The contract version a conformance result is recorded against, until the platform serves another. */
const CURRENT_CONTRACT = 'v1';

/**
 * IA 3.6 Storefront apps (ADR 0070) — the platform's registry of who may build a
 * storefront against the published contract.
 *
 * **What a registration decides.** A *public* client is a browser-only app: no secret,
 * an app id, and the origins the platform checks every request against. It is attributable
 * and revocable, and this screen never calls it authenticated. A *confidential* client is
 * server-backed and gets a secret: shown exactly once, here, in the response to the
 * registration or the rotation that minted it, and never again — the registry keeps only
 * a reference to it, and says so.
 *
 * **Every change carries a reason and the version it read.** Suspending an app stops it for
 * every tenant at its next request, so the screen asks for the reason the audit log will
 * show, and the server refuses a change made against a stale read rather than overwriting it.
 *
 * **A conformance result is about the contract being served.** The suite itself runs against
 * a vendor's staging deployment; this screen records what it said. A pass recorded against
 * an earlier contract reads *expired* — the server derives that, so it is shown, not computed
 * here.
 */
@Component({
  selector: 'app-storefront-apps',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './storefront-apps.html',
  styleUrls: ['../compliance/residency-hosting.css', './storefront-apps.css'],
})
export class StorefrontApps {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(StorefrontAppsApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly apps = signal<readonly StorefrontAppSummary[]>([]);

  protected readonly selected = signal<StorefrontAppDetail | null>(null);
  protected readonly detailLoading = signal(false);

  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  /** The secret a registration or rotation just minted. Held here until the operator says it is stored. */
  protected readonly issuedSecret = signal<{ appName: string; value: string } | null>(null);

  // --- registration
  protected readonly registering = signal(false);
  protected readonly newName = signal('');
  protected readonly newVendor = signal('');
  protected readonly newClientType = signal<ClientType>('PUBLIC');
  protected readonly newOrigins = signal('');
  protected readonly newFirstParty = signal(false);
  protected readonly newReason = signal('');

  // --- editing the selected app
  protected readonly editName = signal('');
  protected readonly editVendor = signal('');
  protected readonly editOrigins = signal('');

  // --- one reason for every action on the selected app
  protected readonly reason = signal('');
  protected readonly conformanceResult = signal<'PASSED' | 'FAILED'>('PASSED');
  protected readonly conformanceNote = signal('');

  protected readonly canRegister = computed(
    () =>
      !this.busy() &&
      this.newName().trim().length > 0 &&
      this.newVendor().trim().length > 0 &&
      this.newReason().trim().length > 0 &&
      (this.newClientType() === 'CONFIDENTIAL' || this.parseOrigins(this.newOrigins()).length > 0),
  );

  protected readonly canAct = computed(() => !this.busy() && this.reason().trim().length > 0);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.apps.set(await this.api.list());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected statusKey(status: AppStatus): MessageKey {
    return `storefrontApps.status.${status}` as MessageKey;
  }

  protected conformanceKey(state: string): MessageKey {
    return `storefrontApps.conformance.${state}` as MessageKey;
  }

  protected clientTypeKey(type: ClientType): MessageKey {
    return `storefrontApps.clientType.${type}` as MessageKey;
  }

  protected toggleRegistration(): void {
    this.registering.update((open) => !open);
    this.actionError.set(null);
  }

  protected async register(): Promise<void> {
    if (!this.canRegister()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      const registered = await this.api.register({
        name: this.newName().trim(),
        vendor: this.newVendor().trim(),
        clientType: this.newClientType(),
        firstParty: this.newFirstParty(),
        originAllowlist: this.parseOrigins(this.newOrigins()),
        reason: this.newReason().trim(),
      });
      if (registered.secretValue !== null) {
        this.issuedSecret.set({ appName: registered.app.name, value: registered.secretValue });
      }
      this.actionMessage.set(
        this.i18n.t('storefrontApps.registered', { name: registered.app.name }),
      );
      this.registering.set(false);
      this.resetRegistration();
      await this.load();
      await this.open(registered.app.id);
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }

  protected async open(appId: string): Promise<void> {
    this.detailLoading.set(true);
    this.actionError.set(null);
    try {
      this.show(await this.api.detail(appId));
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.detailLoading.set(false);
    }
  }

  protected close(): void {
    this.selected.set(null);
  }

  protected dismissSecret(): void {
    this.issuedSecret.set(null);
  }

  protected async save(): Promise<void> {
    const detail = this.selected();
    if (detail === null || !this.canAct()) {
      return;
    }
    await this.act(async () => {
      await this.api.update(detail.app, {
        name: this.editName().trim(),
        vendor: this.editVendor().trim(),
        originAllowlist: this.parseOrigins(this.editOrigins()),
        reason: this.reason().trim(),
      });
    }, 'storefrontApps.saved');
  }

  protected async setStatus(status: AppStatus): Promise<void> {
    const detail = this.selected();
    if (detail === null || !this.canAct()) {
      return;
    }
    await this.act(async () => {
      await this.api.changeStatus(detail.app, status, this.reason().trim());
    }, 'storefrontApps.statusChanged');
  }

  protected async rotate(): Promise<void> {
    const detail = this.selected();
    if (detail === null || !this.canAct()) {
      return;
    }
    await this.act(async () => {
      const rotated = await this.api.rotateSecret(detail.app, this.reason().trim());
      if (rotated.secretValue !== null) {
        this.issuedSecret.set({ appName: rotated.app.name, value: rotated.secretValue });
      }
    }, 'storefrontApps.rotated');
  }

  protected async recordConformance(): Promise<void> {
    const detail = this.selected();
    if (detail === null || !this.canAct()) {
      return;
    }
    const note = this.conformanceNote().trim();
    await this.act(async () => {
      await this.api.recordConformance(detail.app, {
        result: this.conformanceResult(),
        contractVersion: CURRENT_CONTRACT,
        note: note.length > 0 ? note : undefined,
        reason: this.reason().trim(),
      });
    }, 'storefrontApps.conformanceRecorded');
  }

  /** Runs one change, then re-reads the app: the version it carries is what the next change must name. */
  private async act(change: () => Promise<void>, doneKey: MessageKey): Promise<void> {
    const detail = this.selected();
    if (detail === null) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      await change();
      this.reason.set('');
      this.actionMessage.set(this.i18n.t(doneKey));
      this.show(await this.api.detail(detail.app.id));
      this.apps.set(await this.api.list());
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }

  private show(detail: StorefrontAppDetail): void {
    this.selected.set(detail);
    this.editName.set(detail.app.name);
    this.editVendor.set(detail.app.vendor);
    this.editOrigins.set(detail.app.originAllowlist.join('\n'));
  }

  private resetRegistration(): void {
    this.newName.set('');
    this.newVendor.set('');
    this.newClientType.set('PUBLIC');
    this.newOrigins.set('');
    this.newFirstParty.set(false);
    this.newReason.set('');
  }

  /** One origin per line or comma; blank lines are not origins. */
  protected parseOrigins(text: string): string[] {
    return text
      .split(/[\n,]/)
      .map((line) => line.trim())
      .filter((line) => line.length > 0);
  }
}
