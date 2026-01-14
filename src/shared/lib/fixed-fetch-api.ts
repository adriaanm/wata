/**
 * Custom fetch implementation for matrix-js-sdk that fixes URL construction bugs
 * in React Native environments.
 *
 * ## URL Normalization
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

  // Conduit requires trailing slash on pushrules endpoint
  // SDK sends: /_matrix/client/v3/pushrules
  // Conduit expects: /_matrix/client/v3/pushrules/
  if (normalized.includes('/_matrix/client/v3/pushrules') && !normalized.includes('pushrules/')) {
    normalized = normalized.replace('/pushrules', '/pushrules/');
  }

  return normalized;
}

/**
 * Wrapper around React Native's fetch that fixes buggy URLs from matrix-js-sdk.
 */
export function createFixedFetch() {
  return function fixedFetch(
    input: string | Request | URL,
    init?: RequestInit,
  ): Promise<Response> {
    // Extract URL string from any input type
    let urlString: string;
    if (typeof input === 'string') {
      urlString = input;
    } else if (input instanceof URL) {
      urlString = input.href;
    } else if (input instanceof Request) {
      urlString = input.url;
    } else {
      // React Native's custom Request-like object with _url property
      urlString = (input as any)._url || (input as any).url;
    }

    // Normalize URL to fix matrix-js-sdk's malformed URL construction
    const normalized = normalizeUrl(urlString);

    // For Request objects, we need to reconstruct with the fixed URL
    if (typeof input !== 'string' && !(input instanceof URL)) {
      const requestObj = input as any;
      const mergedInit: RequestInit = {
        method: requestObj.method,
        headers: requestObj.headers,
        body: requestObj.body,
        ...init,
      };
      return fetch(normalized, mergedInit);
    }

    return fetch(normalized, init);
  };
}
