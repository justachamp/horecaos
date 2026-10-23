/**
 * Where to go after signing in, as a value that came back from an address bar.
 *
 * The return path is stored before the browser leaves and read when it comes back, so it is
 * input from outside: it is only ever accepted if it is a path inside this app. `//host` and
 * `/\host` are protocol-relative URLs a browser follows to another origin, a scheme is an
 * absolute one, and `/auth/...` would send a person who just signed in straight back into the
 * sign-in machinery.
 */
export function sanitizeReturnTo(candidate: string | null | undefined): string {
  if (typeof candidate !== 'string' || candidate === '') {
    return '/';
  }
  if (!candidate.startsWith('/') || candidate.startsWith('//') || candidate.startsWith('/\\')) {
    return '/';
  }
  if (hasControlCharacter(candidate) || candidate.includes('\\')) {
    return '/';
  }
  if (candidate === '/auth' || candidate.startsWith('/auth/') || candidate.startsWith('/auth?')) {
    return '/';
  }
  return candidate;
}

/** A newline or any other control character in an address is an injection attempt, not a path. */
function hasControlCharacter(text: string): boolean {
  for (const character of text) {
    const code = character.codePointAt(0) ?? 0;
    if (code < 0x20 || code === 0x7f) {
      return true;
    }
  }
  return false;
}
