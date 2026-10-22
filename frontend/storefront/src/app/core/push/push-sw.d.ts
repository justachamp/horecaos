/**
 * The service worker is a plain script in `public/`. A spec imports it for its side effect, which is to
 * register its listeners on the global scope (in jsdom, `self` is the window), and then drives them with
 * events: there is nothing to import from it, so there is nothing to describe.
 */
declare module '*/push-sw.js';
