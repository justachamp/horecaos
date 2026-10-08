import { describe, expect, it } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { MessageKey } from '../../../core/i18n/messages.en';
import { declineReasonKey, describeWalletError } from './wallet-errors';

const translate = (key: MessageKey, values?: Readonly<Record<string, string | number>>): string =>
  values ? `${key}|${JSON.stringify(values)}` : key;

function refusal(code: string, status: number, reason: string): ApiError {
  return new ApiError(code, status, { status, code, reason }, 'corr-1');
}

describe('describeWalletError', () => {
  it('words each refusal a tenant meets in the wallet by its own reason, not the server’s English detail', () => {
    expect(
      describeWalletError(
        refusal(ApiErrorCode.UNPROCESSABLE_STATE, 422, 'CARDS_NOT_AVAILABLE'),
        translate,
      ),
    ).toBe('finance.wallet.reason.CARDS_NOT_AVAILABLE');
    expect(
      describeWalletError(
        refusal(ApiErrorCode.UNPROCESSABLE_STATE, 422, 'BANK_DETAILS_NOT_CONFIGURED'),
        translate,
      ),
    ).toBe('finance.wallet.reason.BANK_DETAILS_NOT_CONFIGURED');
    expect(
      describeWalletError(
        refusal(ApiErrorCode.RESOURCE_CONFLICT, 409, 'TOP_UP_IN_FLIGHT'),
        translate,
      ),
    ).toBe('finance.wallet.reason.TOP_UP_IN_FLIGHT');
    expect(
      describeWalletError(refusal(ApiErrorCode.UNPROCESSABLE_STATE, 422, 'WRONG_CODE'), translate),
    ).toBe('finance.wallet.reason.WRONG_CODE');
  });

  it('treats a provider that says it is not configured as the same thing as no merchant account', () => {
    expect(
      describeWalletError(
        refusal(ApiErrorCode.UNPROCESSABLE_STATE, 422, 'NOT_CONFIGURED'),
        translate,
      ),
    ).toBe('finance.wallet.reason.CARDS_NOT_AVAILABLE');
  });

  it('falls back to the generic ADR 0031 mapping for a reason this screen does not know', () => {
    const unknown = new ApiError(
      ApiErrorCode.UNPROCESSABLE_STATE,
      422,
      { status: 422, code: 'UNPROCESSABLE_STATE', reason: 'SOMETHING_NEW', detail: 'Nope' },
      'corr-2',
    );
    expect(describeWalletError(unknown, translate)).toContain('error.detailed');
  });

  it('says something went wrong, without a reference, for an error that is not an ApiError', () => {
    expect(describeWalletError(new Error('boom'), translate)).toBe('error.unknown.noReference');
  });
});

describe('declineReasonKey', () => {
  it('words only the decline code something here actually emits, and leaves the rest as the code', () => {
    expect(declineReasonKey('INSUFFICIENT_FUNDS')).toBe(
      'finance.wallet.decline.INSUFFICIENT_FUNDS',
    );
    expect(declineReasonKey('SOMETHING_ELSE')).toBeNull();
    expect(declineReasonKey(null)).toBeNull();
  });
});
