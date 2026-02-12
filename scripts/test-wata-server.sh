#!/bin/bash
# Quick iteration workflow for wata-server development
#
# Usage:
#   ./scripts/test-wata-server.sh [test-filter]
#
# Examples:
#   ./scripts/test-wata-server.sh              # Run all integration tests
#   ./scripts/test-wata-server.sh matrix       # Run only matrix.test.ts
#   ./scripts/test-wata-server.sh voice-message  # Run only voice-message tests

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

# Parse test filter
TEST_FILTER="$1"

echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║${NC}           ${YELLOW}Wata Server Development Workflow${NC}           ${GREEN}║${NC}"
echo -e "${GREEN}╠══════════════════════════════════════════════════════════╣${NC}"
echo -e "${GREEN}║${NC}  Quick iteration: server → tests → fix → repeat         ${GREEN}║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════════╝${NC}"
echo ""

# Function to check if wata-server is running
check_wata_server() {
  curl -sf http://localhost:8008/_matrix/client/versions > /dev/null 2>&1
  return $?
}

# Function to check if Conduit is running
check_conduit() {
  docker -f wata-matrix ps 2>/dev/null | grep -q "Up"
  return $?
}

# Function to stop Conduit
stop_conduit() {
  echo -e "${YELLOW}⏹ Stopping Conduit...${NC}"
  cd test/docker
  docker-compose down 2>/dev/null || true
  cd "$PROJECT_ROOT"
  echo -e "${GREEN}✓ Conduit stopped${NC}"
}

# Function to start wata-server
start_wata_server() {
  # Check if already running
  if check_wata_server; then
    echo -e "${YELLOW}⚠ wata-server already running on port 8008${NC}"
    return 0
  fi

  echo -e "${YELLOW}▶ Starting wata-server...${NC}"
  pnpm wata-server > /tmp/wata-server.log 2>&1 &
  SERVER_PID=$!

  # Wait for server to be ready
  echo -n "${YELLOW}Waiting for wata-server..."
  for i in {1..30}; do
    if check_wata_server; then
      echo -e " ${GREEN}ready!${NC}"
      echo ""
      echo -e "${GREEN}Server running with PID: $SERVER_PID${NC}"
      echo -e "${GREEN}Logs: tail -f /tmp/wata-server.log${NC}"
      echo ""
      return 0
    fi
    echo -n "."
    sleep 0.2
  done

  echo -e " ${RED}failed!${NC}"
  echo -e "${RED}Check logs: cat /tmp/wata-server.log${NC}"
  return 1
}

# Function to run integration tests
run_tests() {
  local filter=""
  if [ -n "$TEST_FILTER" ]; then
    filter="--testNamePattern=\"$TEST_FILTER\""
    echo -e "${YELLOW}🧪 Running tests matching: ${TEST_FILTER}${NC}"
  else
    echo -e "${YELLOW}🧪 Running all integration tests${NC}"
  fi

  echo ""
  pnpm test:integration $filter

  local exit_code=$?

  echo ""
  if [ $exit_code -eq 0 ]; then
    echo -e "${GREEN}✅ All tests passed!${NC}"
  else
    echo -e "${RED}❌ Tests failed with exit code: $exit_code${NC}"
  fi

  return $exit_code
}

# Function to show server logs
show_logs() {
  if [ -f /tmp/wata-server.log ]; then
    echo -e "${YELLOW}📋 Recent server logs:${NC}"
    echo ""
    tail -n 30 /tmp/wata-server.log
    echo ""
    echo -e "${YELLOW}Follow live logs with: tail -f /tmp/wata-server.log${NC}"
  else
    echo -e "${RED}No server logs found at /tmp/wata-server.log${NC}"
  fi
}

# Function for interactive loop
interactive_loop() {
  echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
  echo -e "${GREEN}║${NC}                    ${YELLOW}Interactive Mode${NC}                    ${GREEN}║${NC}"
  echo -e "${GREEN}╠══════════════════════════════════════════════════════════╣${NC}"
  echo -e "${GREEN}║${NC} Commands:                                             ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}   ${YELLOW}t${NC} - Run tests              ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}   ${YELLOW}l${NC} - Show recent server logs  ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}   ${YELLOW}r${NC} - Restart wata-server      ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}   ${YELLOW}s${NC} - Stop wata-server         ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}   ${YELLOW}q${NC} - Quit                    ${GREEN}║${NC}"
  echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
  echo ""

  while true; do
    echo -n -e "${YELLOW}> ${NC}"
    read -n 1 cmd

    case $cmd in
      t)
        run_tests
        ;;
      l)
        show_logs
        ;;
      r)
        echo -e "${YELLOW}Restarting wata-server...${NC}"
        pkill -f "tsx src/server/index.ts" 2>/dev/null || true
        sleep 1
        start_wata_server || continue
        ;;
      s)
        echo -e "${YELLOW}Stopping wata-server...${NC}"
        pkill -f "tsx src/server/index.ts" 2>/dev/null
        echo -e "${GREEN}✓ Server stopped${NC}"
        echo ""
        break
        ;;
      q)
        echo -e "${YELLOW}Stopping wata-server...${NC}"
        pkill -f "tsx src/server/index.ts" 2>/dev/null
        echo -e "${GREEN}✓ Server stopped${NC}"
        echo ""
        exit 0
        ;;
      *)
        echo "Unknown command: $cmd"
        ;;
    esac
  done
}

# Main execution flow
main() {
  # Check for Conduit and offer to stop it
  if check_conduit; then
    echo -e "${YELLOW}⚠ Conduit is running (detected wata-matrix container)${NC}"
    echo -e "${YELLOW}  wata-server also uses port 8008, so they can't both run.${NC}"
    echo ""
    read -p "Stop Conduit and start wata-server? [Y/n] " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
      stop_conduit
    else
      echo -e "${RED}Conduit is still running. wata-server may fail to start.${NC}"
      echo -e "${YELLOW}Stop Conduit manually with: cd test/docker && docker-compose down${NC}"
      echo ""
    fi
  fi

  # Start wata-server
  if ! start_wata_server; then
    exit 1
  fi

  # Check if TEST_FILTER is provided - if so, run tests once and exit
  if [ -n "$TEST_FILTER" ]; then
    run_tests
    exit_code=$?

    # Stop server on test completion
    echo ""
    echo -e "${YELLOW}Stopping wata-server...${NC}"
    pkill -f "tsx src/server/index.ts" 2>/dev/null
    exit $exit_code
  fi

  # Check if running in CI/automated mode (no TTY)
  if [ ! -t 0 ]; then
    # Non-interactive: run tests once
    run_tests
    exit_code=$?
    pkill -f "tsx src/server/index.ts" 2>/dev/null
    exit $exit_code
  fi

  # Interactive mode
  interactive_loop
}

main "$@"
