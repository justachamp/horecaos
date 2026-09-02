import { describe, expect, it } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { MessageKey } from '../../core/i18n/messages.en';
import { messagesEn } from '../../core/i18n/messages.en';
import { describeReissueRefusal } from './finance-errors';

function translate(key: MessageKey, values?: Readonly<Record<string, string | number>>): string {
  const template = messagesEn[key];
  if (!values) {
    return template;
  }
  return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
    Object.hasOwn(values, name) ? String(values[name]) : whole,
  );
}

describe('describeReissueRefusal', () => {
  it('describes a known reason by its own sentence, not the generic conflict message', () => {
    const error = new ApiError(
      ApiErrorCode.RESOURCE_CONFLICT,
      409,
      { status: 409, reason: 'ALREADY_PAID' },
      null,
    );

    expect(describeReissueRefusal(error, translate)).toBe('This order is already paid.');
  });

  it('falls back to the generic ADR 0031 mapping for a reason it does not recognise', () => {
    const error = new ApiError(
      ApiErrorCode.RESOURCE_CONFLICT,
      409,
      { status: 409, reason: 'SOME_FUTURE_REASON' },
      'corr-1',
    );

    expect(describeReissueRefusal(error, translate)).toBe(
      'Something went wrong. Reference corr-1.',
    );
  });

  it('maps a validation failure to a phone-specific sentence', () => {
    const error = new ApiError(ApiErrorCode.VALIDATION_FAILED, 400, { status: 400 }, null);

    expect(describeReissueRefusal(error, translate)).toBe('That phone number is not valid.');
  });

  it('maps ORDER_NOT_FOUND through the shared RESOURCE_NOT_FOUND sentence', () => {
    const error = new ApiError(
      ApiErrorCode.RESOURCE_NOT_FOUND,
      404,
      { status: 404, reason: 'ORDER_NOT_FOUND' },
      null,
    );

    expect(describeReissueRefusal(error, translate)).toBe('That no longer exists.');
  });
});
