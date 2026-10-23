import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import {
  BranchOrder,
  OrderedBranch,
  StorefrontMerchandisingApi,
} from './storefront-merchandising-api';
import { StorefrontBranchOrder } from './storefront-branch-order';

const branch = (id: string, order: number | null, status = 'ACTIVE'): OrderedBranch => ({
  locationId: id,
  displayName: `Branch ${id}`,
  status,
  storefrontSortOrder: order,
});

class FakeApi {
  branchesNow: readonly OrderedBranch[] = [];
  /** The version the platform holds the order at; a colleague's save moves it. */
  version = 5;
  readFails: ApiError | null = null;
  writeFails: ApiError | null = null;
  /** What a colleague saved after this console read: the next save finds the order has moved. */
  colleagueSaves: readonly OrderedBranch[] | null = null;
  readonly orders: (readonly string[])[] = [];
  /** The version each save presented. */
  readonly presented: number[] = [];

  branches(): Promise<BranchOrder> {
    return this.readFails
      ? Promise.reject(this.readFails)
      : Promise.resolve({ branches: this.branchesNow, version: this.version });
  }
  setBranchOrder(
    _scope: unknown,
    ids: readonly string[],
    expectedVersion: number,
  ): Promise<BranchOrder> {
    this.presented.push(expectedVersion);
    if (this.colleagueSaves) {
      this.branchesNow = this.colleagueSaves;
      this.colleagueSaves = null;
      this.version += 1;
    }
    if (expectedVersion !== this.version) {
      return Promise.reject(new ApiError('STALE_VERSION', 409, null, null));
    }
    if (this.writeFails) {
      return Promise.reject(this.writeFails);
    }
    this.orders.push(ids);
    this.branchesNow = this.branchesNow.map((b) => {
      const at = ids.indexOf(b.locationId);
      return { ...b, storefrontSortOrder: at < 0 ? null : (at + 1) * 10 };
    });
    this.version += 1;
    return Promise.resolve({ branches: this.branchesNow, version: this.version });
  }
}

async function setup(
  api = new FakeApi(),
): Promise<{ fixture: ComponentFixture<StorefrontBranchOrder>; root: HTMLElement; api: FakeApi }> {
  TestBed.configureTestingModule({
    providers: [
      { provide: StorefrontMerchandisingApi, useValue: api },
      { provide: CurrentBrand, useValue: { scope: signal({ tenantId: 't', brandId: 'b' }) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(StorefrontBranchOrder);
  fixture.detectChanges();
  await vi.waitFor(() => {
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="branch-order-save"]')).not.toBeNull();
  });
  return { fixture, root: fixture.nativeElement as HTMLElement, api };
}

const q = (root: HTMLElement, id: string): HTMLElement | null =>
  root.querySelector(`[data-testid="${id}"]`);
const click = (root: HTMLElement, id: string): void => (q(root, id) as HTMLElement).click();
const placedIds = (root: HTMLElement): string[] =>
  Array.from(root.querySelectorAll('[data-testid^="branch-placed-"]')).map((row) =>
    (row.getAttribute('data-testid') ?? '').replace('branch-placed-', ''),
  );
const unplacedIds = (root: HTMLElement): string[] =>
  Array.from(root.querySelectorAll('[data-testid^="branch-unplaced-"]')).map((row) =>
    (row.getAttribute('data-testid') ?? '').replace('branch-unplaced-', ''),
  );

describe('StorefrontBranchOrder (gap-map row 6.8; ADR 0183 D17)', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists the placed branches in the stored order and the rest as not placed', async () => {
    const api = new FakeApi();
    api.branchesNow = [branch('c', 20), branch('a', 10), branch('b', null), branch('d', null)];
    const { root } = await setup(api);

    expect(placedIds(root)).toEqual(['a', 'c']);
    expect(unplacedIds(root)).toEqual(['b', 'd']);
    expect((q(root, 'branch-order-save') as HTMLButtonElement).disabled).toBe(true);
  });

  it('says what the storefront does when nothing is placed', async () => {
    const api = new FakeApi();
    api.branchesNow = [branch('a', null)];
    const { root } = await setup(api);

    expect(q(root, 'branch-order-none-placed')?.textContent).toContain(
      'lists the branches by name',
    );
  });

  it('places a branch last, moves it, and saves the whole order in one request', async () => {
    const api = new FakeApi();
    api.branchesNow = [branch('a', 10), branch('b', null), branch('c', null)];
    const { fixture, root } = await setup(api);

    click(root, 'branch-place-b');
    fixture.detectChanges();
    click(root, 'branch-place-c');
    fixture.detectChanges();
    expect(placedIds(root)).toEqual(['a', 'b', 'c']);
    expect(unplacedIds(root)).toEqual([]);

    click(root, 'branch-up-c');
    fixture.detectChanges();
    expect(placedIds(root)).toEqual(['a', 'c', 'b']);
    expect((q(root, 'branch-order-save') as HTMLButtonElement).disabled).toBe(false);

    click(root, 'branch-order-save');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(q(root, 'branch-order-saved')).not.toBeNull();
    });
    expect(api.orders).toEqual([['a', 'c', 'b']]);
    expect(api.presented).toEqual([5]);
    expect((q(root, 'branch-order-save') as HTMLButtonElement).disabled).toBe(true);
    expect(placedIds(root)).toEqual(['a', 'c', 'b']);
  });

  it('refuses to overwrite a colleague: the order moved meanwhile, so the screen shows it and drops the arrangement', async () => {
    const api = new FakeApi();
    api.branchesNow = [branch('a', 10), branch('b', 20), branch('c', null)];
    const { fixture, root } = await setup(api);

    click(root, 'branch-down-a');
    fixture.detectChanges();
    expect(placedIds(root)).toEqual(['b', 'a']);
    // A colleague places c while this console is open; this save names only a and b, which would unplace c.
    api.colleagueSaves = [branch('a', 10), branch('b', 20), branch('c', 30)];
    click(root, 'branch-order-save');

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(q(root, 'branch-order-error')?.textContent).toContain('Someone changed');
    });
    expect(api.orders).toEqual([]);
    expect(placedIds(root)).toEqual(['a', 'b', 'c']);
    expect(q(root, 'branch-order-saved')).toBeNull();
    expect((q(root, 'branch-order-save') as HTMLButtonElement).disabled).toBe(true);

    // Making the change again on the current order is saved, presenting the version just read.
    click(root, 'branch-down-a');
    fixture.detectChanges();
    click(root, 'branch-order-save');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(q(root, 'branch-order-saved')).not.toBeNull();
    });
    expect(api.orders).toEqual([['b', 'a', 'c']]);
    expect(api.presented).toEqual([5, 6]);
  });

  it('takes a branch out of the order, and an order put back as it was is not a change', async () => {
    const api = new FakeApi();
    api.branchesNow = [branch('a', 10), branch('b', 20)];
    const { fixture, root } = await setup(api);

    click(root, 'branch-unplace-a');
    fixture.detectChanges();
    expect(placedIds(root)).toEqual(['b']);
    expect(unplacedIds(root)).toEqual(['a']);
    expect((q(root, 'branch-order-save') as HTMLButtonElement).disabled).toBe(false);

    click(root, 'branch-place-a');
    fixture.detectChanges();
    click(root, 'branch-up-a');
    fixture.detectChanges();
    expect(placedIds(root)).toEqual(['a', 'b']);
    expect((q(root, 'branch-order-save') as HTMLButtonElement).disabled).toBe(true);
  });

  it('cannot move the first branch up or the last one down', async () => {
    const api = new FakeApi();
    api.branchesNow = [branch('a', 10), branch('b', 20)];
    const { root } = await setup(api);

    expect((q(root, 'branch-up-a') as HTMLButtonElement).disabled).toBe(true);
    expect((q(root, 'branch-down-b') as HTMLButtonElement).disabled).toBe(true);
  });

  it('undoes the arrangement', async () => {
    const api = new FakeApi();
    api.branchesNow = [branch('a', 10), branch('b', 20)];
    const { fixture, root } = await setup(api);

    click(root, 'branch-down-a');
    fixture.detectChanges();
    expect(placedIds(root)).toEqual(['b', 'a']);

    click(root, 'branch-order-reset');
    fixture.detectChanges();
    expect(placedIds(root)).toEqual(['a', 'b']);
    expect(api.orders).toEqual([]);
  });

  it('keeps the arrangement and says why when the save is refused', async () => {
    const api = new FakeApi();
    api.branchesNow = [branch('a', 10), branch('b', 20)];
    api.writeFails = new ApiError('VALIDATION_FAILED', 400, null, null);
    const { fixture, root } = await setup(api);

    click(root, 'branch-down-a');
    fixture.detectChanges();
    click(root, 'branch-order-save');

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(q(root, 'branch-order-error')).not.toBeNull();
    });
    expect(placedIds(root)).toEqual(['b', 'a']);
    expect(q(root, 'branch-order-saved')).toBeNull();
    expect((q(root, 'branch-order-save') as HTMLButtonElement).disabled).toBe(false);
  });

  it('marks a branch that customers cannot see anyway', async () => {
    const api = new FakeApi();
    api.branchesNow = [branch('a', null, 'SUSPENDED')];
    const { root } = await setup(api);

    expect(q(root, 'branch-unplaced-a')?.textContent).toContain('not active');
  });

  it('shows the wall to someone who cannot read locations', async () => {
    const api = new FakeApi();
    api.readFails = new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null);
    TestBed.configureTestingModule({
      providers: [
        { provide: StorefrontMerchandisingApi, useValue: api },
        { provide: CurrentBrand, useValue: { scope: signal({ tenantId: 't', brandId: 'b' }) } },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(StorefrontBranchOrder);
    fixture.detectChanges();

    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('q-denied-state')).not.toBeNull();
    });
    expect(fixture.nativeElement.querySelector('[data-testid="branch-order-save"]')).toBeNull();
  });
});
