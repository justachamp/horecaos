/**
 * The camera half of the DataMatrix input (ADR 0161), kept in its own module so the browser never fetches it
 * unless a person turns the camera on, and so the console's initial bundle pays nothing for it.
 *
 * It bundles no decoder library: the only decoder is the browser's own `BarcodeDetector`, and the camera is
 * offered only when `getSupportedFormats()` lists `data_matrix` -- a check `q-datamatrix-input` makes itself,
 * with no import from here, precisely so this module stays out of the chunk that is always loaded. A browser
 * that cannot read a DataMatrix gets no camera button at all.
 *
 * Nothing here keeps what it reads: a detected value is handed to the caller and forgotten, the stream is
 * stopped on `stop()`, and no frame, value or timestamp is stored, logged or sent anywhere.
 */

/** The slice of the platform `BarcodeDetector` API this module uses. */
interface BarcodeDetectorLike {
  detect(source: HTMLVideoElement): Promise<readonly { readonly rawValue: string }[]>;
}

interface BarcodeDetectorConstructor {
  new (options: { formats: string[] }): BarcodeDetectorLike;
  getSupportedFormats(): Promise<readonly string[]>;
}

function detectorConstructor(): BarcodeDetectorConstructor | null {
  const candidate = (globalThis as { BarcodeDetector?: BarcodeDetectorConstructor })
    .BarcodeDetector;
  return typeof candidate === 'function' ? candidate : null;
}

/** How often a frame is looked at, and how long a detected code is not reported again while it stays in view. */
const FRAME_INTERVAL_MS = 250;
const SAME_CODE_QUIET_MS = 2000;

export interface CameraScanner {
  stop(): void;
}

/**
 * Starts reading the rear camera into `video` and calls `onCode` for each distinct code that comes into
 * view. A code that stays in view is reported once, not on every frame.
 */
export async function startCameraScanner(
  video: HTMLVideoElement,
  onCode: (rawValue: string) => void,
): Promise<CameraScanner> {
  const Detector = detectorConstructor();
  if (!Detector) {
    throw new Error('BarcodeDetector is not available');
  }
  const detector = new Detector({ formats: ['data_matrix'] });
  const stream = await navigator.mediaDevices.getUserMedia({
    video: { facingMode: 'environment' },
    audio: false,
  });
  video.srcObject = stream;
  await video.play();

  let stopped = false;
  let quietUntil = 0;
  let lastValue = '';
  const timer = setInterval(() => {
    if (stopped || video.readyState < 2) {
      return;
    }
    void detector
      .detect(video)
      .then((found) => {
        const first = found[0];
        if (stopped || !first) {
          lastValue = '';
          return;
        }
        const now = Date.now();
        if (first.rawValue === lastValue && now < quietUntil) {
          return;
        }
        lastValue = first.rawValue;
        quietUntil = now + SAME_CODE_QUIET_MS;
        onCode(first.rawValue);
      })
      .catch(() => {
        // A frame that could not be decoded is not an event: the next one will be looked at.
      });
  }, FRAME_INTERVAL_MS);

  return {
    stop(): void {
      stopped = true;
      clearInterval(timer);
      stream.getTracks().forEach((track) => track.stop());
      video.srcObject = null;
    },
  };
}
