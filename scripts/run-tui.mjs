#!/usr/bin/env node

// ESM wrapper to run the TUI via bootstrap
// Bootstrap ensures LogService is installed before any other modules load

import('../src/tui/bootstrap.ts');
