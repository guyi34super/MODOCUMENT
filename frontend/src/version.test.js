import { describe, expect, it } from 'vitest';
import pkg from '../package.json';
import { APP_VERSION } from './version';

describe('APP_VERSION', () => {
  it('is the package.json version injected by the Vite config', () => {
    expect(APP_VERSION).toBe(pkg.version);
    expect(APP_VERSION).toMatch(/^\d+\.\d+\.\d+$/);
  });
});
