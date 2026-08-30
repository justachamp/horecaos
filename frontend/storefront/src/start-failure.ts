/**
 * The screen for a failure that happened before the application existed.
 *
 * Angular is not running at this point, so there is no component to render and
 * no translation catalogue to read: the text is plain, in the three languages
 * this storefront serves, and is built with DOM calls rather than innerHTML so
 * that nothing from a failure message can be interpreted as markup.
 *
 * Deliberately not a blank page. A Mini App that opens to nothing is
 * indistinguishable from a dead network, and the person who has to tell those
 * apart is usually not a developer.
 */
export function showStartFailure(failure: unknown): void {
  // eslint-disable-next-line no-console
  console.error('The storefront could not start.', failure);

  const root = document.body;
  if (!root) {
    return;
  }
  root.replaceChildren();

  const panel = document.createElement('div');
  panel.setAttribute(
    'style',
    'min-height:100dvh;display:flex;flex-direction:column;align-items:center;' +
      'justify-content:center;gap:0.75rem;padding:2rem;text-align:center;' +
      'font-family:system-ui,sans-serif;color:#e9e9e9;background:#111111;',
  );

  for (const line of [
    'Ilova ochilmadi. Birozdan so‘ng qayta urinib ko‘ring.',
    'Приложение не открылось. Попробуйте позже.',
    'The app could not start. Please try again later.',
  ]) {
    const paragraph = document.createElement('p');
    paragraph.textContent = line;
    paragraph.setAttribute('style', 'margin:0;font-size:0.95rem;line-height:1.5;opacity:0.85;');
    panel.append(paragraph);
  }

  root.append(panel);
}
