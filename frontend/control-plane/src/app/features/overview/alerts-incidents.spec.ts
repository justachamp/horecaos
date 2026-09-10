import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { SessionContextService } from '../../core/auth/session-context.service';
import { ru } from '../../core/i18n/messages.ru';
import { AlertsIncidents } from './alerts-incidents';
import { IncidentView, IncidentsApi } from './incidents-api';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const OPEN: IncidentView = {
  id: 'inc-1', eventClass: 'CONTROL_BAND_ESCALATED', subjectType: 'CONTROL_BAND', subjectId: 'outbox.dead',
  variables: { band: 'outbox.dead', value: '12' }, firstRaisedAt: '2026-09-10T08:00:00Z', lastRaisedAt: '2026-09-10T09:00:00Z',
  occurrences: 3, status: 'OPEN', acknowledgedBy: null, acknowledgedAt: null, resolvedBy: null, resolvedAt: null, resolutionNote: null,
};
const UNKNOWN_CLASS: IncidentView = { ...OPEN, id: 'inc-2', eventClass: 'SOMETHING_NEW', subjectId: 'x', variables: {} };

describe('AlertsIncidents', () => {
  let fixture: ComponentFixture<AlertsIncidents>;
  let api: { list: ReturnType<typeof vi.fn>; acknowledge: ReturnType<typeof vi.fn>; resolve: ReturnType<typeof vi.fn> };

  async function create(canManage = true, incidents: IncidentView[] = [OPEN, UNKNOWN_CLASS]): Promise<void> {
    api = {
      list: vi.fn().mockResolvedValue(incidents),
      acknowledge: vi.fn().mockResolvedValue(undefined),
      resolve: vi.fn().mockResolvedValue(undefined),
    };
    localStorage.clear();
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [AlertsIncidents],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: IncidentsApi, useValue: api },
        { provide: SessionContextService, useValue: { has: () => canManage, current: () => ({ subject: 'me' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AlertsIncidents);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('names a known alert in words and shows an unknown one by its code', async () => {
    await create();

    expect(text()).toContain(ru['alertsIncidents.class.CONTROL_BAND_ESCALATED']);
    expect(text()).toContain('SOMETHING_NEW');
    expect(text()).toContain('outbox.dead');
    expect(api.list).toHaveBeenCalledWith(false);
  });

  it('acknowledges only once a note says what is being done', async () => {
    await create();
    const first = fixture.nativeElement.querySelector('.incident') as HTMLElement;
    (first.querySelector('.ackToggle') as HTMLButtonElement).click();
    await settle();

    const confirm = first.querySelector('.confirmIncident') as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);

    const note = first.querySelector('[name="incidentNote"]') as HTMLInputElement;
    note.value = 'looking at the relay';
    note.dispatchEvent(new Event('input'));
    await settle();
    (first.querySelector('.confirmIncident') as HTMLButtonElement).click();
    await settle();

    expect(api.acknowledge).toHaveBeenCalledWith('inc-1', 'looking at the relay');
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('resolves with what was done', async () => {
    await create();
    const first = fixture.nativeElement.querySelector('.incident') as HTMLElement;
    (first.querySelector('.resolveToggle') as HTMLButtonElement).click();
    await settle();
    const note = first.querySelector('[name="incidentNote"]') as HTMLInputElement;
    note.value = 'replayed the dead letters';
    note.dispatchEvent(new Event('input'));
    await settle();
    (first.querySelector('.confirmIncident') as HTMLButtonElement).click();
    await settle();

    expect(api.resolve).toHaveBeenCalledWith('inc-1', 'replayed the dead letters');
  });

  it('offers no actions to someone who may only read', async () => {
    await create(false);

    expect(fixture.nativeElement.querySelector('.ackToggle')).toBeNull();
    expect(fixture.nativeElement.querySelector('.resolveToggle')).toBeNull();
  });

  it('asks again with resolved incidents included when the box is ticked', async () => {
    await create();
    const box = fixture.nativeElement.querySelector('[name="includeResolved"]') as HTMLInputElement;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    await settle();

    expect(api.list).toHaveBeenLastCalledWith(true);
  });

  it('says so when nothing is open', async () => {
    await create(true, []);

    expect(text()).toContain(ru['alertsIncidents.empty']);
  });
});
