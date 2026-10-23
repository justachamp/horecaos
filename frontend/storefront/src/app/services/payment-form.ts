/**
 * A provider checkout the browser submits instead of following.
 *
 * Payme's checkout form is the one surface documented to carry the receipt detail of a fiscalised cashbox (a
 * hidden `detail` field, base64 JSON of the lines), and a link or a QR cannot carry it. So the platform hands
 * the page a form (`action` and hidden `fields`) and the page POSTs it, exactly as the provider's own button
 * would. The fields are the customer's own checkout: they are not stored, logged or sent anywhere else.
 */
export interface PaymentForm {
  readonly action: string;
  readonly fields: Readonly<Record<string, string>>;
}

/**
 * Submits the form to the provider.
 *
 * The action comes from the platform's approved provider environment, never from the customer, and is still
 * required to be an `https` URL here: a form is the one place a `javascript:` action would run, and a checkout
 * that is not https is not one to send a customer's card to.
 *
 * @returns false when the form was refused and nothing was submitted
 */
export function submitPaymentForm(form: PaymentForm, doc: Document = document): boolean {
  let target: URL;
  try {
    target = new URL(form.action);
  } catch {
    return false;
  }
  if (target.protocol !== 'https:') {
    return false;
  }

  const element = doc.createElement('form');
  element.method = 'POST';
  element.action = target.toString();
  element.style.display = 'none';
  for (const [name, value] of Object.entries(form.fields)) {
    const input = doc.createElement('input');
    input.type = 'hidden';
    input.name = name;
    input.value = value;
    element.appendChild(input);
  }
  doc.body.appendChild(element);
  element.submit();
  return true;
}
