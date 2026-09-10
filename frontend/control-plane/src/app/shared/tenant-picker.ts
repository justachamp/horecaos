import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { I18nService } from '../core/i18n/i18n.service';
import { TenantDirectory } from './tenant-directory';

/**
 * Choosing a tenant by name, for every screen that works on one tenant at a
 * time. Emits the tenant's id; the screen decides what to load with it.
 */
@Component({
  selector: 'app-tenant-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <label class="tenantPicker">
      <span class="q-caption">{{ i18n.t('tenantPicker.label') }}</span>
      <select class="q-body-sm" name="tenant" [value]="value()" (change)="choose($any($event.target).value)">
        <option value="">
          {{ directory.loaded() ? i18n.t('tenantPicker.placeholder') : i18n.t('tenants.loading') }}
        </option>
        @for (tenant of directory.tenants(); track tenant.id) {
          <option [value]="tenant.id" [selected]="tenant.id === value()">{{ tenant.displayName }} · {{ tenant.slug }}</option>
        }
      </select>
    </label>
    @if (directory.failed()) {
      <p class="q-body-sm pickerError">{{ i18n.t('tenantPicker.failed') }}</p>
    }
  `,
  styles: `
    .tenantPicker {
      display: flex;
      flex-direction: column;
      gap: 4px;
      max-width: 420px;
    }
    .tenantPicker select {
      border: 1px solid var(--q-hairline);
      padding: 8px;
      background: var(--q-canvas);
      color: var(--q-ink);
    }
    .pickerError {
      color: var(--q-error-text);
    }
  `,
})
export class TenantPicker {
  protected readonly i18n = inject(I18nService);
  protected readonly directory = inject(TenantDirectory);

  /** The tenant currently shown by the screen, or '' for none. */
  readonly value = input<string>('');
  readonly tenantChange = output<string>();

  constructor() {
    void this.directory.load();
  }

  protected choose(tenantId: string): void {
    this.directory.choose(tenantId);
    this.tenantChange.emit(tenantId);
  }
}
