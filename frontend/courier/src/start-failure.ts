/**
 * The screen for a failure that happened before the application existed.
 *
 * Angular is not running at this point, so there is no component to render and no catalogue to
 * read: the text is plain, in the three languages the app serves, and is built with DOM calls
 * rather than innerHTML so nothing from a failure can be interpreted as markup. Deliberately not
 * a blank page -- an app that opens to nothing is indistinguishable from a dead network, and the
 * person who has to tell those apart is a courier standing in the street.
 */
export function showStartFailure(failure: unknown): void {
  console.error('The courier app could not start.', failure);

  const root = document.body;
  if (!root) {
    return;
  }
  root.replaceChildren();

  const panel = document.createElement('div');
  panel.setAttribute(
    'style',
    'min-height:100dvh;display:flex;flex-direction:column;align-items:center;' +
      'justify-content:center;gap:12px;padding:32px;text-align:center;' +
      'font-family:system-ui,sans-serif;color:#161616;background:#f4f4f4;',
  );

  for (const line of [
    'Приложение не открылось. Попробуйте позже.',
    'Ilova ochilmadi. Birozdan soʻng qayta urinib koʻring.',
    'The app could not start. Please try again later.',
  ]) {
    const paragraph = document.createElement('p');
    paragraph.textContent = line;
    paragraph.setAttribute('style', 'margin:0;line-height:1.5;');
    panel.append(paragraph);
  }

  root.append(panel);
}
