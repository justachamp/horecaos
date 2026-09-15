import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';

import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';

/** One column a report export may offer. Mirrors `ReportExportDefinition`'s own column vocabulary. */
export interface ExportColumnOption {
  readonly key: string;
  readonly labelKey: MessageKey;
  /** Whether this column belongs to the PII group `customer.pii.export` gates. */
  readonly pii: boolean;
}

/**
 * Row `7.2e`'s column chooser (ADR 0043/ADR 0029, wave P28): every column a
 * report offers, grouped so the PII columns read as their own decision
 * rather than one more checkbox in the list.
 *
 * **The PII group is hidden, not merely disabled, when the operator lacks
 * `customer.pii.export`.** A disabled-but-visible checkbox still tells a
 * principal that a phone column exists to ask for; the row's own brief asks
 * for the omission to be invisible from the request itself, and this is
 * where that starts — `ReportExportService#requestExport` is the second,
 * authoritative place it is enforced, never trusting this component alone
 * (ADR 0025: a client-side hide is a usability affordance, not the
 * authorization decision).
 */
@Component({
  selector: 'q-export-column-chooser',
  imports: [TPipe],
  template: `
    <fieldset class="column-chooser">
      <legend class="q-caption column-chooser__legend">
        {{ 'reports.exportCentre.columnsLabel' | t }}
      </legend>
      @for (column of standardColumns(); track column.key) {
        <label class="column-chooser__option q-body-sm">
          <input
            type="checkbox"
            [checked]="isChecked(column.key)"
            (change)="toggle(column.key, $any($event.target).checked)"
            [attr.data-testid]="'export-column-' + column.key"
          />
          {{ column.labelKey | t }}
        </label>
      }

      @if (piiColumns().length > 0) {
        @if (canIncludePii()) {
          <div class="column-chooser__pii-group" data-testid="export-column-pii-group">
            <span class="q-caption column-chooser__pii-label">{{
              'reports.exportCentre.piiGroupLabel' | t
            }}</span>
            @for (column of piiColumns(); track column.key) {
              <label class="column-chooser__option q-body-sm">
                <input
                  type="checkbox"
                  [checked]="isChecked(column.key)"
                  (change)="toggle(column.key, $any($event.target).checked)"
                  [attr.data-testid]="'export-column-' + column.key"
                />
                {{ column.labelKey | t }}
              </label>
            }
          </div>
        } @else {
          <p class="q-caption column-chooser__pii-hidden" data-testid="export-column-pii-hidden">
            {{ 'reports.exportCentre.piiGroupHidden' | t }}
          </p>
        }
      }
    </fieldset>
  `,
  styles: `
    .column-chooser {
      display: flex;
      flex-direction: column;
      gap: 8px;
      border: 1px solid var(--q-surface-2);
      border-radius: var(--q-radius);
      padding: 12px;
      margin: 0;
    }
    .column-chooser__legend {
      padding: 0 4px;
      color: var(--q-ink-subtle);
    }
    .column-chooser__option {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--q-ink);
    }
    .column-chooser__pii-group {
      display: flex;
      flex-direction: column;
      gap: 8px;
      margin-top: 4px;
      padding-top: 8px;
      border-top: 1px dashed var(--q-surface-2);
    }
    .column-chooser__pii-label {
      color: var(--q-warning-text);
    }
    .column-chooser__pii-hidden {
      margin: 4px 0 0;
      color: var(--q-ink-subtle);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExportColumnChooser {
  private readonly capabilities = inject(SessionCapabilities);

  readonly columns = input.required<readonly ExportColumnOption[]>();
  readonly selected = input<readonly string[]>([]);
  readonly selectedChange = output<readonly string[]>();

  protected readonly canIncludePii = computed(() => this.capabilities.has('CUSTOMER_PII_EXPORT'));

  protected readonly standardColumns = computed(() => this.columns().filter((c) => !c.pii));
  protected readonly piiColumns = computed(() => this.columns().filter((c) => c.pii));

  protected isChecked(key: string): boolean {
    return this.selected().includes(key);
  }

  protected toggle(key: string, checked: boolean): void {
    const current = this.selected();
    const next = checked ? [...current, key] : current.filter((existing) => existing !== key);
    this.selectedChange.emit(next);
  }
}
