import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { PLATFORM_API_REQUEST, SILENT_FAILURE } from '../core/api/api-client';
import { problemDetailsInterceptor } from '../core/api/api.interceptors';
import { NotificationService } from '../services/notification.service';
import { TranslateService } from '../services/translate.service';
import { errorNotificationInterceptor } from './error-notification.interceptor';

class FakeNotificationService {
  show = vi.fn();
}
class FakeTranslateService {
  get = (key: string) => key;
}

function setUp() {
  const notifications = new FakeNotificationService();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(
        withInterceptors([errorNotificationInterceptor, problemDetailsInterceptor]),
      ),
      provideHttpClientTesting(),
      { provide: NotificationService, useValue: notifications },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  return {
    http: TestBed.inject(HttpClient),
    httpMock: TestBed.inject(HttpTestingController),
    notifications,
  };
}

const platformCall = (silent: boolean) =>
  new HttpContext().set(PLATFORM_API_REQUEST, true).set(SILENT_FAILURE, silent);

describe('errorNotificationInterceptor', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
  });

  it('tells the customer when a platform call fails', async () => {
    const { http, httpMock, notifications } = setUp();

    const failure = firstValueFrom(http.get('/api/v1/menu', { context: platformCall(false) }));
    httpMock
      .expectOne('/api/v1/menu')
      .flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Server Error' });

    await expect(failure).rejects.toBeDefined();
    expect(notifications.show).toHaveBeenCalledTimes(1);
  });

  it('says nothing when the call was marked silent, and still hands the failure to the caller', async () => {
    const { http, httpMock, notifications } = setUp();

    const failure = firstValueFrom(http.get('/api/v1/content', { context: platformCall(true) }));
    httpMock
      .expectOne('/api/v1/content')
      .flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Server Error' });

    await expect(failure).rejects.toBeDefined();
    expect(notifications.show).not.toHaveBeenCalled();
  });
});
