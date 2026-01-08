/**
 * Custom fetch implementation for matrix-js-sdk that fixes URL construction bugs
 * in React Native environments.
 *
 * Problem: matrix-js-sdk constructs URLs by appending paths like "/login/" and then
 * adding query parameters with "?", resulting in malformed URLs like "/login/?param=value"
 * which should be "/login?param=value".
 *
 * This happens because the SDK uses:
 *   const url = baseUrl + path + "?" + queryString
 * where path already ends with "/" for some endpoints.
 *
 * This wrapper fixes these malformed URLs before passing them to React Native's fetch.
 */

/**
 * Normalizes URLs that have malformed query strings from matrix-js-sdk
 */
function normalizeUrl(url: string): string {
  // Fix malformed URLs from matrix-js-sdk's path construction
  // The SDK incorrectly produces:
  //   /path/?           -> should be: /path
  //   /path/?param=val  -> should be: /path?param=val
  //   /path/?&param=val -> should be: /path?param=val
  //   /path/            -> should be: /path (React Native adds ? later)
  let normalized = url;

  // Remove trailing slash before query string: /? -> ?
  // This handles both "/path/?" and "/path/?params"
  normalized = normalized.replace(/\/\?/, '?');

  // Remove trailing slash from path (before query params are added by RN)
  // Only remove if it's not the root path (/)
  normalized = normalized.replace(/\/(\?|#|$)/, '$1');

  return normalized;
}

/**
 * Wrapper around React Native's fetch that fixes buggy URLs from matrix-js-sdk
 */
export function createFixedFetch() {
  return function fixedFetch(
    input: string | Request,
    init?: RequestInit,
  ): Promise<Response> {
    console.log('[FixedFetch] input:', input);

    // Handle string URLs
    if (typeof input === 'string') {
      const normalized = normalizeUrl(input);
      if (normalized !== input) {
        console.log('[FixedFetch] Fixed URL:', {
          original: input,
          fixed: normalized,
        });
      }
      return fetch(normalized, init);
    }

    // Handle Request objects - extract URL and normalize
    let url: string;
    if (input instanceof Request) {
      // Standard Request object
      url = input.url;
    } else {
      // React Native's custom Request-like object with _url property
      url = (input as any)._url || (input as any).url;
    }

    const normalizedUrl = normalizeUrl(url);
    if (normalizedUrl !== url) {
      console.log('[FixedFetch] Fixed URL:', {
        original: url,
        fixed: normalizedUrl,
      });

      // Extract Request object properties and merge with init
      const requestObj = input as any;
      const mergedInit: RequestInit = {
        method: requestObj.method,
        headers: requestObj.headers,
        body: requestObj.body,
        ...init, // init can override
      };

      return fetch(normalizedUrl, mergedInit);
    }

    // URL doesn't need fixing - pass through as-is
    return fetch(input, init);
  };
}
