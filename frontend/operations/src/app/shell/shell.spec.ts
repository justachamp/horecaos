import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { signal } from '@angular/core';

import { Shell } from './shell';
import { ServiceStatus } from './service-status';
import { Auth } from '../core/auth/auth';
import { CurrentLocation, LocationOption } from '../core/auth/current-location';
import { CurrentTenant } from '../core/auth/current-tenant';
import { ScopeGrant } from '../core/auth/session-context';
import { LocationScope } from '../core/api/operations-paths';
import { I18n } from '../core/i18n/i18n';
import { Toasts } from '../shared/ui/toast';
import { NAV_ITEMS } from './navigation';

class FakeAuth {
  readonly displayName = signal<string | null>(null);
  readonly subject = signal<string | null>('operator-1');
  readonly logout = vi.fn().mockReturnValue(of(null));
}

/**
 * Every capability `navigation.ts` names, granted at one `TENANT` scope —
 * a fully-privileged operator, so every other spec in this file keeps
 * seeing the unfiltered fourteen-entry rail it asserted before the rail
 * became capability-gated (operations IA §9.1c). `capabilityGate`'s own
 * spec covers a partially-privileged operator.
 */
class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('t1');
  readonly scopes = signal<readonly ScopeGrant[]>([
    {
      scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
      roleCode: 'tenant-owner',
      capabilities: [...new Set(NAV_ITEMS.map((item) => item.capability))],
    },
  ]);
  readonly denied = signal(false);
  readonly ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

/**
 * The style this app's specs use for a faked injected service — see
 * `features/customers/segments/segments-page.spec.ts` for a `pages`-level
 * sibling of the same shape. Real `CurrentLocation` makes network calls
 * this test has no business mocking; this fake gives full control over
 * `options`/`scope` instead.
 */
class FakeCurrentLocation {
  readonly options = signal<readonly LocationOption[]>([]);
  readonly scope = signal<LocationScope | null>(null);
  readonly denied = signal(false);
  readonly ensureLoaded = vi.fn().mockResolvedValue(undefined);
  readonly selectLocation = vi.fn();
}

function locationOption(
  id: string,
  displayName: string,
  status: LocationOption['status'] = 'ACTIVE',
): LocationOption {
  return { id, displayName, status };
}

/**
 * The shell's three load-bearing behaviours, each of which is a design decision
 * from the prototype rather than an implementation detail:
 *
 *  - the rail is grouped Service / People / Business, not eleven flat entries;
 *  - the late count is visible from every screen; and
 *  - F2 starts an order from anywhere, including from inside a text field.
 */
describe('Shell', () => {
  let fixture: ComponentFixture<Shell>;
  let status: ServiceStatus;
  let currentLocation: FakeCurrentLocation;
  let currentTenant: FakeCurrentTenant;

  beforeEach(async () => {
    currentLocation = new FakeCurrentLocation();
    currentTenant = new FakeCurrentTenant();
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([]),
        { provide: Auth, useValue: new FakeAuth() },
        { provide: CurrentLocation, useValue: currentLocation },
        { provide: CurrentTenant, useValue: currentTenant },
      ],
    }).compileComponents();

    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Shell);
    status = TestBed.inject(ServiceStatus);
    fixture.detectChanges();
  });

  it('groups the rail by the working day', () => {
    const groups = [...fixture.nativeElement.querySelectorAll('.rail__group-label')].map(
      (node: Element) => node.textContent?.trim(),
    );
    expect(groups).toEqual(['Service', 'People', 'Business']);
  });

  it('renders every navigation entry', () => {
    const items = fixture.nativeElement.querySelectorAll('.rail__item');
    // Fourteen since wave 37 added Marketing to the Business group: the IA
    // §6 tier legend gave it no P-tier row, but the owner directed the
    // tier-2 build this wave, so it now gets a rail entry the same way
    // Finance's own tier-2 rows did not stop Finance from getting one.
    expect(items.length).toBe(14);
  });

  it('hides a section whose capability the session context lacks, and drops its group heading if it empties', () => {
    // A cook-only grant: `ORDER_READ`/`KITCHEN_TICKET_READ`/`CATALOG_READ`
    // (a subset of `location-staff`'s own bundle) — none of `COURIER_READ`,
    // `CUSTOMER_READ` or `IAM_GRANT_MANAGE`, so every item in the People
    // group is missing its capability.
    currentTenant.scopes.set([
      {
        scope: { type: 'LOCATION', tenantId: 't1', brandId: 'b1', locationId: 'l1' },
        roleCode: 'location-staff',
        capabilities: ['ORDER_READ', 'KITCHEN_TICKET_READ', 'CATALOG_READ'],
      },
    ]);
    fixture.detectChanges();

    const items = [...fixture.nativeElement.querySelectorAll('.rail__item')].map((node: Element) =>
      node.textContent?.trim(),
    );
    expect(items).not.toContain('Staff and access');
    expect(items).toContain('Kitchen');

    // The whole group emptied out — its heading must not print above nothing.
    const groups = [...fixture.nativeElement.querySelectorAll('.rail__group-label')].map(
      (node: Element) => node.textContent?.trim(),
    );
    expect(groups).not.toContain('People');
  });

  it('hides the late indicator when nothing is late', () => {
    // Not greyed out. Absent. A permanently visible "0 late" is a signal that
    // never changes, and an operator stops reading it within a shift.
    expect(fixture.nativeElement.querySelector('.late')).toBeNull();
  });

  it('shows the late count in the top bar, on whatever screen is open', () => {
    status.set({ open: 47, late: 6 });
    fixture.detectChanges();

    const late = fixture.nativeElement.querySelector('.late');
    expect(late).not.toBeNull();
    expect(late.textContent).toContain('6 late');
  });

  it('badges Orders with the open count and Delivery with the late one', () => {
    status.set({ open: 47, late: 6 });
    fixture.detectChanges();

    const badges = [...fixture.nativeElement.querySelectorAll('.rail__badge')].map(
      (node: Element) => node.textContent?.trim(),
    );
    expect(badges).toEqual(['47', '6']);

    // Exactly one badge is coloured. Colour everything and nothing is a signal.
    expect(fixture.nativeElement.querySelectorAll('.rail__badge--late').length).toBe(1);
  });

  it('starts an order on F2, from anywhere', async () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F2', bubbles: true }));

    expect(navigate).toHaveBeenCalledWith('/orders/new');
  });

  it('ignores F2 that something else already handled', () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    const event = new KeyboardEvent('keydown', { key: 'F2', bubbles: true, cancelable: true });
    event.preventDefault();
    document.dispatchEvent(event);

    expect(navigate).not.toHaveBeenCalled();
  });

  it('kicks off location resolution itself, rather than waiting for the first routed screen', () => {
    // The shell mounts before any routed screen — see `shell.ts`'s own doc
    // comment on why the picker cannot wait for a screen to call this first.
    expect(currentLocation.ensureLoaded).toHaveBeenCalled();
  });

  it('shows no location picker for an operator with at most one location', () => {
    // Zero options (a direct LOCATION grant) and one option (a resolved
    // brand with a single branch) must look identical to the operator: no
    // picker at all. settings.md §1.1: "a picker with one option is noise."
    expect(fixture.nativeElement.querySelector('.location')).toBeNull();

    currentLocation.options.set([locationOption('l1', 'Chilanzar')]);
    currentLocation.scope.set({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.location')).toBeNull();
  });

  it('offers every location once there is more than one, and switches on selection', () => {
    currentLocation.options.set([
      locationOption('l1', 'Chilanzar'),
      locationOption('l2', 'Yunusabad'),
    ]);
    currentLocation.scope.set({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector('.location__select') as HTMLSelectElement;
    expect(select).not.toBeNull();
    expect(select.value).toBe('l1');
    expect([...select.options].map((option) => option.value)).toEqual(['l1', 'l2']);

    select.value = 'l2';
    select.dispatchEvent(new Event('change'));

    expect(currentLocation.selectLocation).toHaveBeenCalledWith('l2');
  });

  it('renders a remembered, non-first location correctly the moment the picker first appears', () => {
    // The real defect this exposed against the live app: the picker goes
    // straight from zero options (hidden) to a remembered *second* location
    // in one step, once the brand's location list finishes loading. A plain
    // `[value]` binding on the `<select>` itself is applied once and, if no
    // matching `<option>` exists yet, silently dropped by the browser and
    // never retried — Angular sees the same bound string next cycle and does
    // not re-write it. This asserts the actual DOM `.value`, not just the
    // component's own signal, so it fails the way the live app did.
    currentLocation.scope.set({ tenantId: 't1', brandId: 'b1', locationId: 'l2' });
    currentLocation.options.set([
      locationOption('l1', 'Chilanzar'),
      locationOption('l2', 'Yunusabad'),
    ]);
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector('.location__select') as HTMLSelectElement;
    expect(select.value).toBe('l2');
  });

  it('shows a SUSPENDED or DRAFT location’s status inline in the picker', () => {
    currentLocation.options.set([
      locationOption('l1', 'Chilanzar', 'ACTIVE'),
      locationOption('l2', 'Yunusabad', 'SUSPENDED'),
      locationOption('l3', 'Sergeli', 'DRAFT'),
    ]);
    currentLocation.scope.set({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
    fixture.detectChanges();

    const labels = [...fixture.nativeElement.querySelectorAll('.location__select option')].map(
      (option: HTMLOptionElement) => option.textContent?.trim(),
    );

    expect(labels).toEqual(['Chilanzar', 'Yunusabad — suspended', 'Sergeli — draft']);
  });
});

/**
 * The shell is `q-toast-host`'s only call site, and deliberately so (ADR 0101,
 * row `X.17`): one host, mounted outside the routed outlet, so a confirmation
 * survives both the dialog that raised it closing and the navigation a
 * successful mutation usually triggers. `customers-page.spec.ts` and
 * `order-queue.spec.ts` cover the two features that raise them.
 */
describe('Shell: the toast host', () => {
  let fixture: ComponentFixture<Shell>;
  let toasts: Toasts;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([]),
        { provide: Auth, useValue: new FakeAuth() },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Shell);
    toasts = TestBed.inject(Toasts);
    toasts.clear();
    fixture.detectChanges();
  });

  afterEach(() => toasts.clear());

  it('mounts exactly one host, with both live regions present before anything is announced', () => {
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelectorAll('[data-testid="q-toast-host"]').length).toBe(1);
    // Empty, but in the DOM: a live region created at the moment it gains
    // content is often never announced at all.
    expect(host.querySelector('[data-testid="q-toast-alerts"]')?.getAttribute('role')).toBe(
      'alert',
    );
    expect(host.querySelector('[data-testid="q-toast-statuses"]')?.getAttribute('role')).toBe(
      'status',
    );
    expect(host.querySelectorAll('[data-testid="q-toast"]').length).toBe(0);
  });

  it('renders a confirmation raised from anywhere in the application', () => {
    toasts.show({ message: 'Customer created', tone: 'success', timeoutMs: 0 });
    fixture.detectChanges();

    const statuses = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-toast-statuses"]',
    )!;
    expect(statuses.textContent).toContain('Customer created');
  });

  it('puts a failure in the interrupting region rather than beside the success', () => {
    toasts.show({ message: 'Order updated', tone: 'success', timeoutMs: 0 });
    toasts.show({ message: 'That did not apply', tone: 'error', timeoutMs: 0 });
    fixture.detectChanges();

    const host: HTMLElement = fixture.nativeElement;
    expect(host.querySelector('[data-testid="q-toast-alerts"]')?.textContent).toContain(
      'That did not apply',
    );
    expect(host.querySelector('[data-testid="q-toast-alerts"]')?.textContent).not.toContain(
      'Order updated',
    );
  });

  it('clears every toast on sign-out, so the next operator at the terminal sees none of the last one’s', () => {
    // The exact handoff `Toasts.clear()`'s own doc warns about: an operator
    // raises a confirmation, then immediately signs out for the next one.
    toasts.show({ message: 'Customer created', tone: 'success', timeoutMs: 0 });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('[data-testid="q-toast"]').length).toBe(1);

    const signOut = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '.rail__signout',
    )!;
    signOut.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('[data-testid="q-toast"]').length).toBe(0);
  });
});
