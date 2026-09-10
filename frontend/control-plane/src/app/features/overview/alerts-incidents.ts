import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { IncidentView, IncidentsApi } from './incidents-api';

/** Alert classes this console has words for; any other shows its code. */
const KNOWN_CLASSES = new Set(['CONTROL_BAND_ESCALATED', 'ONBOARDING_RUN_STUCK']);

/**
 * IA 1.2 Alerts & incidents -- every platform alert, kept until someone
 * resolves it.
 *
 * An alert raised again while its incident is open counts another occurrence
 * instead of adding a row. Acknowledging says someone has it; resolving
 * closes it with what was done. A resolved incident that comes back opens a
 * new one, so the record of the first stays as it was.
 */
@Component({
  selector: 'app-alerts-incidents',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './alerts-incidents.html',
  styleUrl: './alerts-incidents.css',
})
export class AlertsIncidents {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(IncidentsApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly incidents = signal<readonly IncidentView[]>([]);
  protected readonly includeResolved = signal(false);
  protected readonly acting = signal<{ id: string; mode: 'acknowledge' | 'resolve' } | null>(null);
  protected readonly note = signal('');
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.incidents.set(await this.api.list(this.includeResolved()));
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected toggleResolved(on: boolean): void {
    this.includeResolved.set(on);
    void this.load();
  }

  protected title(incident: IncidentView): string {
    return KNOWN_CLASSES.has(incident.eventClass)
      ? this.i18n.t(`alertsIncidents.class.${incident.eventClass}` as MessageKey)
      : incident.eventClass;
  }

  protected statusKey(status: string): MessageKey {
    return `alertsIncidents.status.${status}` as MessageKey;
  }

  protected entries(variables: Readonly<Record<string, string>>): readonly [string, string][] {
    return Object.entries(variables);
  }

  protected open(incident: IncidentView, mode: 'acknowledge' | 'resolve'): void {
    const current = this.acting();
    this.acting.set(current?.id === incident.id && current.mode === mode ? null : { id: incident.id, mode });
    this.note.set('');
    this.actionError.set(null);
  }

  protected async confirm(incident: IncidentView): Promise<void> {
    const action = this.acting();
    const note = this.note().trim();
    if (action === null || note.length === 0 || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    try {
      if (action.mode === 'acknowledge') {
        await this.api.acknowledge(incident.id, note);
      } else {
        await this.api.resolve(incident.id, note);
      }
      this.acting.set(null);
      await this.load();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
