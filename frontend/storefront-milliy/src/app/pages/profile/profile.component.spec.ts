import { TestBed } from '@angular/core/testing';
import { Component, signal } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { ProfileComponent } from './profile.component';
import { CustomerProfileService } from '../../services/customer-profile.service';
import { CustomerOtp } from '../../core/session/customer-otp';
import { LangService } from '../../services/lang.service';
import { OrdersService, type ApiOrder } from '../../services/orders.service';
import { ReviewsService, type ReviewResponse } from '../../services/reviews.service';
import { TranslateService } from '../../services/translate.service';
import { HorecaOSApiError } from '../../core/api/problem-details';
import type { CustomerProfile } from '../../core/api/customer-api';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}(${JSON.stringify(params)})` : key;
  current = (): Record<string, unknown> => ({});
  setLang = vi.fn();
}

@Component({ selector: 'app-test-target', template: '' })
class TestTargetComponent {}

function profile(displayName: string | null): CustomerProfile {
  return {
    accountId: 'acc-1',
    brandId: 'brand-1',
    status: 'ACTIVE',
    identityMode: 'BRAND',
    profileScope: 'BRAND',
    identityPolicyVersion: 1,
    displayName,
    preferredLocale: 'uz',
    preferredTimezone: 'Asia/Tashkent',
    contactPoints: [],
    version: 1,
    createdAt: new Date().toISOString(),
  };
}

function completedOrder(id: string): ApiOrder {
  return {
    id: id as unknown as number,
    status: { id: 'COMPLETED', name: 'COMPLETED' },
    total: 25_000,
    total_price: 25_000,
    order_number: 'PN-1' as unknown as number,
    number: 1,
    created_date: new Date().toISOString(),
    created_time: new Date().toISOString(),
    actions: [],
  };
}

class FakeCustomerProfileService {
  private readonly current = signal<CustomerProfile | null>(profile('Jasur Rahimov'));
  profile = () => this.current();
  firstName = () => this.current()?.displayName?.split(' ')[0] ?? '';
  lastName = () => this.current()?.displayName?.split(' ').slice(1).join(' ') ?? '';
  load = vi.fn(async () => this.current());
  update = vi.fn(async () => this.current()!);

  setProfile(value: CustomerProfile | null): void {
    this.current.set(value);
  }
}

class FakeCustomerOtp {
  signOut = vi.fn(async () => {});
}

class FakeOrdersService {
  getOrders = vi.fn(() => of<ApiOrder[]>([]));
}

class FakeReviewsService {
  myReviews = vi.fn(async () => ({ items: [] as ReviewResponse[], nextCursor: null }));
  submit = vi.fn(
    async (): Promise<ReviewResponse> => ({
      id: 'r1',
      orderId: 'o1',
      rating: 5,
      comment: null,
      submittedAt: new Date().toISOString(),
    }),
  );
}

async function setUp(
  configure: (
    profileService: FakeCustomerProfileService,
    orders: FakeOrdersService,
    reviews: FakeReviewsService,
  ) => void = () => {},
) {
  const profileService = new FakeCustomerProfileService();
  const orders = new FakeOrdersService();
  const reviews = new FakeReviewsService();
  const customerOtp = new FakeCustomerOtp();
  configure(profileService, orders, reviews);

  TestBed.configureTestingModule({
    imports: [ProfileComponent],
    providers: [
      provideRouter([{ path: 'home', component: TestTargetComponent }]),
      { provide: CustomerProfileService, useValue: profileService },
      { provide: CustomerOtp, useValue: customerOtp },
      { provide: LangService, useValue: { langId: signal('uz') } },
      { provide: OrdersService, useValue: orders },
      { provide: ReviewsService, useValue: reviews },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });

  const fixture = TestBed.createComponent(ProfileComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return {
    fixture,
    profileService,
    orders,
    reviews,
    customerOtp,
    translate: TestBed.inject(TranslateService) as unknown as FakeTranslateService,
    router: TestBed.inject(Router),
  };
}

describe('ProfileComponent -- denied/empty states', () => {
  it('shows a guest fallback name when there is no display name', async () => {
    const { fixture } = await setUp((profileService) => profileService.setProfile(profile(null)));

    expect(fixture.nativeElement.textContent).toContain('profile.guestName');
  });

  it('never shows a rating card when there is nothing eligible to rate', async () => {
    const { fixture } = await setUp();

    expect(fixture.nativeElement.querySelector('.rate-card')).toBeNull();
  });

  it('never shows a rating card for a completed order that already has a review', async () => {
    const { fixture } = await setUp((_p, orders, reviews) => {
      orders.getOrders.mockReturnValue(of([completedOrder('o1')]));
      reviews.myReviews.mockResolvedValue({
        items: [{ id: 'r1', orderId: 'o1', rating: 4, comment: null, submittedAt: new Date().toISOString() }],
        nextCursor: null,
      });
    });

    expect(fixture.nativeElement.querySelector('.rate-card')).toBeNull();
  });

  it('surfaces a profile load failure as a translated message', async () => {
    const { fixture } = await setUp((profileService) => {
      profileService.load.mockRejectedValue(new Error('offline'));
    });

    expect(fixture.nativeElement.textContent).toContain('profile.loadError');
  });
});

describe('ProfileComponent -- sign-out and language', () => {
  it('signs out through the platform, then leaves for /home', async () => {
    const { fixture, customerOtp, router } = await setUp();
    const navigateSpy = vi.spyOn(router, 'navigate');

    (fixture.nativeElement.querySelector('.signout') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(customerOtp.signOut).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/home']);
  });

  it('switching language calls through with exactly the code that was tapped', async () => {
    const { fixture, translate } = await setUp();

    const chips = fixture.nativeElement.querySelectorAll('.lang-chip');
    const ru = Array.from(chips as NodeListOf<HTMLButtonElement>).find((el) =>
      el.textContent?.includes('RU'),
    )!;
    ru.click();

    expect(translate.setLang).toHaveBeenCalledWith('ru');
  });
});

describe('ProfileComponent -- order rating (ADR 0071)', () => {
  it('does not submit while no star has been picked', async () => {
    const { fixture, reviews } = await setUp((_p, orders) => {
      orders.getOrders.mockReturnValue(of([completedOrder('o1')]));
    });

    const submit = fixture.nativeElement.querySelector('.btn--block') as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    submit.click();

    expect(reviews.submit).not.toHaveBeenCalled();
  });

  it('sends exactly the picked rating and the typed comment for the eligible order', async () => {
    const { fixture, reviews } = await setUp((_p, orders) => {
      orders.getOrders.mockReturnValue(of([completedOrder('o1')]));
    });

    const stars = fixture.nativeElement.querySelectorAll('.star-btn');
    (stars[3] as HTMLButtonElement).click(); // the 4th star: a rating of 4
    fixture.detectChanges();
    const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = 'Juda mazali!';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.btn--block') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(reviews.submit).toHaveBeenCalledWith('o1', 4, 'Juda mazali!');
  });

  it('shows the thank-you state and never a raw code after a successful submission', async () => {
    const { fixture } = await setUp((_p, orders) => {
      orders.getOrders.mockReturnValue(of([completedOrder('o1')]));
    });

    (fixture.nativeElement.querySelectorAll('.star-btn')[4] as HTMLButtonElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.btn--block') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('profile.rateThanks');
    expect(fixture.nativeElement.querySelector('.rate-card')).toBeNull();
  });

  it('a refusal (already reviewed, or not yet completed) surfaces a translated message, never the raw ADR 0031 code', async () => {
    const { fixture, reviews } = await setUp((_p, orders) => {
      orders.getOrders.mockReturnValue(of([completedOrder('o1')]));
    });
    reviews.submit.mockRejectedValue(
      new HorecaOSApiError({ status: 409, code: 'RESOURCE_CONFLICT', detail: 'already reviewed' }),
    );

    (fixture.nativeElement.querySelectorAll('.star-btn')[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.btn--block') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('errors.generic');
    expect(fixture.nativeElement.textContent).not.toContain('RESOURCE_CONFLICT');
  });
});
