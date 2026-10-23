import { applicationServerKeyBytes, browserPush } from './push-browser';

describe('applicationServerKeyBytes', () => {
  it('reads a base64url key as the bytes PushManager.subscribe wants', () => {
    // 0xfb 0xff 0xbf is "-_-_" in base64url and "+/+/" in base64: the two alphabets differ here.
    expect(Array.from(applicationServerKeyBytes('-_-_'))).toEqual([0xfb, 0xff, 0xbf]);
  });

  it('accepts a key without padding, as the platform serves it', () => {
    // 65 octets, the length of an uncompressed P-256 point, is 87 characters unpadded.
    const key = btoa(String.fromCharCode(...new Array(65).fill(4)))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');

    const bytes = applicationServerKeyBytes(key);

    expect(key).toHaveLength(87);
    expect(bytes).toHaveLength(65);
    expect(bytes[0]).toBe(4);
  });
});

describe('browserPush in a browser with no service worker', () => {
  it('says it is unsupported instead of throwing, so a webview renders nothing', () => {
    const browser = browserPush();

    expect(browser.supported).toBe(false);
    expect(browser.permission()).toBe('unsupported');
  });
});
