import pkg from '../../../package.json';
import { APP_VERSION } from './version';

describe('APP_VERSION', () => {
  it('is the version package.json says, so a courier reading it out names the build they have', () => {
    expect(APP_VERSION).toBe(pkg.version);
  });
});
