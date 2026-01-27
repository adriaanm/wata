import type { ServerConfig } from './config.js';
import { createRouter } from './server.js';
import { Store } from './store.js';
import { startNodeServer } from './transport/node.js';

const config: ServerConfig = {
  serverName: 'localhost',
  port: 8008,
  users: [
    { localpart: 'alice', password: 'testpass123', displayName: 'Alice' },
    { localpart: 'bob', password: 'testpass123', displayName: 'Bob' },
  ],
};

const store = new Store(config);
const router = createRouter(store, config);
startNodeServer(router, config.port);
