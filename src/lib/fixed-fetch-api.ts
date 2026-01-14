/**
 * Custom fetch implementation for matrix-js-sdk that fixes URL construction bugs
 * in React Native environments and works around Conduit server limitations.
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
 *
 * ## Conduit Push Rules Workaround
 *
 * IMPORTANT LIMITATION: This fetch wrapper intercepts requests to /_matrix/client/v3/pushrules/
 * and returns an empty push rules response instead of letting Conduit return 404.
 *
 * Why this is needed:
 * - Conduit (lightweight Matrix server) does not implement the push rules endpoint
 * - The Matrix SDK fetches push rules during initial sync and treats 404 as a fatal error
 * - This causes the SDK to enter ERROR state and never reach PREPARED/SYNCING
 * - By returning empty push rules, the SDK can complete sync successfully
 *
 * Trade-offs:
 * - Push notifications will NOT work (rules are always empty)
 * - Client-side notification settings have no effect
 * - This is acceptable for the wata app which targets PTT devices without push support
 *
 * To remove this workaround:
 * - Switch to a Matrix server that supports push rules (Synapse, Dendrite)
 * - Or wait for Conduit to implement the push rules endpoint
 * - Then remove the shouldInterceptPushRules() check and mockPushRulesResponse()
 *
 * See: TEST_STRATEGY.md for more context on this decision
 */

/**
 * Check if a URL is a push rules request that should be intercepted.
 * Conduit returns 404 for this endpoint, causing SDK sync failures.
 */
function shouldInterceptPushRules(url: string): boolean {
  return url.includes('/_matrix/client/v3/pushrules');
}

/**
 * Create a mock Response for push rules requests.
 * Returns empty push rules in the format expected by matrix-js-sdk.
 *
 * Format per Matrix spec: https://spec.matrix.org/latest/client-server-api/#get_matrixclientv3pushrules
 */
function mockPushRulesResponse(): Response {
  const emptyPushRules = {
    global: {
      override: [],
      content: [],
      room: [],
      sender: [],
      underride: [],
    },
  };

  return new Response(JSON.stringify(emptyPushRules), {
    status: 200,
    statusText: 'OK',
    headers: {
      'Content-Type': 'application/json',
    },
  });
}

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
 * and intercepts problematic endpoints for Conduit compatibility.
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

    // CONDUIT WORKAROUND: Intercept push rules requests
    // See file header for full explanation of why this is needed
    if (shouldInterceptPushRules(urlString)) {
      console.log(
        '[FixedFetch] Intercepting pushrules request (Conduit workaround):',
        urlString,
      );
      return Promise.resolve(mockPushRulesResponse());
    }

    // Normalize URL to fix matrix-js-sdk's malformed URL construction
    const normalized = normalizeUrl(urlString);
    if (normalized !== urlString) {
      console.log('[FixedFetch] Fixed URL:', {
        original: urlString,
        fixed: normalized,
      });
    }

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
