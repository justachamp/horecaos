import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { startCameraScanner } from './datamatrix-camera';

describe('startCameraScanner', () => {
  const stopTrack = vi.fn();
  let detections: { rawValue: string }[][];

  beforeEach(() => {
    vi.useFakeTimers();
    stopTrack.mockClear();
    detections = [];
    vi.stubGlobal(
      'BarcodeDetector',
      class {
        detect(): Promise<{ rawValue: string }[]> {
          return Promise.resolve(detections.shift() ?? []);
        }
      },
    );
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [{ stop: stopTrack }] }),
      },
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  function video(): HTMLVideoElement {
    const element = document.createElement('video');
    Object.defineProperty(element, 'readyState', { value: 4 });
    element.play = vi.fn().mockResolvedValue(undefined);
    return element;
  }

  it('reports each distinct code that comes into view once and stops the stream when told to', async () => {
    const seen: string[] = [];
    const element = video();
    const scanner = await startCameraScanner(element, (value) => seen.push(value));

    detections = [[{ rawValue: 'CODE-A' }], [{ rawValue: 'CODE-A' }], [], [{ rawValue: 'CODE-B' }]];
    await vi.advanceTimersByTimeAsync(250 * 4);

    // A code that stays in view is not reported on every frame; one that leaves and returns is.
    expect(seen).toEqual(['CODE-A', 'CODE-B']);

    scanner.stop();
    expect(stopTrack).toHaveBeenCalledTimes(1);
    expect(element.srcObject).toBeNull();
    detections = [[{ rawValue: 'CODE-C' }]];
    await vi.advanceTimersByTimeAsync(1000);
    expect(seen).toEqual(['CODE-A', 'CODE-B']);
  });
});
