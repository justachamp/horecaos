import { TestBed } from '@angular/core/testing';

import { ApiError } from '../api/problem-details';
import { I18n } from './i18n';
import { errorMessage } from './error-message';

describe('errorMessage', () => {
  let i18n: I18n;

  beforeEach(async () => {
    i18n = TestBed.inject(I18n);
    await i18n.require(['shift']);
    await i18n.use('en');
  });

  it('says nothing for no error', () => {
    expect(errorMessage(i18n, null, 'shift.error')).toBeNull();
  });

  it('gives a request that never arrived the connection’s sentence, whatever the screen', () => {
    const offline = new ApiError({ status: 0, code: 'NETWORK', detail: 'x' });

    expect(errorMessage(i18n, offline, 'shift.error')).toContain('Cannot reach the server');
  });

  it('prefers the platform’s reason over its code, under the screen’s own prefix', () => {
    const refused = new ApiError({
      status: 409,
      code: 'RESOURCE_CONFLICT',
      reason: 'REGISTRATION_LAPSED',
      detail: 'developer words',
    });

    expect(errorMessage(i18n, refused, 'duty.error')).toContain('registration has lapsed');
  });

  it('falls back to the code when the reason has no sentence here', () => {
    const conflict = new ApiError({
      status: 409,
      code: 'RESOURCE_CONFLICT',
      reason: 'SOMETHING_NEW',
      detail: 'x',
    });

    expect(errorMessage(i18n, conflict, 'shift.error')).toContain('already open');
  });

  it('never shows the platform’s detail: an unknown refusal is the generic sentence', () => {
    const unknown = new ApiError({
      status: 500,
      code: 'INTERNAL_ERROR',
      detail: 'NullPointer at X',
    });

    const text = errorMessage(i18n, unknown, 'shift.error');

    expect(text).toContain('Something went wrong');
    expect(text).not.toContain('NullPointer');
  });

  it('says the account may not do it for a 403', () => {
    const forbidden = new ApiError({ status: 403, code: 'INSUFFICIENT_CAPABILITY', detail: 'x' });

    expect(errorMessage(i18n, forbidden, 'shift.error')).toContain('not allowed');
  });
});
