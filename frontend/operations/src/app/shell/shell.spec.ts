import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { NEVER, Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Component, inject, signal } from '@angular/core';

import { Shell } from './shell';
import { ServiceStatus } from './service-status';
import { ApiClient } from '../core/api/api-client';
import { Auth } from '../core/auth/auth';
import { BrandChoice } from '../core/auth/brand-choice';
import { CurrentLocation, LocationOption } from '../core/auth/current-location';
import { CurrentTenant } from '../core/auth/current-tenant';
import { OwnProfile } from '../core/auth/own-profile';
import { StaffMember } from '../core/api/staff-member';
import { OwnPreferences } from '../core/preferences/own-preferences';
import { staffMemberDetail } from '../features/staff/staff-member.testing';
import { ScopeGrant } from '../core/auth/session-context';
import { LocationScope } from '../core/api/operations-paths';
import { I18n } from '../core/i18n/i18n';
import { seedPlatformLocalesForTesting } from '../core/i18n/platform-locales';
import { REGISTRY_FIXTURE } from '../../testing/platform-locales.fixture';
import { RealtimeClient } from '../core/realtime/realtime-client';
import { LocationsApi } from '../features/settings/locations/locations-api';
import { ShortcutRegistry } from '../shared/keyboard/shortcut-registry';
import { Toasts } from '../shared/ui/toast';
import { NAV_ITEMS } from './navigation';

class FakeOwnProfile {
  readonly displayName = signal<string | null>(null);
  readonly member = signal<StaffMember | null>(null);
  readonly ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

/**
 * ADR 0160: the person's stored preferences. The shell only starts the read and flushes it before it
 * signs out; what they hold is the order board's and the branch picker's business.
 */
class FakePreferences {
  readonly shell = signal({});
  readonly orderBoard = signal({});
  readonly persistent = signal(true);
  readonly ensureLoaded = vi.fn().mockResolvedValue(undefined);
  readonly flush = vi.fn().mockResolvedValue(undefined);
}

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
  let auth: FakeAuth;
  let ownProfile: FakeOwnProfile;
  let preferences: FakePreferences;
  let locationsApi: { serviceStates: ReturnType<typeof vi.fn>; list: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    preferences = new FakePreferences();
    locationsApi = {
      serviceStates: vi.fn().mockResolvedValue([]),
      list: vi.fn().mockResolvedValue([]),
    };
    currentLocation = new FakeCurrentLocation();
    currentTenant = new FakeCurrentTenant();
    auth = new FakeAuth();
    ownProfile = new FakeOwnProfile();
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([]),
        { provide: Auth, useValue: auth },
        { provide: OwnPreferences, useValue: preferences },
        { provide: OwnProfile, useValue: ownProfile },
        { provide: CurrentLocation, useValue: currentLocation },
        { provide: CurrentTenant, useValue: currentTenant },
        { provide: LocationsApi, useValue: locationsApi },
      ],
    }).compileComponents();

    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Shell);
    status = TestBed.inject(ServiceStatus);
    fixture.detectChanges();
  });

  describe('the account chip (ADR 0139)', () => {
    const chip = (): string | undefined =>
      fixture.nativeElement.querySelector('.rail__account')?.textContent?.trim();

    it('loads the signed-in person’s own record when the shell mounts', () => {
      expect(ownProfile.ensureLoaded).toHaveBeenCalled();
    });

    it('shows the name the tenant keeps, not the token’s name claim, which goes stale after an edit', () => {
      auth.displayName.set('Old Name');
      ownProfile.displayName.set('Aziza Karimova');
      fixture.detectChanges();

      expect(chip()).toBe('Aziza Karimova');
    });

    it('falls back to the token claim for an account the tenant keeps no record for', () => {
      auth.displayName.set('Support Agent');
      ownProfile.displayName.set(null);
      fixture.detectChanges();

      expect(chip()).toBe('Support Agent');
    });

    it('shows no chip at all when there is neither', () => {
      expect(chip()).toBeUndefined();
    });
  });

  describe('the language switcher (ADR 0160, decision 4)', () => {
    const offer = (): HTMLElement | null =>
      fixture.nativeElement.querySelector('[data-testid="locale-scope-offer"]');

    async function choose(value: string): Promise<void> {
      const select = fixture.nativeElement.querySelector('.locale__select') as HTMLSelectElement;
      select.value = value;
      select.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    }

    it('chooses for this device and offers, once, to make the choice the account’s as well', async () => {
      ownProfile.member.set(staffMemberDetail({ uiLocale: 'ru' }));

      await choose('en');

      expect(TestBed.inject(I18n).locale()).toBe('en');
      expect(offer()).not.toBeNull();
      expect(offer()!.textContent).toContain('this device only');
      expect(offer()!.textContent).toContain('For all my devices');
    });

    it('stays quiet when the account already carries the language just picked', async () => {
      ownProfile.member.set(staffMemberDetail({ uiLocale: 'en' }));

      await choose('en');

      expect(offer()).toBeNull();
    });

    it('stays quiet for an account the tenant keeps no record for: there is nothing to write the choice to', async () => {
      ownProfile.member.set(null);

      await choose('en');

      expect(TestBed.inject(I18n).locale()).toBe('en');
      expect(offer()).toBeNull();
    });

    it('takes the offer down when it is answered, and again on the next change', async () => {
      ownProfile.member.set(staffMemberDetail({ uiLocale: 'ru' }));
      await choose('en');
      (offer()!.querySelector('[data-testid="locale-scope-dismiss"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(offer()).toBeNull();

      await choose('uz-Latn');

      expect(offer()).not.toBeNull();
    });
  });

  it('writes the person’s last preference change before it signs them out', async () => {
    let flushed = false;
    preferences.flush.mockImplementation(() => {
      flushed = true;
      return Promise.resolve();
    });
    auth.logout.mockImplementation(() => {
      expect(flushed, 'the token is still good when the last change is written').toBe(true);
      return of(null);
    });

    (fixture.nativeElement.querySelector('.rail__signout') as HTMLButtonElement).click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(preferences.flush).toHaveBeenCalledTimes(1);
    expect(auth.logout).toHaveBeenCalledTimes(1);
  });

  it('starts the read of the person’s preferences when it mounts, without waiting for it', () => {
    expect(preferences.ensureLoaded).toHaveBeenCalled();
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

  it('shows a brand manager the Customers section by the call centre capability she holds', () => {
    // PlatformRole.BRAND_MANAGER at her brand: customer.lead.read and no customer.read, so the
    // section's own capability alone would have hidden the screen her role exists to use.
    currentTenant.scopes.set([
      {
        scope: { type: 'BRAND', tenantId: 't1', brandId: 'b1', locationId: null },
        roleCode: 'brand-manager',
        capabilities: ['ORDER_READ', 'CUSTOMER_LEAD_READ', 'CUSTOMER_LEAD_MANAGE'],
      },
    ]);
    fixture.detectChanges();

    const items = [...fixture.nativeElement.querySelectorAll('.rail__item')].map((node: Element) =>
      node.textContent?.trim(),
    );
    expect(items).toContain('Customers');
    expect(items).not.toContain('Staff and access');
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

  it('g then l goes to the branch list from anywhere, for someone who has the Settings section', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'g', bubbles: true }));
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'l', bubbles: true }));

    expect(navigate).toHaveBeenCalledWith('/settings/locations');
  });

  it('offers no g l to someone without the Settings section, and the chord goes nowhere', () => {
    currentTenant.scopes.set([
      {
        scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
        roleCode: 'location-staff',
        capabilities: ['ORDER_READ'],
      },
    ]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'g', bubbles: true }));
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'l', bubbles: true }));

    expect(navigate).not.toHaveBeenCalled();
  });

  describe('the branch open/closed control (brands-and-locations.md §6.2)', () => {
    const CONTROL = '[data-testid="branch-state-control"]';
    const here: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

    function mayChangeBranchState(): void {
      currentTenant.scopes.set([
        {
          scope: { type: 'LOCATION', tenantId: 't1', brandId: 'b1', locationId: 'l1' },
          roleCode: 'location-manager',
          capabilities: ['ORDER_READ', 'LOCATION_SERVICE_STATE_CHANGE'],
        },
      ]);
      currentLocation.scope.set(here);
      currentLocation.options.set([locationOption('l1', 'Chilonzor')]);
      locationsApi.serviceStates.mockResolvedValue([
        {
          locationId: 'l1',
          mode: 'FORCE_CLOSED',
          effectiveMode: 'FORCE_CLOSED',
          reasonCode: 'NO_STAFF',
          effectiveUntil: null,
        },
      ]);
    }

    async function afterIdle(expectation: () => void): Promise<void> {
      await vi.waitFor(
        () => {
          fixture.detectChanges();
          expectation();
        },
        { timeout: 5000 },
      );
    }

    it('is in the header, once the shell is idle, for someone who may change a branch’s state', async () => {
      mayChangeBranchState();

      await afterIdle(() =>
        expect(
          (fixture.nativeElement as HTMLElement).querySelector(CONTROL)?.textContent,
        ).toContain('Branch closed'),
      );
    });

    it('is not drawn at all for someone who may not', async () => {
      mayChangeBranchState();
      currentTenant.scopes.set([
        {
          scope: { type: 'LOCATION', tenantId: 't1', brandId: 'b1', locationId: 'l1' },
          roleCode: 'location-staff',
          capabilities: ['ORDER_READ'],
        },
      ]);

      await afterIdle(() => {
        expect(
          (fixture.nativeElement as HTMLElement).querySelector('q-branch-state-control'),
        ).not.toBeNull();
      });
      expect((fixture.nativeElement as HTMLElement).querySelector(CONTROL)).toBeNull();
    });
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

  it('mounts the call bar (IA X.37) so a ringing call reaches an operator on any screen', () => {
    // Behaviour (claiming, starting an order, what renders while a call is
    // ringing) is `call-bar.spec.ts`'s own job; this only proves the shell
    // actually mounts it, outside the routed outlet, the same way it mounts
    // `q-toast-host`.
    expect(fixture.nativeElement.querySelector('q-call-bar')).not.toBeNull();
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
        { provide: OwnPreferences, useValue: new FakePreferences() },
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

/**
 * Row `X.1`: the keyboard scheme (one dispatcher, a cheat-sheet over whatever is registered) and the
 * brand picker for a multi-brand tenant.
 */
describe('Shell: the keyboard and the brand picker', () => {
  let fixture: ComponentFixture<Shell>;
  let registry: ShortcutRegistry;
  let choice: BrandChoice;
  let probeCreated: number;

  @Component({ selector: 'q-probe', template: '<p>screen</p>' })
  class Probe {
    constructor() {
      probeCreated += 1;
    }
  }

  beforeEach(async () => {
    probeCreated = 0;
    localStorage.removeItem('horecaos.operations.brandId');
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([{ path: '', component: Probe }]),
        { provide: Auth, useValue: new FakeAuth() },
        { provide: OwnPreferences, useValue: new FakePreferences() },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    registry = TestBed.inject(ShortcutRegistry);
    choice = TestBed.inject(BrandChoice);
    fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
  });

  afterEach(() => {
    registry.closeSheet();
    fixture.destroy();
  });

  const sheet = (): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-modal"]');

  function press(key: string, target: EventTarget = document): void {
    target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    fixture.detectChanges();
  }

  describe('the cheat-sheet', () => {
    it('is closed until asked for', () => {
      expect(sheet()).toBeNull();
    });

    it('opens on ? and lists the shell’s own keys', () => {
      press('?');

      const dialog = sheet();
      expect(dialog?.textContent).toContain('Keyboard shortcuts');
      const scope = dialog?.querySelector('[data-testid="shortcut-scope-shell"]');
      expect(scope?.textContent).toContain('F2');
      expect(scope?.textContent).toContain('New order');
      expect(scope?.textContent).toContain('Esc');
    });

    it('opens from the header button, for a mouse', () => {
      (
        fixture.nativeElement.querySelector(
          '[data-testid="shell-shortcuts-button"]',
        ) as HTMLButtonElement
      ).click();
      fixture.detectChanges();

      expect(sheet()).not.toBeNull();
    });

    it('lists the keys of the screen that is open, ahead of the shell’s, and drops them when it goes', () => {
      const unregister = registry.register({
        id: 'board',
        title: () => 'Order board',
        shortcuts: [{ keys: ['r'], caps: ['r'], label: () => 'Refresh the board', run: () => {} }],
      });
      press('?');

      const scopes = [...(sheet()?.querySelectorAll('[data-testid^="shortcut-scope-"]') ?? [])];
      expect(scopes.map((s) => s.getAttribute('data-testid'))).toEqual([
        'shortcut-scope-board',
        'shortcut-scope-shell',
      ]);
      expect(scopes[0].textContent).toContain('Refresh the board');

      unregister();
      fixture.detectChanges();
      expect(sheet()?.querySelector('[data-testid="shortcut-scope-board"]')).toBeNull();
    });

    it('closes on Escape and on its Close button', () => {
      press('?');
      expect(sheet()).not.toBeNull();

      press('Escape');
      expect(sheet()).toBeNull();

      press('?');
      (
        fixture.nativeElement.querySelector(
          '[data-testid="shortcut-sheet-close"]',
        ) as HTMLButtonElement
      ).click();
      fixture.detectChanges();
      expect(sheet()).toBeNull();
    });

    it('does not open while the operator is typing a question mark into a field', () => {
      const field = document.createElement('input');
      document.body.appendChild(field);
      try {
        press('?', field);
        expect(sheet()).toBeNull();
      } finally {
        field.remove();
      }
    });

    it('says what a key does in the language the operator chose', () => {
      TestBed.inject(I18n).setLocale('ru');
      press('?');

      expect(sheet()?.textContent).toContain('Горячие клавиши');
      expect(sheet()?.textContent).toContain('Новый заказ');
    });
  });

  describe('the brand picker', () => {
    const picker = (): HTMLSelectElement | null =>
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="shell-brand-picker"]');

    it('is not drawn for a tenant with one brand, or before the brands are known', () => {
      expect(picker()).toBeNull();

      choice.offer([{ id: 'b1', displayName: 'Rayhon' }]);
      fixture.detectChanges();

      expect(picker()).toBeNull();
    });

    it('is drawn, captioned, for a tenant with several brands, showing the brand in effect', () => {
      choice.offer([
        { id: 'b1', displayName: 'Rayhon' },
        { id: 'b2', displayName: 'Evos' },
      ]);
      fixture.detectChanges();

      const select = picker()!;
      expect([...select.options].map((option) => option.textContent?.trim())).toEqual([
        'Rayhon',
        'Evos',
      ]);
      expect(select.value).toBe('b1');
      expect((fixture.nativeElement as HTMLElement).querySelector('.brand')?.textContent).toContain(
        'Brand',
      );
    });

    it('re-points the console at the brand picked, remembers it, and builds the open screen afresh', async () => {
      TestBed.inject(Router).initialNavigation();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(probeCreated).toBe(1);

      choice.offer([
        { id: 'b1', displayName: 'Rayhon' },
        { id: 'b2', displayName: 'Evos' },
      ]);
      fixture.detectChanges();
      // The brand list arriving is not a switch: the screen is not rebuilt for it.
      expect(probeCreated).toBe(1);

      const select = picker()!;
      select.value = 'b2';
      select.dispatchEvent(new Event('change'));
      await fixture.whenStable();
      fixture.detectChanges();

      expect(choice.brandId()).toBe('b2');
      expect(localStorage.getItem('horecaos.operations.brandId')).toBe('b2');
      expect(probeCreated).toBe(2);
    });

    it('does nothing when the brand already in effect is picked again', async () => {
      TestBed.inject(Router).initialNavigation();
      await fixture.whenStable();
      choice.offer([
        { id: 'b1', displayName: 'Rayhon' },
        { id: 'b2', displayName: 'Evos' },
      ]);
      fixture.detectChanges();

      const select = picker()!;
      select.value = 'b1';
      select.dispatchEvent(new Event('change'));
      await fixture.whenStable();

      expect(probeCreated).toBe(1);
    });
  });
});

/**
 * A brand pick rebuilds the open screen at once, and that screen reads its location the moment it
 * has awaited `CurrentLocation.ensureLoaded()`. The real `CurrentLocation` is used here (not the
 * fake the specs above share) because the defect lived in how it answers in the gap between the
 * pick and the new brand's branches arriving: it went on naming the previous brand's branch, so the
 * screen loaded -- and later wrote to -- the brand the header no longer showed.
 */
describe('Shell: the location a screen rebuilt by a brand pick reads', () => {
  const BRANDS = [
    { id: 'b1', displayName: 'Rayhon' },
    { id: 'b2', displayName: 'Evos' },
  ];
  let fixture: ComponentFixture<Shell>;
  let evosBranches: Subject<{ value: LocationOption[] }>;
  let seen: (LocationScope | null)[];
  let probeLocation: CurrentLocation;

  /** Only the three reads the brand path makes; the Evos branches are held back until the test releases them. */
  class StubApi {
    get(path: string): Observable<unknown> {
      if (path === '/api/v1/session/context') {
        return of({
          value: {
            subject: 'operator-1',
            activeTenantId: 't1',
            scopes: [
              {
                scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
                roleCode: 'tenant-owner',
                capabilities: [],
              },
            ],
          },
        });
      }
      if (path === '/api/v1/operations/tenants/t1/brands') {
        return of({ value: BRANDS });
      }
      if (path === '/api/v1/operations/tenants/t1/brands/b1/locations') {
        return of({ value: [locationOption('l1', 'Chilanzar')] });
      }
      if (path === '/api/v1/operations/tenants/t1/brands/b2/locations') {
        return evosBranches;
      }
      return NEVER;
    }
  }

  @Component({ selector: 'q-probe', template: '<p>screen</p>' })
  class Probe {
    constructor() {
      // What every location-scoped screen does first: wait for the location, then read it.
      const location = inject(CurrentLocation);
      probeLocation = location;
      void location.ensureLoaded().then(() => seen.push(location.scope()));
    }
  }

  beforeEach(async () => {
    localStorage.removeItem('horecaos.operations.brandId');
    localStorage.removeItem('horecaos.operations.locationId');
    evosBranches = new Subject();
    seen = [];
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([{ path: '', component: Probe }]),
        { provide: ApiClient, useValue: new StubApi() },
        { provide: Auth, useValue: new FakeAuth() },
        { provide: OwnPreferences, useValue: new FakePreferences() },
        { provide: OwnProfile, useValue: new FakeOwnProfile() },
        // The stream opens a real `fetch`; nothing here is about it.
        { provide: RealtimeClient, useValue: { state: signal(null), onFrame: () => () => {} } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    TestBed.inject(Router).initialNavigation();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  async function pickEvos(): Promise<void> {
    const select = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="shell-brand-picker"]',
    ) as HTMLSelectElement;
    select.value = 'b2';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('starts on the first brand’s branch', () => {
    expect(seen).toEqual([{ tenantId: 't1', brandId: 'b1', locationId: 'l1' }]);
  });

  it('waits for the picked brand’s branches instead of reading the previous brand’s', async () => {
    await pickEvos();

    // The screen built by the pick is waiting, and nothing it could read yet is Rayhon's.
    expect(seen).toHaveLength(1);
    expect(probeLocation.scope()).toBeNull();
    expect(probeLocation.denied()).toBe(false);

    evosBranches.next({ value: [locationOption('l9', 'Sergeli')] });
    evosBranches.complete();
    await fixture.whenStable();
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(seen).toEqual([
      { tenantId: 't1', brandId: 'b1', locationId: 'l1' },
      { tenantId: 't1', brandId: 'b2', locationId: 'l9' },
    ]);
  });

  it('draws no location picker for the old brand’s branches while the new brand’s are being read', async () => {
    await pickEvos();

    expect(probeLocation.options()).toEqual([]);
  });
});

/**
 * ADR 0164: the deliveries profile is a presentation of the one console. It puts Kitchen, Marketing and
 * Inbox away, calls Orders what they are for this tenant and opens with Delivery first -- and it can only
 * ever remove things from a rail the capability filter already allowed.
 */
describe('Shell: the console profile (ADR 0164)', () => {
  const DELIVERIES = {
    variant: 'DELIVERIES',
    landing: '/deliveries',
    hiddenSections: ['/kitchen', '/marketing', '/inbox'],
    handovers: ['DELIVERY'],
  };
  const STANDARD = {
    variant: 'STANDARD',
    landing: '/today',
    hiddenSections: [],
    handovers: ['DELIVERY', 'PICKUP', 'DINE_IN'],
  };

  function stubApi(profile: unknown, capabilities: readonly string[]) {
    return {
      get(path: string): Observable<unknown> {
        if (path === '/api/v1/session/context') {
          return of({
            value: {
              subject: 'operator-1',
              activeTenantId: 't1',
              scopes: [
                {
                  scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
                  roleCode: 'tenant-owner',
                  capabilities,
                },
              ],
            },
          });
        }
        if (path === '/api/v1/session/console-profile') {
          return profile === null ? throwError(() => new Error('down')) : of({ value: profile });
        }
        return NEVER;
      },
    };
  }

  async function open(
    profile: unknown,
    capabilities: readonly string[] = [...new Set(NAV_ITEMS.map((item) => item.capability))],
  ): Promise<{ readonly fixture: ComponentFixture<Shell>; readonly host: HTMLElement }> {
    localStorage.removeItem('horecaos.console.showAllSections');
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: stubApi(profile, capabilities) },
        { provide: Auth, useValue: new FakeAuth() },
        { provide: OwnProfile, useValue: new FakeOwnProfile() },
        { provide: RealtimeClient, useValue: { state: signal(null), onFrame: () => () => {} } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    await fixture.whenStable();
    // The tenant read and then the profile read are promise chains the fixture cannot see.
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    return { fixture, host: fixture.nativeElement as HTMLElement };
  }

  const railPaths = (host: HTMLElement): (string | null)[] =>
    [...host.querySelectorAll('.rail__item')].map((a) => a.getAttribute('href'));
  const railLabels = (host: HTMLElement): (string | undefined)[] =>
    [...host.querySelectorAll('.rail__item')].map((a) => a.textContent?.trim());

  afterEach(() => localStorage.removeItem('horecaos.console.showAllSections'));

  it('removes exactly the put-away sections and keeps everything else', async () => {
    const standard = railPaths((await open(STANDARD)).host);
    TestBed.resetTestingModule();
    const deliveries = railPaths((await open(DELIVERIES)).host);

    expect(standard).toContain('/kitchen');
    expect(standard).toContain('/marketing');
    expect(standard).toContain('/inbox');
    expect(deliveries).not.toContain('/kitchen');
    expect(deliveries).not.toContain('/marketing');
    expect(deliveries).not.toContain('/inbox');
    expect([...deliveries].sort()).toEqual(
      standard.filter((path) => !['/kitchen', '/marketing', '/inbox'].includes(path ?? '')).sort(),
    );
  });

  it('puts Delivery first and calls Orders «Deliveries»', async () => {
    const { host } = await open(DELIVERIES);

    expect(railPaths(host)[0]).toBe('/delivery');
    expect(railLabels(host)).toContain('Deliveries');
    expect(railLabels(host)).not.toContain('Orders');
  });

  it('leaves the standard rail exactly as it was, Orders and Kitchen included', async () => {
    const { host } = await open(STANDARD);

    expect(railPaths(host)[0]).toBe('/today');
    expect(railLabels(host)).toContain('Orders');
    expect(host.querySelector('[data-testid="shell-show-all-sections"]')).toBeNull();
  });

  it('never puts back a section the capability filter removed', async () => {
    // The profile hides Kitchen, but this operator never held its capability either: still absent.
    const { host } = await open(DELIVERIES, [
      'ORDER_READ',
      'DELIVERY_PLAN_READ',
      'KITCHEN_TICKET_READ',
    ]);

    expect(railPaths(host)).toEqual(['/delivery', '/today', '/orders']);
  });

  it('is the standard rail when the profile cannot be read', async () => {
    const { host } = await open(null);

    expect(railPaths(host)).toContain('/kitchen');
    expect(railPaths(host)[0]).toBe('/today');
  });

  it('«Show all sections» restores the rail without another request, and hides it again', async () => {
    const { fixture, host } = await open(DELIVERIES);
    const button = host.querySelector('[data-testid="shell-show-all-sections"]') as HTMLElement;
    expect(button.textContent?.trim()).toBe('Show all sections');

    button.click();
    fixture.detectChanges();

    expect(railPaths(host)).toContain('/kitchen');
    expect(railPaths(host)).toContain('/marketing');
    expect(button.textContent?.trim()).toBe('Hide the rest');

    button.click();
    fixture.detectChanges();
    expect(railPaths(host)).not.toContain('/kitchen');
  });
});

/**
 * The language switcher offers a language when this build has its catalogue **and** the registry has
 * it live in the staff-UI tier (ADR 0149): a language the platform declares but has not made live is
 * never offered, whatever the build holds, and a language the registry takes out of the tier goes from
 * the list without a change to the console.
 */
describe('Shell language switcher', () => {
  async function open(): Promise<ComponentFixture<Shell>> {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([]),
        { provide: Auth, useValue: new FakeAuth() },
        { provide: OwnPreferences, useValue: new FakePreferences() },
        { provide: OwnProfile, useValue: new FakeOwnProfile() },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(Shell);
    fixture.detectChanges();
    return fixture;
  }

  const offered = (fixture: ComponentFixture<Shell>): string[] =>
    Array.from(
      fixture.nativeElement.querySelectorAll(
        '.locale__select option',
      ) as NodeListOf<HTMLOptionElement>,
    ).map((option) => option.value);

  afterEach(() => seedPlatformLocalesForTesting(REGISTRY_FIXTURE));

  it('offers the languages the build has a catalogue for and the registry has live', async () => {
    expect(offered(await open())).toEqual(['ru', 'uz-Latn', 'en']);
  });

  it('stops offering a language the registry takes out of the staff-UI tier', async () => {
    seedPlatformLocalesForTesting({
      ...REGISTRY_FIXTURE,
      locales: REGISTRY_FIXTURE.locales.map((entry) =>
        entry.tag === 'en' ? { ...entry, tiers: ['CONTENT' as const, 'MESSAGES' as const] } : entry,
      ),
    });

    expect(offered(await open())).toEqual(['ru', 'uz-Latn']);
  });

  it('does not offer a language that is live in the registry but has no catalogue in this build', async () => {
    seedPlatformLocalesForTesting({
      ...REGISTRY_FIXTURE,
      locales: REGISTRY_FIXTURE.locales.map((entry) =>
        entry.tag === 'kk' ? { ...entry, tiers: ['STAFF_UI' as const] } : entry,
      ),
    });

    expect(offered(await open())).toEqual(['ru', 'uz-Latn', 'en']);
  });
});
