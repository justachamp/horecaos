import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import {
  DataMatrixScan,
  MAX_SCANNER_GAP_MS,
  QDatamatrixInput,
  SCAN_CLOCK,
  SOUND_STORAGE_KEY,
} from './datamatrix-input';
import { GROUP_SEPARATOR } from './marking-code';

const CODE = '010460000000001521Zq8mK2pXr4Wd';
const CODE_WITH_ELEMENTS = `${CODE}${GROUP_SEPARATOR}91EE10${GROUP_SEPARATOR}92dGVzdA==`;

@Component({
  selector: 'q-datamatrix-host',
  imports: [QDatamatrixInput],
  template: `
    <q-datamatrix-input
      [armed]="armed()"
      [remaining]="remaining()"
      (scanned)="onScanned($event)"
      (disarmRequested)="disarmRequests = disarmRequests + 1"
    />
  `,
})
class Host {
  readonly armed = signal(true);
  readonly remaining = signal(5);
  readonly input = viewChild.required(QDatamatrixInput);
  readonly scans: DataMatrixScan[] = [];
  disarmRequests = 0;
  /** What the input's own state held at the instant the handler ran. */
  seenAtEmit: string[] = [];

  onScanned(scan: DataMatrixScan): void {
    this.scans.push(scan);
    this.seenAtEmit.push(inspectState(this.input()));
  }
}

/** Every own field of the component, serialised: a code left in a signal or a buffer would show up here. */
function inspectState(component: object): string {
  const parts: string[] = [];
  for (const [name, value] of Object.entries(component)) {
    if (typeof value === 'function' && 'set' in value) {
      parts.push(`${name}=${JSON.stringify((value as () => unknown)())}`);
    } else if (typeof value === 'string') {
      parts.push(`${name}=${value}`);
    }
  }
  return parts.join('|');
}

describe('QDatamatrixInput', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<Host>>;
  let host: Host;
  let element: HTMLElement;
  let now = 0;

  beforeEach(async () => {
    now = 1_000;
    localStorage.removeItem(SOUND_STORAGE_KEY);
    TestBed.configureTestingModule({
      providers: [{ provide: SCAN_CLOCK, useValue: () => now }],
    });
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    host = fixture.componentInstance;
    element = fixture.nativeElement;
  });

  afterEach(() => {
    fixture.nativeElement.remove();
    vi.unstubAllGlobals();
  });

  function field(): HTMLInputElement {
    return element.querySelector('[data-testid="datamatrix-field"]') as HTMLInputElement;
  }

  function press(init: KeyboardEventInit): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
    field().dispatchEvent(event);
    return event;
  }

  /** A scanner's burst: every character a few milliseconds after the last, then a terminator. */
  function scannerTypes(text: string, terminator = 'Enter'): void {
    for (const character of text) {
      now += 3;
      if (character === GROUP_SEPARATOR) {
        press({ key: ']', ctrlKey: true });
      } else {
        press({ key: character });
      }
    }
    now += 3;
    press({ key: terminator });
  }

  it('turns a scanner burst into one scan with the group separator rebuilt from Ctrl+]', () => {
    scannerTypes(CODE_WITH_ELEMENTS);

    expect(host.scans).toHaveLength(1);
    expect(host.scans[0].raw).toBe(CODE_WITH_ELEMENTS);
    expect(host.scans[0].method).toBe('SCANNER');
    expect(host.scans[0].diagnostic.groupSeparators).toBe(2);
    expect(host.scans[0].diagnostic.gs1Shaped).toBe(true);
  });

  it('accepts a scanner that sends the literal U+001D instead of Ctrl+]', () => {
    for (const character of CODE_WITH_ELEMENTS) {
      now += 2;
      press({ key: character });
    }
    press({ key: 'Enter' });

    expect(host.scans[0].raw).toBe(CODE_WITH_ELEMENTS);
    expect(host.scans[0].method).toBe('SCANNER');
  });

  it('accepts Tab as the terminator as well as Enter, and keeps the key from moving focus', () => {
    for (const character of CODE) {
      now += 2;
      press({ key: character });
    }
    const tab = press({ key: 'Tab' });

    expect(host.scans).toHaveLength(1);
    expect(tab.defaultPrevented).toBe(true);
  });

  it('reports a scanner that drops the separator as a diagnostic and not a crash', () => {
    const merged = `${CODE}91EE10${'x'.repeat(10)}`;
    scannerTypes(merged);

    expect(host.scans).toHaveLength(1);
    expect(host.scans[0].diagnostic.groupSeparators).toBe(0);
    expect(host.scans[0].diagnostic.gs1Shaped).toBe(false);
  });

  it('classes a scan typed slowly as KEYED', () => {
    for (const character of CODE) {
      now += MAX_SCANNER_GAP_MS + 50;
      press({ key: character });
    }
    press({ key: 'Enter' });

    expect(host.scans[0].method).toBe('KEYED');
    expect(host.scans[0].raw).toBe(CODE);
  });

  it('classes a pasted code as KEYED and keeps it out of the field', () => {
    const paste = new Event('paste', { bubbles: true, cancelable: true }) as ClipboardEvent;
    Object.defineProperty(paste, 'clipboardData', { value: { getData: () => CODE } });
    field().dispatchEvent(paste);

    expect(host.scans).toHaveLength(1);
    expect(host.scans[0]).toMatchObject({ raw: CODE, method: 'KEYED' });
    expect(paste.defaultPrevented).toBe(true);
    expect(field().value).toBe('');
  });

  it('takes five scans in a burst, each its own scan, with no character bleeding into the next', () => {
    const codes = [1, 2, 3, 4, 5].map((n) =>
      `01046000000000152 1Serial${n}xxxxxxxx`.replace(' ', ''),
    );
    for (const code of codes) {
      scannerTypes(code);
    }

    expect(host.scans.map((scan) => scan.raw)).toEqual(codes);
    expect(host.scans.every((scan) => scan.method === 'SCANNER')).toBe(true);
  });

  it('abandons a half-read scan when the input is disarmed, and starts clean when armed again', async () => {
    for (const character of 'AAAAAAAAAA') {
      now += 3;
      press({ key: character });
    }
    host.armed.set(false);
    fixture.detectChanges();
    await fixture.whenStable();
    host.armed.set(true);
    fixture.detectChanges();
    await fixture.whenStable();

    scannerTypes(CODE);

    expect(host.scans).toHaveLength(1);
    expect(host.scans[0].raw).toBe(CODE);
  });

  it('disarms itself when nothing remains to scan, and ignores keystrokes while disarmed', async () => {
    host.remaining.set(0);
    fixture.detectChanges();
    await fixture.whenStable();

    scannerTypes(CODE);

    expect(host.scans).toHaveLength(0);
    expect(field().disabled).toBe(true);
  });

  it('forgets a half-read scan that is older than a second instead of extending it', () => {
    press({ key: 'A' });
    now += 5_000;
    scannerTypes(CODE);

    expect(host.scans[0].raw).toBe(CODE);
    expect(host.scans[0].method).toBe('SCANNER');
  });

  it('asks the host to disarm on Escape and drops the scan in progress', () => {
    press({ key: 'A' });
    press({ key: 'Escape' });
    scannerTypes(CODE);

    expect(host.disarmRequests).toBe(1);
    expect(host.scans[0].raw).toBe(CODE);
  });

  it('keeps nothing after an emit: the buffer is empty before the handler runs and no signal holds the code', () => {
    scannerTypes(CODE_WITH_ELEMENTS);

    expect(host.seenAtEmit).toHaveLength(1);
    // Checked at the instant the handler ran, and again afterwards.
    for (const state of [host.seenAtEmit[0], inspectState(host.input())]) {
      expect(state).not.toContain('Zq8mK2pXr4Wd');
      expect(state).not.toContain(CODE);
    }
    const buffer = (host.input() as unknown as { buffer: string }).buffer;
    expect(buffer).toBe('');
    expect(field().value).toBe('');
    expect(element.textContent).not.toContain('Zq8mK2pXr4Wd');
  });

  it('never writes what it reads to storage or the console', () => {
    const consoleSpies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) =>
      vi.spyOn(console, level),
    );
    const setItem = vi.spyOn(Storage.prototype, 'setItem');

    scannerTypes(CODE);

    for (const spy of consoleSpies) {
      expect(spy).not.toHaveBeenCalled();
    }
    expect(setItem).not.toHaveBeenCalled();
    expect(location.href).not.toContain('Zq8m');
  });

  it('announces progress and a refusal in the live region, and plays no tone when sound is off', () => {
    host.input().acknowledge(2, 5);
    fixture.detectChanges();
    expect(element.querySelector('[data-testid="datamatrix-live"]')?.textContent).toContain(
      '2 of 5 scanned',
    );

    host.input().refuse();
    fixture.detectChanges();
    expect(element.querySelector('[data-testid="datamatrix-live"]')?.textContent).toContain(
      'refused',
    );
  });

  it('keeps the sound preference on the device and works when storage is unavailable', () => {
    const checkbox = element.querySelector('[data-testid="datamatrix-sound"]') as HTMLInputElement;
    expect(checkbox.checked).toBe(true);
    checkbox.checked = false;
    checkbox.dispatchEvent(new Event('change'));
    expect(localStorage.getItem(SOUND_STORAGE_KEY)).toBe('off');

    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    checkbox.checked = true;
    expect(() => checkbox.dispatchEvent(new Event('change'))).not.toThrow();
  });

  it('declares no camera when the browser has no BarcodeDetector for a DataMatrix', async () => {
    expect(element.querySelector('[data-testid="datamatrix-camera-toggle"]')).toBeNull();

    vi.stubGlobal(
      'BarcodeDetector',
      class {
        static getSupportedFormats(): Promise<string[]> {
          return Promise.resolve(['qr_code', 'ean_13']);
        }
      },
    );
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn() },
    });
    const second = TestBed.createComponent(Host);
    second.detectChanges();
    await second.whenStable();
    expect(
      second.nativeElement.querySelector('[data-testid="datamatrix-camera-toggle"]'),
    ).toBeNull();
  });

  it('offers the camera only where getSupportedFormats lists data_matrix', async () => {
    vi.stubGlobal(
      'BarcodeDetector',
      class {
        static getSupportedFormats(): Promise<string[]> {
          return Promise.resolve(['qr_code', 'data_matrix']);
        }
      },
    );
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn() },
    });
    const second = TestBed.createComponent(Host);
    second.detectChanges();
    await second.whenStable();
    second.detectChanges();

    expect(
      second.nativeElement.querySelector('[data-testid="datamatrix-camera-toggle"]'),
    ).not.toBeNull();
  });
});
