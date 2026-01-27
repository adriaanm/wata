import http from 'node:http';

export function startNodeServer(
  handler: (request: Request) => Promise<Response>,
  port: number,
): http.Server {
  const server = http.createServer(async (req, res) => {
    try {
      // Collect body chunks
      const chunks: Buffer[] = [];
      for await (const chunk of req) {
        chunks.push(chunk as Buffer);
      }
      const body = Buffer.concat(chunks);

      // Build the Request
      const url = `http://${req.headers.host ?? 'localhost'}${req.url ?? '/'}`;
      const headers = new Headers();
      for (const [key, value] of Object.entries(req.headers)) {
        if (value !== undefined) {
          if (Array.isArray(value)) {
            for (const v of value) headers.append(key, v);
          } else {
            headers.set(key, value);
          }
        }
      }

      const init: RequestInit = {
        method: req.method,
        headers,
      };

      // Only attach body for methods that can have one
      if (req.method !== 'GET' && req.method !== 'HEAD' && body.length > 0) {
        init.body = body;
        // @ts-expect-error Node fetch requires duplex for streaming
        init.duplex = 'half';
      }

      const request = new Request(url, init);
      const response = await handler(request);

      // Write response
      res.writeHead(response.status, Object.fromEntries(response.headers));
      if (response.body) {
        const arrayBuffer = await response.arrayBuffer();
        res.end(Buffer.from(arrayBuffer));
      } else {
        res.end();
      }
    } catch (err) {
      console.error('Request handler error:', err);
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ errcode: 'M_UNKNOWN', error: 'Internal server error' }));
    }
  });

  server.listen(port, () => {
    console.log(`Wata server listening on port ${port}`);
  });

  return server;
}
