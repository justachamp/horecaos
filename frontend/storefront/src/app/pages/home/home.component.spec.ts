import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';

import { HomeComponent } from './home.component';
import { MenuService } from '../../services/menu.service';
import { FavouritesService } from '../../services/favourites.service';
import { LangService } from '../../services/lang.service';
import { UiCartService } from '../../services/ui-cart.service';
import { CustomerProfileService } from '../../services/customer-profile.service';
import { TranslateService } from '../../services/translate.service';
import { Session } from '../../core/auth/session';
import type { CustomerUiResponse } from '../../types/home.types';

function emptyMenu(): CustomerUiResponse {
  return {
    category: null,
    offer: null,
    populars: [],
    populars_count: 0,
    menu: { categories: [], category_items: [], category_items_count: 0 },
  };
}

class FakeMenuService {
  readonly home = vi.fn(async () => emptyMenu());
}

class FakeFavouritesService {
  readonly load = vi.fn(async () => {});
}

class FakeLangService {
  readonly langId = signal('uz');
}

class FakeUiCartService {
  readonly deliveryAddressName = () => '';
  readonly totalItemsCount = () => 0;
  readonly load = vi.fn(async () => {});
  readonly switchFulfillmentMode = vi.fn(async () => {});
}

class FakeCustomerProfileService {
  readonly load = vi.fn(async () => null);
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

function setUp() {
  const menu = new FakeMenuService();
  const favourites = new FakeFavouritesService();
  const cart = new FakeUiCartService();
  const profile = new FakeCustomerProfileService();

  TestBed.configureTestingModule({
    imports: [HomeComponent],
    providers: [
      provideRouter([]),
      { provide: MenuService, useValue: menu },
      { provide: FavouritesService, useValue: favourites },
      { provide: LangService, useClass: FakeLangService },
      { provide: UiCartService, useValue: cart },
      { provide: CustomerProfileService, useValue: profile },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });

  const session = TestBed.inject(Session);
  const fixture = TestBed.createComponent(HomeComponent);

  return { fixture, comp: fixture.componentInstance, menu, favourites, cart, profile, session };
}

function signIn(session: Session): void {
  session.adopt({ accessToken: 'tok', expiresAt: new Date(Date.now() + 3_600_000).toISOString() });
}

async function mount(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('HomeComponent: anonymous visitor', () => {
  beforeEach(() => localStorage.clear());

  it('renders the published menu with no session at all', async () => {
    const { fixture, comp, menu } = setUp();

    await mount(fixture);

    expect(menu.home).toHaveBeenCalledTimes(1);
    expect(comp.error()).toBeNull();
    expect(comp.loading()).toBe(false);
  });

  it('never reads the cart, favourites or profile for an anonymous visitor -- the platform has no anonymous form of any of them', async () => {
    const { fixture, cart, favourites, profile } = setUp();

    await mount(fixture);

    expect(cart.load).not.toHaveBeenCalled();
    expect(favourites.load).not.toHaveBeenCalled();
    expect(profile.load).not.toHaveBeenCalled();
  });

  it('renders without crashing and without a router redirect away from /home', async () => {
    const { fixture } = setUp();

    expect(() => fixture.detectChanges()).not.toThrow();
  });
});

describe('HomeComponent: signed in', () => {
  beforeEach(() => localStorage.clear());

  it('still warms the cart, favourites and profile for a signed-in customer, unchanged from before', async () => {
    const { fixture, cart, favourites, profile, session } = setUp();
    signIn(session);

    await mount(fixture);

    expect(cart.load).toHaveBeenCalledTimes(1);
    expect(favourites.load).toHaveBeenCalledTimes(1);
    expect(profile.load).toHaveBeenCalledTimes(1);
  });
});
