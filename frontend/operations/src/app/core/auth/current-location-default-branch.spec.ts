import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { environment } from '../../../environments/environment';
import { ApiClient } from '../api/api-client';
import { OwnPreferences } from '../preferences/own-preferences';
import { ShellDocument } from '../preferences/preference-documents';
import { CurrentLocation } from './current-location';
import { SessionContext } from './session-context';

const STORAGE_KEY = 'horecaos.operations.locationId';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

function tick(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/**
 * ADR 0160, decision 4: one rule for language and for branch. The branch chosen on this device wins; the
 * person's stored default applies where this device has chosen none and the roster still contains it;
 * otherwise the first branch. The default is read with the roster so the first screen opens on the right
 * branch and not on the first one and then on the right one.
 */
describe('CurrentLocation: the default branch (ADR 0160)', () => {
  let http: HttpTestingController;
  let shell: ReturnType<typeof signal<ShellDocument>>;
  let release: () => void;
  let preferencesLoaded: boolean;

  beforeEach(() => {
    localStorage.clear();
    shell = signal<ShellDocument>({});
    preferencesLoaded = false;
    const loading = new Promise<void>((resolve) => {
      release = () => {
        preferencesLoaded = true;
        resolve();
      };
    });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        ApiClient,
        CurrentLocation,
        { provide: OwnPreferences, useValue: { shell, ensureLoaded: () => loading } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** A brand-scoped operator whose brand has these branches. */
  async function load(branchIds: readonly string[] = ['l1', 'l2', 'l3']): Promise<CurrentLocation> {
    const loc = TestBed.inject(CurrentLocation);
    const loaded = loc.ensureLoaded();
    http.expectOne(url('/api/v1/session/context')).flush(brandOperator());
    await tick();
    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b1/locations'))
      .flush(branchIds.map((id) => branch(id)));
    release();
    await loaded;
    return loc;
  }

  it('opens on the person’s default branch where this device has chosen none', async () => {
    shell.set({ defaultLocationId: 'l2' });

    const loc = await load();

    expect(loc.scope()?.locationId).toBe('l2');
  });

  it('opens on the branch chosen on this device, over the default', async () => {
    localStorage.setItem(STORAGE_KEY, 'l3');
    shell.set({ defaultLocationId: 'l2' });

    const loc = await load();

    expect(loc.scope()?.locationId).toBe('l3');
  });

  it('ignores a default the roster no longer contains and falls back to the first branch', async () => {
    shell.set({ defaultLocationId: 'l-gone' });

    const loc = await load();

    expect(loc.scope()?.locationId).toBe('l1');
  });

  it('opens on the first branch for a person with no default', async () => {
    const loc = await load();

    expect(loc.scope()?.locationId).toBe('l1');
  });

  it('follows a default that arrives after the roster, and gives way to a pick made on this device', async () => {
    const loc = await load();
    expect(loc.scope()?.locationId).toBe('l1');

    shell.set({ defaultLocationId: 'l3' });
    expect(loc.scope()?.locationId).toBe('l3');

    loc.selectLocation('l2');
    shell.set({ defaultLocationId: 'l3' });
    expect(loc.scope()?.locationId).toBe('l2');
  });

  it('reads the default with the roster: it does not call the first screen ready before the default is in', async () => {
    const loc = TestBed.inject(CurrentLocation);
    let ready = false;
    void loc.ensureLoaded().then(() => (ready = true));
    http.expectOne(url('/api/v1/session/context')).flush(brandOperator());
    await tick();
    http
      .expectOne(url('/api/v1/operations/tenants/t1/brands/b1/locations'))
      .flush([branch('l1'), branch('l2')]);
    await tick();

    expect(preferencesLoaded).toBe(false);
    expect(ready).toBe(false);

    shell.set({ defaultLocationId: 'l2' });
    release();
    await tick();

    expect(ready).toBe(true);
    expect(loc.scope()?.locationId).toBe('l2');
  });
});

function brandOperator(): SessionContext {
  return {
    subject: 'operator-1',
    activeTenantId: 't1',
    scopes: [
      {
        scope: { type: 'BRAND', tenantId: 't1', brandId: 'b1', locationId: null },
        roleCode: 'MANAGER',
      },
    ],
  };
}

function branch(id: string) {
  return { id, tenantId: 't1', brandId: 'b1', displayName: `Branch ${id}`, status: 'ACTIVE' };
}
