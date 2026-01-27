#!/bin/bash
# Integration test runner that starts wata-server automatically

set -e

# Kill any existing server on port 8008
lsof -ti:8008 | xargs kill -9 2>/dev/null || true

# Start wata-server in background
echo "Starting wata-server..."
WATA_SERVER_DEBUG=${WATA_SERVER_DEBUG:-0} pnpm exec tsx src/server/index.ts > /tmp/wata-server.log 2>&1 &
SERVER_PID=$!

# Wait for server to be ready
echo "Waiting for server to start..."
for i in {1..30}; do
  if curl -s http://localhost:8008/_matrix/client/versions > /dev/null 2>&1; then
    echo "Server is ready!"
    break
  fi
  if [ $i -eq 30 ]; then
    echo "Server failed to start"
    cat /tmp/wata-server.log
    exit 1
  fi
  sleep 0.2
done

# Run tests
echo "Running integration tests..."
pnpm test:integration

# Clean up
echo "Stopping server..."
kill $SERVER_PID 2>/dev/null || true
wait $SERVER_PID 2>/dev/null || true
