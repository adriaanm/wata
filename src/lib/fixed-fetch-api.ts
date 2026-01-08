/**
 * Custom fetch implementation for matrix-js-sdk that fixes URL construction bugs
 * in React Native environments.
 *
 * The matrix-js-sdk incorrectly adds trailing /? to URLs which Conduit rejects.
 */

/**
 * Wrapper around native fetch that fixes buggy URLs from matrix-js-sdk
 */
export function createFixedFetch(): typeof fetch {
  return function fixedFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
    let url: string;

    // Extract URL string
    if (typeof input === 'string') {
      url = input;
    } else if (input instanceof Request) {
      url = input.url;
    } else if (input instanceof URL) {
      url = input.toString();
    } else {
      url = String(input);
    }

    // Fix trailing /? that matrix-js-sdk incorrectly adds
    // Examples:
    //   /login/? -> /login
    //   /versions/?&_=123 -> /versions?_=123
    //   /filter/? -> /filter
    const originalUrl = url;
    url = url.replace(/\/\?\s*$/, '').replace(/\/\?&/, '?');

    if (originalUrl !== url) {
      console.log('[FixedFetch] Fixed URL:', { original: originalUrl, fixed: url });
    }

    // Call native fetch with fixed URL
    return fetch(url, init);
  } as typeof fetch;
}
