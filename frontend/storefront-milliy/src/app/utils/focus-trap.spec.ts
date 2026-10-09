import { trapTab } from './focus-trap';

function dialog(html: string): HTMLElement {
  const container = document.createElement('div');
  container.tabIndex = -1;
  container.innerHTML = html;
  document.body.appendChild(container);
  return container;
}

function tab(shiftKey = false): KeyboardEvent {
  return new KeyboardEvent('keydown', { key: 'Tab', shiftKey, cancelable: true, bubbles: true });
}

afterEach(() => {
  document.body.replaceChildren();
});

describe('trapTab: Tab stays inside the dialog', () => {
  it('wraps from the last control to the first', () => {
    const container = dialog('<button id="a">A</button><a id="b" href="/x">B</a>');
    container.querySelector<HTMLElement>('#b')?.focus();
    const event = tab();

    trapTab(event, container);

    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement?.id).toBe('a');
  });

  it('wraps from the first control to the last on Shift+Tab', () => {
    const container = dialog('<button id="a">A</button><a id="b" href="/x">B</a>');
    container.querySelector<HTMLElement>('#a')?.focus();
    const event = tab(true);

    trapTab(event, container);

    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement?.id).toBe('b');
  });

  it('leaves Tab alone in the middle of the dialog', () => {
    const container = dialog(
      '<button id="a">A</button><button id="b">B</button><button id="c">C</button>',
    );
    container.querySelector<HTMLElement>('#b')?.focus();
    const event = tab();

    trapTab(event, container);

    expect(event.defaultPrevented).toBe(false);
    expect(document.activeElement?.id).toBe('b');
  });

  it('brings focus back in when it has strayed outside the dialog', () => {
    const container = dialog('<button id="a">A</button><button id="b">B</button>');
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    outside.focus();
    const event = tab();

    trapTab(event, container);

    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement?.id).toBe('a');
  });

  it('skips a disabled control and a control taken out of the tab order', () => {
    const container = dialog(
      '<button id="a">A</button><button id="b" disabled>B</button><button id="c" tabindex="-1">C</button>',
    );
    container.querySelector<HTMLElement>('#a')?.focus();
    const event = tab();

    trapTab(event, container);

    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement?.id).toBe('a');
  });

  it('holds focus on the dialog itself when it has nothing to focus', () => {
    const container = dialog('<p>Only text</p>');
    const event = tab();

    trapTab(event, container);

    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(container);
  });

  it('ignores every other key', () => {
    const container = dialog('<button id="a">A</button>');
    const event = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true });

    trapTab(event, container);

    expect(event.defaultPrevented).toBe(false);
  });
});
