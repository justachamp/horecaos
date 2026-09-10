import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { TenantsApi } from '../tenants/tenants-api';
import { MigrationApi, ProgramView, QuarantineItemView, RunView, ScopeView } from './migration-api';
import { MigrationRuns } from './migration-runs';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const PROGRAM: ProgramView = {
  id: 'program-1',
  name: 'Delever cutover',
  status: 'ACTIVE',
  sourceEnvironment: 'delever-prod',
  targetEnvironment: 'horecaos-prod',
  policyVersion: 3,
  startedAt: '2026-09-01T00:00:00Z',
  completedAt: null,
  version: 2,
};

const scope = (overrides: Partial<ScopeView> = {}): ScopeView => ({
  id: 'scope-1',
  programId: 'program-1',
  tenantId: 'tenant-1',
  brandId: null,
  locationId: null,
  capability: 'ORDERS',
  sourceOwner: 'DELEVER',
  targetOwner: 'HORECAOS_ORDERING',
  writeMode: 'LEGACY_ONLY',
  readMode: 'LEGACY',
  state: 'BACKFILLING',
  stateEnteredAt: '2026-09-05T00:00:00Z',
  version: 7,
  nextStates: ['CATCHING_UP', 'PAUSED', 'BLOCKED_RECONCILIATION'],
  ...overrides,
});

const RUN: RunView = {
  id: 'run-1',
  scopeId: 'scope-1',
  runType: 'BACKFILL',
  status: 'RUNNING',
  sourceWatermark: 'legacy:1000',
  targetWatermark: null,
  transformationVersion: 1,
  counters: { scanned: 1000, created: 990, updated: 10, skipped: 0, quarantined: 2 },
  checksum: null,
  startedBy: 'migrator',
  version: 3,
  startedAt: '2026-09-05T01:00:00Z',
  finishedAt: null,
};

const ITEM: QuarantineItemView = {
  id: 'item-1',
  runId: 'run-1',
  entityType: 'ORDER',
  legacyId: 'delever-88122',
  reasonCode: 'TENANT_NOT_PROVABLE',
  sanitizedEvidenceReference: 'evidence:quarantine/88122',
  status: 'OPEN',
  resolutionCode: null,
  resolvedBy: null,
  resolvedAt: null,
};

class FakeMigrationApi {
  readonly listPrograms = vi.fn().mockResolvedValue({ items: [PROGRAM], nextCursor: null });
  readonly listScopes = vi.fn();
  readonly createOrFindProgram = vi.fn();
  readonly changeProgramStatus = vi.fn().mockResolvedValue(undefined);
  readonly openScope = vi.fn();
  readonly listRuns = vi.fn().mockResolvedValue({ items: [RUN], nextCursor: null });
  readonly openQuarantine = vi.fn().mockResolvedValue({ items: [ITEM], nextCursor: null });
  readonly advanceScope = vi.fn().mockResolvedValue(undefined);
  readonly suspendScope = vi.fn().mockResolvedValue(undefined);
  readonly resumeScope = vi.fn().mockResolvedValue(undefined);
  readonly rollBackScope = vi.fn().mockResolvedValue(undefined);
  readonly startRun = vi.fn().mockResolvedValue(RUN);
  readonly finishRun = vi.fn().mockResolvedValue({ ...RUN, status: 'COMPLETED' });
  readonly resolveQuarantine = vi.fn().mockResolvedValue(undefined);
}

describe('MigrationRuns', () => {
  let fixture: ComponentFixture<MigrationRuns>;
  let api: FakeMigrationApi;

  async function create(scopes: ScopeView[] = [scope()]): Promise<void> {
    api = new FakeMigrationApi();
    api.listScopes.mockResolvedValue({ items: scopes, nextCursor: null });
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [MigrationRuns],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: MigrationApi, useValue: api },
        {
          provide: TenantsApi,
          useValue: {
            listTenants: vi.fn().mockResolvedValue({
              items: [{ id: 'tenant-1', slug: 'oshxona', displayName: 'Oshxona' }],
              nextCursor: null,
            }),
            getBrands: vi.fn().mockResolvedValue([]),
          },
        },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(MigrationRuns);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function el<T extends HTMLElement>(selector: string): T {
    return fixture.nativeElement.querySelector(selector) as T;
  }

  async function set(selector: string, value: string, event = 'input'): Promise<void> {
    const input = el<HTMLInputElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event(event));
    await settle();
  }

  async function openScope(): Promise<void> {
    el<HTMLElement>('.scopes tbody tr').click();
    await settle();
  }

  it('lists programs to choose from instead of registering one to find it', async () => {
    await create();

    expect(api.createOrFindProgram).not.toHaveBeenCalled();
    expect(el('.programs').textContent).toContain('Delever cutover');
    expect(api.listScopes).toHaveBeenCalledWith('program-1', null, 200);
    expect(el('.scopes').textContent).toContain('Oshxona');
    expect(el('.scopes').textContent).toContain(ru['migration.capability.ORDERS']);
  });

  it('moves a scope along its path with the version it read, and pauses it through a suspension', async () => {
    await create();
    await openScope();

    await set('select[name="moveTarget"]', 'CATCHING_UP', 'change');
    await set('input[name="moveReason"]', 'backfill finished overnight');
    el<HTMLButtonElement>('.moveForm button[type="submit"]').click();
    await settle();
    expect(api.advanceScope).toHaveBeenCalledWith(expect.objectContaining({ id: 'scope-1', version: 7 }), 'CATCHING_UP', 'backfill finished overnight');

    await set('select[name="moveTarget"]', 'PAUSED', 'change');
    await set('input[name="moveReason"]', 'legacy maintenance window');
    el<HTMLButtonElement>('.moveForm button[type="submit"]').click();
    await settle();
    expect(api.suspendScope).toHaveBeenCalledWith(expect.objectContaining({ id: 'scope-1' }), 'PAUSED', 'legacy maintenance window');
    expect(api.advanceScope).toHaveBeenCalledTimes(1);
  });

  it('leaves taking ownership to the cutover checklist', async () => {
    await create([scope({ state: 'CUTOVER_READY', nextStates: ['CANARY', 'TARGET_OWNED', 'PAUSED'] })]);
    await openScope();

    const options = Array.from(el<HTMLSelectElement>('select[name="moveTarget"]').options).map((o) => o.value);
    expect(options).not.toContain('TARGET_OWNED');
    expect(fixture.nativeElement.textContent).toContain(ru['migrationRuns.move.toCutover']);
  });

  it('resumes a held scope instead of offering moves out of it', async () => {
    await create([scope({ state: 'PAUSED', nextStates: [] })]);
    await openScope();

    expect(el('select[name="moveTarget"]')).toBeNull();
    await set('input[name="moveReason"]', 'window over');
    el<HTMLButtonElement>('button.resume').click();
    await settle();

    expect(api.resumeScope).toHaveBeenCalledWith(expect.objectContaining({ id: 'scope-1' }), 'window over');
  });

  it('ends a running run with a reason, and refuses a checksum that is not one', async () => {
    await create();
    await openScope();

    el<HTMLButtonElement>('.finishToggle').click();
    await settle();
    await set('input[name="finishReason"]', 'backfill complete');
    await set('input[name="checksum"]', 'not-a-checksum');
    const submit = el<HTMLButtonElement>('.finishForm button[type="submit"]');
    expect(submit.disabled).toBe(true);

    await set('input[name="checksum"]', 'a'.repeat(64));
    submit.click();
    await settle();

    expect(api.finishRun).toHaveBeenCalledWith('tenant-1', RUN, 'COMPLETED', 'backfill complete', 'a'.repeat(64));
  });

  it('settles a held-back row with how it was settled and why', async () => {
    await create();
    await openScope();

    expect(el('.quarantine').textContent).toContain('delever-88122');
    el<HTMLButtonElement>('.settleToggle').click();
    await settle();
    await set('select[name="resolutionCode"]', 'ACCEPTED_NOT_MIGRATABLE', 'change');
    await set('input[name="settleReason"]', 'a test order nobody paid for');
    el<HTMLButtonElement>('.settleForm button[type="submit"]').click();
    await settle();

    expect(api.resolveQuarantine).toHaveBeenCalledWith(
      'tenant-1',
      'item-1',
      'ACCEPTED_NOT_MIGRATABLE',
      'a test order nobody paid for',
    );
  });
});
