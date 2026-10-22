import { afterEach, describe, expect, it, vi } from 'vitest';

import { submitPaymentForm } from './payment-form';

describe('submitPaymentForm', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    document.body.querySelectorAll('form').forEach((form) => form.remove());
  });

  const FORM = {
    action: 'https://checkout.paycom.uz/',
    fields: {
      merchant: '587f72c72cac0d162c722ae2',
      amount: '1500000',
      'account[order_id]': 'mtid-1',
      lang: 'ru',
      detail: 'eyJyZWNlaXB0X3R5cGUiOjB9',
    },
  };

  it('POSTs a hidden form to the provider carrying every field, the receipt detail among them', () => {
    // jsdom does not implement a navigating submit; the seam is the one call that would leave the page.
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {});

    const submitted = submitPaymentForm(FORM);

    expect(submitted).toBe(true);
    expect(submit).toHaveBeenCalledTimes(1);
    const form = document.body.querySelector('form') as HTMLFormElement;
    expect(form.method).toBe('post');
    expect(form.action).toBe('https://checkout.paycom.uz/');
    expect(form.style.display).toBe('none');
    const fields = Array.from(form.querySelectorAll('input')).map((input) => [
      input.name,
      input.type,
      input.value,
    ]);
    expect(fields).toEqual([
      ['merchant', 'hidden', '587f72c72cac0d162c722ae2'],
      ['amount', 'hidden', '1500000'],
      ['account[order_id]', 'hidden', 'mtid-1'],
      ['lang', 'hidden', 'ru'],
      ['detail', 'hidden', 'eyJyZWNlaXB0X3R5cGUiOjB9'],
    ]);
  });

  it('refuses an action that is not https, and submits nothing', () => {
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {});

    for (const action of ['http://checkout.paycom.uz/', 'javascript:alert(1)', 'not a url', '']) {
      expect(submitPaymentForm({ ...FORM, action })).toBe(false);
    }

    expect(submit).not.toHaveBeenCalled();
    expect(document.body.querySelector('form')).toBeNull();
  });
});
