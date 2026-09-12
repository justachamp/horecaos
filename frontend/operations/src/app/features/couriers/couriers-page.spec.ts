import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation, LocationOption } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CourierDetailResponse, CouriersApi, RosterEntryResponse } from './couriers-api';
import { CouriersPage } from './couriers-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/** The operator's own brand: `l1` where `DETAIL` is already bound, plus `l2` free to bind. */
const BRANCH_OPTIONS: readonly LocationOption[] = [
  { id: 'l1', displayName: 'Chilonzor', status: 'ACTIVE' },
  { id: 'l2', displayName: 'Sergeli', status: 'ACTIVE' },
];

const COURIER: RosterEntryResponse = {
  courierId: 'courier-1',
  displayReference: 'K-014',
  status: 'ACTIVE',
  courierTypeId: 'type-1',
  courierTypeName: 'Scooter',
  vehicleClass: 'SCOOTER',
  activeAssignments: 1,
  concurrencyCeiling: 2,
  engagementId: 'engagement-1',
  engagementStatus: 'ACTIVE',
  warningState: 'VALID',
  reverificationDueOn: null,
};

const PENDING: RosterEntryResponse = { ...COURIER, engagementStatus: 'PENDING_VERIFICATION' };

const DETAIL: CourierDetailResponse = {
  ...COURIER,
  complianceFieldsOnFile: ['PASSPORT', 'PINFL'],
  vehicleFuelType: 'PETROL',
  photoMediaId: null,
  complianceUpdatedAt: '2026-09-01T07:00:00Z',
  groups: [
    { groupId: 'g1', code: 'NIGHT', displayName: 'Ночная смена', status: 'ACTIVE', memberCount: 3 },
  ],
  branches: [{ locationId: 'l1', brandId: 'b1', locationName: 'Chilonzor', primary: true }],
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CouriersPage', () => {
  let fixture: ComponentFixture<CouriersPage>;

  async function render(
    api: Partial<CouriersApi>,
    options: readonly LocationOption[] = [],
  ): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [CouriersPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
            options: () => options,
          },
        },
        // `groups()` is read on every load, exactly like `roster()`/`types()`;
        // defaulted here so the tests below it that are not about groups do
        // not each have to stub a call they do not care about.
        { provide: CouriersApi, useValue: { groups: vi.fn().mockResolvedValue([]), ...api } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CouriersPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  /** Clicks, then lets the component's own promise chain settle. */
  async function click(host: HTMLElement, testId: string): Promise<void> {
    host.querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)!.click();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function type(host: HTMLElement, testId: string, value: string): void {
    const input = host.querySelector<HTMLInputElement>(`[data-testid="${testId}"]`)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  /**
   * The register and verify dialogs label their inputs rather than tagging
   * them. Matched on the label's exact text, not a substring: "Reason code"
   * contains "Reason", and a substring match silently typed the reason into the
   * reason-code box and left the reason empty.
   */
  function typeByLabel(host: HTMLElement, label: string, value: string): void {
    const field = [...host.querySelectorAll('label.field')].find(
      (candidate) => candidate.querySelector('.q-caption')?.textContent?.trim() === label,
    )!;
    const input = field.querySelector<HTMLInputElement>('input')!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('lists the roster with type, load and engagement status', async () => {
    const host = await render({
      roster: vi.fn().mockResolvedValue([COURIER]),
      types: vi.fn().mockResolvedValue([]),
    });

    expect(host.querySelectorAll('[data-testid="courier-row"]')).toHaveLength(1);
    expect(host.textContent).toContain('K-014');
    expect(host.textContent).toContain('Scooter');
    expect(host.textContent).toContain('1 / 2');
  });

  it('renders the vehicle class, which is in the DTO and was in no template', async () => {
    const host = await render({
      roster: vi.fn().mockResolvedValue([COURIER]),
      types: vi.fn().mockResolvedValue([]),
    });

    // The label, not the enum: an operator reads "Scooter", not "SCOOTER".
    const cell = host.querySelector('[data-testid="courier-vehicle-class"]');
    expect(cell?.textContent?.trim()).toBe('Scooter');
  });

  it('falls through to the raw code for a vehicle class this client does not know', async () => {
    const host = await render({
      roster: vi.fn().mockResolvedValue([{ ...COURIER, vehicleClass: 'HOVERBOARD' }]),
      types: vi.fn().mockResolvedValue([]),
    });

    // An unfamiliar vehicle must read as an unfamiliar word rather than as an
    // empty cell, which would look like a courier with no vehicle at all.
    expect(host.querySelector('[data-testid="courier-vehicle-class"]')?.textContent?.trim()).toBe(
      'HOVERBOARD',
    );
  });

  it('no longer carries the honesty notice, because the fields it apologised for exist', async () => {
    const host = await render({
      roster: vi.fn().mockResolvedValue([COURIER]),
      types: vi.fn().mockResolvedValue([]),
    });

    expect(host.querySelector('.couriers__not-built')).toBeNull();
    expect(host.textContent).not.toContain('not built');
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [CouriersPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CouriersApi, useValue: { roster: vi.fn(), types: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CouriersPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="couriers-denied"]'),
    ).not.toBeNull();
  });

  // ---------------------------------------------------------------- register

  it('registers a courier with the compliance fields the operator filled in', async () => {
    const register = vi.fn().mockResolvedValue({ courierId: 'c2', engagementId: 'e2' });
    const host = await render({
      roster: vi.fn().mockResolvedValue([]),
      types: vi.fn().mockResolvedValue([
        {
          courierTypeId: 'type-1',
          code: 'SCOOTER',
          displayName: 'Scooter',
          vehicleClass: 'SCOOTER',
          minDistanceMeters: 0,
          maxConcurrentAssignments: 2,
          offerTtlSeconds: 60,
        },
      ]),
      register,
    });

    host.querySelector<HTMLButtonElement>('.couriers__register')!.click();
    fixture.detectChanges();

    typeByLabel(host, 'Courier-app account (Keycloak subject)', 'keycloak-k900');
    typeByLabel(host, 'Display reference (e.g. K-014)', 'K-900');
    typeByLabel(host, 'Full name', 'Alisher Karimov');
    typeByLabel(host, 'Reason', 'onboarding a rider');
    type(host, 'register-PASSPORT', 'AA1234567');
    type(host, 'register-PINFL', '31234567890123');

    const submit = [...host.querySelectorAll('button')].find(
      (button) => button.textContent?.trim() === 'Register',
    )!;
    submit.click();
    await flushMicrotasks();

    expect(register).toHaveBeenCalledOnce();
    const body = register.mock.calls[0][1];
    expect(body.displayReference).toBe('K-900');
    expect(body.passport).toBe('AA1234567');
    expect(body.pinfl).toBe('31234567890123');
    // A field the operator left blank is absent, never an empty string: the
    // endpoint reads an absent field as "leave it alone".
    expect(body.drivingLicence).toBeUndefined();
    expect(body.homeAddress).toBeUndefined();
  });

  // ------------------------------------------------------------------ verify

  it('verifies a pending registration and reloads the roster', async () => {
    const verify = vi.fn().mockResolvedValue({ engagementId: 'engagement-1', status: 'ACTIVE' });
    const roster = vi.fn().mockResolvedValue([PENDING]);
    const host = await render({ roster, types: vi.fn().mockResolvedValue([]), verify });

    [...host.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === 'Verify')!
      .click();
    fixture.detectChanges();

    typeByLabel(host, 'Registration identifier', '312345678901');
    typeByLabel(host, 'Valid until', '2027-01-01');
    typeByLabel(host, 'Reason', 'sighted the certificate');

    [...host.querySelectorAll('.dialog__actions button')]
      .find((button) => button.textContent?.trim() === 'Verify')!
      .dispatchEvent(new MouseEvent('click'));
    await flushMicrotasks();

    expect(verify).toHaveBeenCalledWith('t1', 'engagement-1', {
      registrationIdentifier: '312345678901',
      validUntil: '2027-01-01',
      method: 'MANUAL_ATTESTATION',
      reason: 'sighted the certificate',
    });
    // The roster is read again, or the row keeps saying PENDING after the
    // attestation that activated it.
    expect(roster).toHaveBeenCalledTimes(2);
  });

  // ----------------------------------------------------------------- suspend

  it('suspends an engagement with a reason code and a reason', async () => {
    const suspend = vi.fn().mockResolvedValue(undefined);
    const host = await render({
      roster: vi.fn().mockResolvedValue([COURIER]),
      types: vi.fn().mockResolvedValue([]),
      suspend,
    });

    [...host.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === 'Suspend')!
      .click();
    fixture.detectChanges();

    typeByLabel(host, 'Reason code', 'NO_SHOW');
    typeByLabel(host, 'Reason', 'did not come in for a week');

    [...host.querySelectorAll('.dialog__actions button')]
      .find((button) => button.textContent?.trim() === 'Suspend')!
      .dispatchEvent(new MouseEvent('click'));
    await flushMicrotasks();

    expect(suspend).toHaveBeenCalledWith('t1', 'engagement-1', {
      reasonCode: 'NO_SHOW',
      reason: 'did not come in for a week',
    });
  });

  // ------------------------------------------------------------ detail pane

  it('opens the detail pane and shows which documents are on file, not what they say', async () => {
    const courier = vi.fn().mockResolvedValue(DETAIL);
    const host = await render({
      roster: vi.fn().mockResolvedValue([COURIER]),
      types: vi.fn().mockResolvedValue([]),
      courier,
    });

    await click(host, 'courier-open');

    expect(courier).toHaveBeenCalledWith('t1', 'courier-1');
    expect(host.querySelector('[data-testid="courier-detail"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="on-file-PASSPORT"]')?.textContent?.trim()).toBe(
      'On file',
    );
    // Seven of nine are missing, and the pane says so rather than leaving a
    // manager to count the rows.
    expect(host.querySelector('[data-testid="courier-file-incomplete"]')?.textContent).toContain(
      '7',
    );
    expect(host.querySelector('[data-testid="detail-vehicle-class"]')?.textContent?.trim()).toBe(
      'Scooter',
    );
    expect(host.textContent).toContain('Chilonzor');
    expect(host.textContent).toContain('Ночная смена');
  });

  it('reveals the documents only against a purpose, and hides them again', async () => {
    const revealComplianceFile = vi.fn().mockResolvedValue({
      fields: [
        { field: 'PASSPORT', value: 'AA1234567' },
        { field: 'PINFL', value: '31234567890123' },
      ],
    });
    const host = await render({
      roster: vi.fn().mockResolvedValue([COURIER]),
      types: vi.fn().mockResolvedValue([]),
      courier: vi.fn().mockResolvedValue(DETAIL),
      revealComplianceFile,
    });

    await click(host, 'courier-open');

    // No purpose, no reveal: the button is the control, and the audit fact the
    // endpoint writes is only as useful as the sentence behind it.
    expect(host.querySelector<HTMLButtonElement>('[data-testid="courier-reveal"]')!.disabled).toBe(
      true,
    );
    expect(host.textContent).not.toContain('AA1234567');

    type(host, 'reveal-purpose', 'a traffic police enquiry');
    await click(host, 'courier-reveal');

    expect(revealComplianceFile).toHaveBeenCalledWith(
      't1',
      'courier-1',
      'a traffic police enquiry',
    );
    expect(host.querySelector('[data-testid="revealed-PASSPORT"]')?.textContent?.trim()).toBe(
      'AA1234567',
    );

    await click(host, 'courier-hide');
    expect(host.textContent).not.toContain('AA1234567');
  });

  it('drops a revealed file when the pane moves to another courier', async () => {
    const second: RosterEntryResponse = {
      ...COURIER,
      courierId: 'courier-2',
      displayReference: 'K-015',
    };
    const host = await render({
      roster: vi.fn().mockResolvedValue([COURIER, second]),
      types: vi.fn().mockResolvedValue([]),
      courier: vi.fn().mockResolvedValue(DETAIL),
      revealComplianceFile: vi
        .fn()
        .mockResolvedValue({ fields: [{ field: 'PASSPORT', value: 'AA1234567' }] }),
    });

    await click(host, 'courier-open');
    type(host, 'reveal-purpose', 'an enquiry');
    await click(host, 'courier-reveal');
    expect(host.textContent).toContain('AA1234567');

    // One decrypted document must never appear under another courier's name,
    // however the second request resolves.
    host.querySelectorAll<HTMLButtonElement>('[data-testid="courier-open"]')[1].click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.textContent).not.toContain('AA1234567');
  });

  it('records only the fields the operator typed, and re-reads the file afterwards', async () => {
    const recordComplianceFile = vi.fn().mockResolvedValue(undefined);
    const courier = vi.fn().mockResolvedValue(DETAIL);
    const host = await render({
      roster: vi.fn().mockResolvedValue([COURIER]),
      types: vi.fn().mockResolvedValue([]),
      courier,
      recordComplianceFile,
    });

    await click(host, 'courier-open');
    await click(host, 'courier-file-edit');

    // A reason alone changes nothing, so the save stays shut.
    type(host, 'edit-reason', 'he changed the scooter');
    expect(
      host.querySelector<HTMLButtonElement>('[data-testid="courier-file-save"]')!.disabled,
    ).toBe(true);

    type(host, 'edit-VEHICLE_PLATE', '01 B 999 XX');
    await click(host, 'courier-file-save');

    // Exactly one field and the reason. The fuel picker opened on PETROL and
    // was not moved, so it is not re-sent: an unchanged value would stamp a
    // provenance that reads afterwards as though somebody reviewed the file.
    expect(recordComplianceFile).toHaveBeenCalledWith('t1', 'courier-1', {
      vehiclePlate: '01 B 999 XX',
      reason: 'he changed the scooter',
    });
    // The eight fields nobody typed are absent, not blank: a blank would erase
    // a passport the console never held.
    expect(recordComplianceFile.mock.calls[0][2].passport).toBeUndefined();
    expect(courier).toHaveBeenCalledTimes(2);
  });

  // ------------------------------------------------------------------ groups

  it('creates a courier group and refreshes the tenant list', async () => {
    const createGroup = vi.fn().mockResolvedValue({ groupId: 'g2' });
    const groups = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          groupId: 'g2',
          code: 'NIGHT',
          displayName: 'Ночная смена',
          status: 'ACTIVE',
          memberCount: 0,
        },
      ]);
    const host = await render({
      roster: vi.fn().mockResolvedValue([COURIER]),
      types: vi.fn().mockResolvedValue([]),
      courier: vi.fn().mockResolvedValue(DETAIL),
      groups,
      createGroup,
    });

    await click(host, 'courier-open');
    await click(host, 'courier-new-group');
    type(host, 'new-group-code', 'NIGHT');
    type(host, 'new-group-display-name', 'Ночная смена');
    type(host, 'new-group-reason', 'planning the rota');
    await click(host, 'new-group-submit');

    expect(createGroup).toHaveBeenCalledWith('t1', 'NIGHT', 'Ночная смена', 'planning the rota');
    // Re-read, not just closed: the next «join a group» pick must offer it.
    expect(groups).toHaveBeenCalledTimes(2);
  });

  it('joins a courier to an existing group not already on their file', async () => {
    const joinGroup = vi.fn().mockResolvedValue(undefined);
    const courier = vi.fn().mockResolvedValue(DETAIL);
    const host = await render({
      roster: vi.fn().mockResolvedValue([COURIER]),
      types: vi.fn().mockResolvedValue([]),
      courier,
      groups: vi.fn().mockResolvedValue([
        {
          groupId: 'g1',
          code: 'NIGHT',
          displayName: 'Ночная смена',
          status: 'ACTIVE',
          memberCount: 1,
        },
        {
          groupId: 'g2',
          code: 'DAY',
          displayName: 'Дневная смена',
          status: 'ACTIVE',
          memberCount: 4,
        },
      ]),
      joinGroup,
    });

    await click(host, 'courier-open');
    await click(host, 'courier-join-group');

    // g1 is already on DETAIL's file, so only g2 is offered to join.
    const select = host.querySelector<HTMLSelectElement>('[data-testid="join-group-select"]')!;
    expect([...select.options].map((option) => option.value)).toEqual(['g2']);

    type(host, 'join-group-reason', 'he asked for days');
    await click(host, 'join-group-submit');

    expect(joinGroup).toHaveBeenCalledWith('t1', 'courier-1', 'g2', 'he asked for days');
    expect(courier).toHaveBeenCalledTimes(2);
  });

  it('takes a courier out of a group with a reason', async () => {
    const leaveGroup = vi.fn().mockResolvedValue(undefined);
    const host = await render({
      roster: vi.fn().mockResolvedValue([COURIER]),
      types: vi.fn().mockResolvedValue([]),
      courier: vi.fn().mockResolvedValue(DETAIL),
      leaveGroup,
    });

    await click(host, 'courier-open');
    await click(host, 'leave-group-g1');

    // No reason, no leave: the same rule every other write on this page keeps.
    expect(
      host.querySelector<HTMLButtonElement>('[data-testid="leave-group-confirm"]')!.disabled,
    ).toBe(true);

    type(host, 'leave-group-reason', 'he moved to days');
    await click(host, 'leave-group-confirm');

    expect(leaveGroup).toHaveBeenCalledWith('t1', 'courier-1', 'g1', 'he moved to days');
  });

  // ----------------------------------------------------------- branch bindings

  it('binds a courier to a branch the operator can see and has not bound yet', async () => {
    const bindBranch = vi.fn().mockResolvedValue(undefined);
    const courier = vi.fn().mockResolvedValue(DETAIL);
    const host = await render(
      {
        roster: vi.fn().mockResolvedValue([COURIER]),
        types: vi.fn().mockResolvedValue([]),
        courier,
        bindBranch,
      },
      BRANCH_OPTIONS,
    );

    await click(host, 'courier-open');
    await click(host, 'courier-bind-branch');

    // l1 is already on DETAIL's file, so only l2 is offered to bind.
    const select = host.querySelector<HTMLSelectElement>('[data-testid="bind-branch-select"]')!;
    expect([...select.options].map((option) => option.value)).toEqual(['l2']);

    host.querySelector<HTMLInputElement>('[data-testid="bind-branch-primary"]')!.click();
    type(host, 'bind-branch-reason', 'he covers Sergeli too');
    await click(host, 'bind-branch-submit');

    expect(bindBranch).toHaveBeenCalledWith(
      't1',
      'courier-1',
      'b1',
      'l2',
      true,
      'he covers Sergeli too',
    );
    expect(courier).toHaveBeenCalledTimes(2);
  });

  it('releases a courier from a branch, carrying the binding’s own brandId', async () => {
    const unbindBranch = vi.fn().mockResolvedValue(undefined);
    const host = await render(
      {
        roster: vi.fn().mockResolvedValue([COURIER]),
        types: vi.fn().mockResolvedValue([]),
        courier: vi.fn().mockResolvedValue(DETAIL),
        unbindBranch,
      },
      BRANCH_OPTIONS,
    );

    await click(host, 'courier-open');
    await click(host, 'unbind-branch-l1');

    type(host, 'unbind-branch-reason', 'he never rides there');
    await click(host, 'unbind-branch-confirm');

    // DETAIL's own binding names brandId "b1" — asserted explicitly because
    // this is exactly the value the P19 fix made a real path segment.
    expect(unbindBranch).toHaveBeenCalledWith(
      't1',
      'courier-1',
      'b1',
      'l1',
      'he never rides there',
    );
  });

  it('unbinds under the binding’s brand, not the operator’s current one', async () => {
    // The operator is scoped to brand b1; the courier's other branch was bound
    // under brand b2. Only a page that reads brandId off the binding itself
    // sends b2 — one that reads it off the current scope sends b1 and this
    // assertion goes red. That is the regression the P19 fix guards against.
    const unbindBranch = vi.fn().mockResolvedValue(undefined);
    const boundElsewhere: CourierDetailResponse = {
      ...DETAIL,
      branches: [
        ...DETAIL.branches,
        { locationId: 'l9', brandId: 'b2', locationName: 'Yunusobod', primary: false },
      ],
    };
    const host = await render(
      {
        roster: vi.fn().mockResolvedValue([COURIER]),
        types: vi.fn().mockResolvedValue([]),
        courier: vi.fn().mockResolvedValue(boundElsewhere),
        unbindBranch,
      },
      BRANCH_OPTIONS,
    );

    await click(host, 'courier-open');
    await click(host, 'unbind-branch-l9');

    type(host, 'unbind-branch-reason', 'moved to the other brand’s roster');
    await click(host, 'unbind-branch-confirm');

    expect(unbindBranch).toHaveBeenCalledWith(
      't1',
      'courier-1',
      'b2',
      'l9',
      'moved to the other brand’s roster',
    );
    expect(unbindBranch).not.toHaveBeenCalledWith('t1', 'courier-1', 'b1', 'l9', expect.anything());
  });
});
