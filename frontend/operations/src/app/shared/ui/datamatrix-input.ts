import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  InjectionToken,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { GROUP_SEPARATOR, ScanDiagnostic, diagnoseScan } from './marking-code';

/** How a scan reached the console (ADR 0161): the server records it as advisory and refuses `KEYED` by default. */
export type ScanMethod = 'SCANNER' | 'CAMERA' | 'KEYED';

/**
 * What a scan hands its host. `raw` is the one place the code lives, for exactly as long as the host's
 * handler runs: the component's own buffer is already empty when the handler is called, and `diagnostic`
 * carries lengths and counts only, so it is safe to show.
 */
export interface DataMatrixScan {
  readonly raw: string;
  readonly method: ScanMethod;
  readonly diagnostic: ScanDiagnostic;
}

/** A gap above this between two characters of one scan means a person is typing, not a scanner sending (ADR 0161). */
export const MAX_SCANNER_GAP_MS = 100;

/** A half-read scan older than this is abandoned, not extended: it belongs to a key somebody pressed and walked away from. */
const STALE_PARTIAL_MS = 1000;

/** No marking code is longer than the server accepts, so a longer buffer is not a scan and is dropped. */
const MAX_BUFFER = 700;

/** Where the per-device sound preference is kept: a convenience, with a working default if storage is unavailable. */
export const SOUND_STORAGE_KEY = 'horecaos.operations.marking.sound';

/** The clock keystroke timing is read from, so a spec can drive a scanner's burst and a person's pace deterministically. */
export const SCAN_CLOCK = new InjectionToken<() => number>('SCAN_CLOCK', {
  providedIn: 'root',
  factory: () => () => performance.now(),
});

/**
 * ADR 0161, the scan half of gap map row `X.35`: one component that turns a hardware scanner's keystrokes, or
 * the browser's camera, into `{ raw, method }` -- and keeps nothing.
 *
 * **It never keeps what it reads.** The buffer is a plain field, cleared in the same tick the scan is
 * emitted and before the host's handler runs; nothing here writes storage, `console`, the URL or a signal;
 * the DOM only ever shows a count the host passes in ({@link acknowledge}). A paste or a slow typist is
 * classed `KEYED` and the server refuses it unless the platform allows it, because the printed text on a
 * package omits the cryptographic tail.
 *
 * **Two sources.** A visually hidden, focused field fed by the scanner's keystrokes (`SCANNER`, the default:
 * no permission, no decoder). The scan is rebuilt from `keydown` `event.key`, not from the field's value --
 * a text field strips control characters, and the group separator is one: `Ctrl+]` and a literal U+001D both
 * become GS. And `BarcodeDetector` with `formats: ['data_matrix']` where `getSupportedFormats()` lists it
 * (`CAMERA`), loaded by dynamic import the first time it is switched on, absent without apology on a browser
 * that cannot read a DataMatrix. No decoder library is bundled.
 *
 * **Accessibility.** An `aria-live` region announces progress and refusals; a short tone for accept and a
 * lower one for refuse, behind a per-device toggle.
 */
@Component({
  selector: 'q-datamatrix-input',
  imports: [TPipe],
  templateUrl: './datamatrix-input.html',
  styleUrl: './datamatrix-input.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QDatamatrixInput {
  private readonly i18n = inject(I18n);
  private readonly clock = inject(SCAN_CLOCK);

  /** Focus is taken and held while armed. */
  readonly armed = input(false);
  /** Units still to scan; at zero the input disarms itself. */
  readonly remaining = input(0);

  /** Once per scan, with the buffer already empty. */
  readonly scanned = output<DataMatrixScan>();
  /** Escape was pressed: the host decides whether to disarm. */
  readonly disarmRequested = output<void>();

  private readonly field = viewChild<ElementRef<HTMLInputElement>>('field');
  private readonly video = viewChild<ElementRef<HTMLVideoElement>>('video');

  protected readonly active = computed(() => this.armed() && this.remaining() > 0);
  protected readonly announcement = signal('');
  protected readonly cameraOffered = signal(false);
  protected readonly cameraOn = signal(false);
  protected readonly soundOn = signal(readSoundPreference());

  // Plain fields, not signals: nothing that renders may ever hold what a scanner has typed so far.
  private buffer = '';
  private keyed = false;
  private lastKeyAt = 0;
  private cameraScanner: { stop(): void } | null = null;
  private audio: AudioContext | null = null;

  constructor() {
    void this.detectCamera();

    // Take focus when armed; let go of the camera and the half-read scan when not.
    effect(() => {
      if (this.active()) {
        queueMicrotask(() => this.field()?.nativeElement.focus());
      } else {
        this.reset();
        this.stopCamera();
      }
    });

    inject(DestroyRef).onDestroy(() => {
      this.reset();
      this.stopCamera();
      void this.audio?.close().catch(() => undefined);
    });
  }

  // ------------------------------------------------------------------ the host's side

  /** The host accepted a scan: announce `captured of required` and play the accept tone. */
  acknowledge(captured: number, required: number): void {
    this.announcement.set(this.i18n.t('orders.marking.scanner.progress', { captured, required }));
    this.playTone('accept');
  }

  /** The host refused a scan: announce it and play the lower tone. */
  refuse(): void {
    this.announcement.set(this.i18n.t('orders.marking.scanner.refused'));
    this.playTone('refuse');
  }

  // ------------------------------------------------------------------ scanner keystrokes

  protected onKeydown(event: KeyboardEvent): void {
    if (!this.active()) {
      return;
    }
    if (event.key === 'Escape') {
      this.reset();
      this.disarmRequested.emit();
      return;
    }
    if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault();
      this.finish('SCANNER');
      return;
    }
    const character = characterOf(event);
    if (character === null) {
      return;
    }
    // Keep the character out of the field: the buffer is the only copy, and it is cleared on emit.
    event.preventDefault();

    const now = this.clock();
    if (this.buffer.length > 0 && now - this.lastKeyAt > STALE_PARTIAL_MS) {
      this.reset();
    }
    if (this.buffer.length > 0 && now - this.lastKeyAt > MAX_SCANNER_GAP_MS) {
      this.keyed = true;
    }
    this.lastKeyAt = now;
    this.buffer += character;
    if (this.buffer.length > MAX_BUFFER) {
      this.reset();
    }
  }

  protected onPaste(event: ClipboardEvent): void {
    if (!this.active()) {
      return;
    }
    event.preventDefault();
    const text = event.clipboardData?.getData('text') ?? '';
    if (text.length === 0 || text.length > MAX_BUFFER) {
      return;
    }
    this.buffer = text;
    this.keyed = true;
    this.finish('KEYED');
  }

  /** Focus lost to nothing (a click on the page background) comes back; focus moved to a control stays there. */
  protected onBlur(event: FocusEvent): void {
    if (this.active() && event.relatedTarget === null) {
      queueMicrotask(() => {
        if (this.active()) {
          this.field()?.nativeElement.focus();
        }
      });
    }
  }

  private finish(source: 'SCANNER' | 'CAMERA' | 'KEYED'): void {
    const raw = this.buffer;
    const method: ScanMethod = this.keyed ? 'KEYED' : source;
    // Cleared before the handler runs, so a host that throws, or inspects the component, finds nothing.
    this.reset();
    const element = this.field()?.nativeElement;
    if (element) {
      element.value = '';
    }
    if (raw.length === 0) {
      return;
    }
    this.scanned.emit({ raw, method, diagnostic: diagnoseScan(raw) });
  }

  private reset(): void {
    this.buffer = '';
    this.keyed = false;
    this.lastKeyAt = 0;
  }

  // ------------------------------------------------------------------ camera

  private async detectCamera(): Promise<void> {
    const Detector = (
      globalThis as {
        BarcodeDetector?: { getSupportedFormats(): Promise<readonly string[]> };
      }
    ).BarcodeDetector;
    if (!Detector || !navigator.mediaDevices?.getUserMedia) {
      return;
    }
    try {
      this.cameraOffered.set((await Detector.getSupportedFormats()).includes('data_matrix'));
    } catch {
      this.cameraOffered.set(false);
    }
  }

  protected async toggleCamera(): Promise<void> {
    if (this.cameraOn()) {
      this.stopCamera();
      return;
    }
    const video = this.video()?.nativeElement;
    if (!video || !this.active()) {
      return;
    }
    try {
      // The decoder-adjacent code is a separate chunk: nothing about the camera is paid for until here.
      const { startCameraScanner } = await import('./datamatrix-camera');
      this.cameraScanner = await startCameraScanner(video, (value) => {
        this.buffer = value;
        this.keyed = false;
        this.finish('CAMERA');
      });
      this.cameraOn.set(true);
    } catch {
      this.cameraOn.set(false);
      this.announcement.set(this.i18n.t('orders.marking.scanner.cameraUnavailable'));
    }
  }

  private stopCamera(): void {
    this.cameraScanner?.stop();
    this.cameraScanner = null;
    if (this.cameraOn()) {
      this.cameraOn.set(false);
    }
  }

  // ------------------------------------------------------------------ sound

  protected setSound(on: boolean): void {
    this.soundOn.set(on);
    try {
      localStorage.setItem(SOUND_STORAGE_KEY, on ? 'on' : 'off');
    } catch {
      // Storage unavailable: the choice holds for this page and nothing is lost that mattered.
    }
  }

  private playTone(kind: 'accept' | 'refuse'): void {
    if (!this.soundOn()) {
      return;
    }
    try {
      const Context =
        (globalThis as { AudioContext?: typeof AudioContext }).AudioContext ?? undefined;
      if (!Context) {
        return;
      }
      this.audio ??= new Context();
      const oscillator = this.audio.createOscillator();
      const gain = this.audio.createGain();
      oscillator.frequency.value = kind === 'accept' ? 880 : 220;
      gain.gain.value = 0.08;
      oscillator.connect(gain);
      gain.connect(this.audio.destination);
      oscillator.start();
      oscillator.stop(this.audio.currentTime + (kind === 'accept' ? 0.08 : 0.25));
    } catch {
      // No audio device, or a browser that wants a gesture first: the live region still announces it.
    }
  }
}

/**
 * The character a keystroke contributes, or `null` for a key that is not a character. `Ctrl+]` and a literal
 * U+001D are the group separator; other chords and bare modifier keys contribute nothing.
 */
function characterOf(event: KeyboardEvent): string | null {
  if (event.ctrlKey && !event.altKey && event.key === ']') {
    return GROUP_SEPARATOR;
  }
  if (
    event.key.length === 1 &&
    !event.metaKey &&
    (!event.ctrlKey || event.key === GROUP_SEPARATOR)
  ) {
    return event.key;
  }
  return null;
}

function readSoundPreference(): boolean {
  try {
    return localStorage.getItem(SOUND_STORAGE_KEY) !== 'off';
  } catch {
    return true;
  }
}
