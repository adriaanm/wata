import type { ServerConfig } from '../config.js';
import type { Store } from '../store.js';
import { authenticate, jsonResponse, matrixError } from '../utils.js';

export function handleUpload(
  request: Request,
  store: Store,
  config: ServerConfig,
  _params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return Promise.resolve(auth);

  return request.arrayBuffer().then((data) => {
    const contentType =
      request.headers.get('Content-Type') ?? 'application/octet-stream';
    const url = new URL(request.url);
    const filename = url.searchParams.get('filename') ?? undefined;
    const mediaId = store.storeMedia(data, contentType, filename);
    return jsonResponse({
      content_uri: `mxc://${config.serverName}/${mediaId}`,
    });
  });
}

export async function handleDownload(
  request: Request,
  store: Store,
  _config: ServerConfig,
  params: Record<string, string>,
): Promise<Response> {
  const auth = authenticate(request, store);
  if (auth instanceof Response) return auth;

  const mediaId = params.mediaId;
  const media = store.getMedia(mediaId);
  if (!media) {
    return matrixError('M_NOT_FOUND', 'Media not found', 404);
  }

  return new Response(media.data, {
    status: 200,
    headers: {
      'Content-Type': media.contentType,
      ...(media.filename
        ? { 'Content-Disposition': `inline; filename="${media.filename}"` }
        : {}),
    },
  });
}
