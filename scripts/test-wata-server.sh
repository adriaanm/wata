#!/bin/bash
# Quick iteration workflow for wata-server development
#
# TDD Mode: Run one test at a time, iterate until green, move to next
#
# Usage:
#   ./scripts/test-wata-server.sh [--tdd] [test-filter]
#
# Examples:
#   ./scripts/test-wata-server.sh              # Interactive mode
#   ./scripts/test-wata-server.sh --tdd         # TDD mode (auto-advance)
#   ./scripts/test-wata-server.sh --tdd matrix    # TDD mode starting with specific test

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

# Mode flags
TDD_MODE=false
TEST_FILTER=""

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tdd)
      TDD_MODE=true
      shift
      ;;
    *)
      if [ -z "$TEST_FILTER" ]; then
        TEST_FILTER="$1"
      fi
      shift
      ;;
  esac
done

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

# Function to list all available tests
list_all_tests() {
  echo -e "${CYAN}Available tests:${NC}"
  echo ""

  # Use jest to list all tests
  NODE_OPTIONS='--experimental-vm-modules' npx jest --config test/integration/jest.config.js --listTests 2>/dev/null | grep -v "Test Suites" || true

  echo ""
}

# Function to run a single test and return result
run_single_test() {
  local test_name="$1"
  local test_file="$2"

  echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo -e "${BLUE}  ${BOLD}Running:${NC} ${CYAN}$test_name${NC}"
  echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo ""

  # Build jest command with specific test
  local jest_cmd="NODE_OPTIONS='--experimental-vm-modules' jest --config test/integration/jest.config.js --forceExit --no-coverage"

  if [ -n "$test_name" ]; then
    jest_cmd="$jest_cmd --testNamePattern=\"$test_name\""
  fi

  # Run the test
  eval "$jest_cmd"
  local exit_code=$?

  echo ""
  if [ $exit_code -eq 0 ]; then
    echo -e "${GREEN}✅ PASSED${NC} - ${CYAN}$test_name${NC}"
    return 0
  else
    echo -e "${RED}❌ FAILED${NC} - ${CYAN}$test_name${NC}"
    echo ""
    echo -e "${YELLOW}💡 Fix the issue, then press Enter to retry${NC}"
    return 1
  fi
}

# Function to run tests in TDD mode
tdd_mode() {
  local starting_filter="$1"

  echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
  echo -e "${GREEN}║${NC}                   ${BOLD}TDD Mode - Test Driven Development${NC}            ${GREEN}║${NC}"
  echo -e "${GREEN}╠══════════════════════════════════════════════════════════╣${NC}"
  echo -e "${GREEN}║${NC}  Runs one test at a time, retry until it passes         ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}  Auto-advances to next failing test when current passes  ${GREEN}║${NC}"
  echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
  echo ""

  # Check for server
  if ! check_wata_server; then
    echo -e "${YELLOW}Starting wata-server...${NC}"
    if ! start_wata_server; then
      exit 1
    fi
    echo ""
  else
    echo -e "${GREEN}✓ wata-server already running${NC}"
    echo ""
  fi

  # Get initial test list
  local all_tests=()
  while IFS= read -r line; do
    [[ ! "$line" =~ ^Test\ Suites ]] && [[ -n "$line" ]] && all_tests+=("$line")
  done < <(NODE_OPTIONS='--experimental-vm-modules' npx jest --config test/integration/jest.config.js --listTests 2>/dev/null | grep -v "Test Suites" || true)

  if [ ${#all_tests[@]} -eq 0 ]; then
    echo -e "${RED}No tests found!${NC}"
    exit 1
  fi

  # Find starting point
  local current_index=0
  if [ -n "$starting_filter" ]; then
    # Find index of starting test
    for i in "${!all_tests[@]}"; do
      if [[ "${all_tests[$i]}" == *"$starting_filter"* ]]; then
        current_index=$i
        break
      fi
    done
  fi

  # Main TDD loop
  while [ $current_index -lt ${#all_tests[@]} ]; do
    local current_test="${all_tests[$current_index]}"
    local remaining=$((${#all_tests[@]} - current_index - 1))

    echo -e "${BOLD}═════════════════════════════════════════════════════════${NC}"
    echo -e "${BOLD}║${NC}   Test ${YELLOW}$((current_index + 1))${NC}/${#all_tests[@]}: ${CYAN}$current_test${NC}      ${BOLD}║${NC}"
    echo -e "${BOLD}║${NC}   Remaining: ${YELLOW}$remaining${NC} tests                                 ${BOLD}║${NC}"
    echo -e "${BOLD}╠══════════════════════════════════════════════════════════╣${NC}"
    echo ""
    echo -e "${YELLOW}Commands:${NC}  ${CYAN}r${NC}=run  ${CYAN}s${NC}=skip  ${CYAN}l${NC}=logs  ${CYAN}q${NC}=quit  ${CYAN}j${NC}=jump to test"
    echo ""

    while true; do
      echo -n -e "${BLUE}➤${NC} "
      read -n 1 -p "" cmd

      case $cmd in
        r)
          echo ""
          if run_single_test "$current_test"; then
            # Test passed - move to next
            current_index=$((current_index + 1))
            break
          fi
          ;;
        s)
          echo -e "${YELLOW}Skipping: $current_test${NC}"
          echo ""
          current_index=$((current_index + 1))
          break
          ;;
        l)
          echo ""
          show_logs
          echo ""
          ;;
        q)
          echo ""
          echo -e "${YELLOW}Stopping wata-server...${NC}"
          pkill -f "tsx src/server/index.ts" 2>/dev/null
          echo -e "${GREEN}✓ Server stopped${NC}"
          exit 0
          ;;
        j)
          echo ""
          echo -e "${CYAN}Jump to test (1-${#all_tests[@]}):${NC}"
          read -p "> "  " jump_index
          if [[ "$jump_index" =~ ^[0-9]+$ ]] && [ $jump_index -ge 1 ] && [ $jump_index -le ${#all_tests[@]} ]; then
            current_index=$((jump_index - 1))
            echo -e "${GREEN}Jumped to: ${all_tests[$current_index]}${NC}"
          else
            echo -e "${RED}Invalid test number${NC}"
          fi
          echo ""
          ;;
        "")
          # Run test by default
          echo ""
          if run_single_test "$current_test"; then
            current_index=$((current_index + 1))
            break
          fi
          ;;
        *)
          echo -e "${RED}Unknown command. Press Enter to run test.${NC}"
          ;;
      esac
    done
  done

  echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
  echo -e "${GREEN}║${NC}                    ${BOLD}${GREEN}All tests passed!${NC}                    ${GREEN}║${NC}"
  echo -e "${GREEN}╚════════════════════════════════════════════════════════════╝${NC}"
  echo ""
  echo -e "${YELLOW}🎉 TDD cycle complete!${NC}"

  # Stop server
  pkill -f "tsx src/server/index.ts" 2>/dev/null
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
    tail -n 50 /tmp/wata-server.log
    echo ""
    echo -e "${YELLOW}Follow live logs with: tail -f /tmp/wata-server.log${NC}"
  else
    echo -e "${RED}No server logs found at /tmp/wata-server.log${NC}"
  fi
}

# Function to restart server
restart_server() {
  echo -e "${YELLOW}Restarting wata-server...${NC}"
  pkill -f "tsx src/server/index.ts" 2>/dev/null || true
  sleep 1
  start_wata_server || return 1
  echo ""
}

# Function for interactive loop
interactive_loop() {
  echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
  echo -e "${GREEN}║${NC}                    ${YELLOW}Interactive Mode${NC}                    ${GREEN}║${NC}"
  echo -e "${GREEN}╠════════════════════════════════════════════════════════╣${NC}"
  echo -e "${GREEN}║${NC} Commands:                                             ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}   ${YELLOW}t${NC} - Run tests              ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}   ${YELLOW}o${NC} - Run one test (prompt for name) ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}   ${YELLOW}l${NC} - Show recent server logs  ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}   ${YELLOW}r${NC} - Restart wata-server      ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}   ${YELLOW}s${NC} - Stop wata-server         ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}   ${YELLOW}--${NC} - List all available tests ${GREEN}║${NC}"
  echo -e "${GREEN}║${NC}   ${YELLOW}q${NC} - Quit                    ${GREEN}║${NC}"
  echo -e "${GREEN}╚══════════════════════════════════════════════════════════╝${NC}"
  echo ""

  while true; do
    echo -n -e "${YELLOW}> ${NC}"
    read -n 1 cmd

    case $cmd in
      t)
        run_tests
        ;;
      o)
        echo ""
        echo -e "${CYAN}Enter test name or pattern:${NC}"
        read -p "> "  " test_pattern
        echo ""
        run_single_test "$test_pattern"
        ;;
      --)
        list_all_tests
        ;;
      l)
        show_logs
        ;;
      r)
        restart_server
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
  # TDD mode
  if [ "$TDD_MODE" = true ]; then
    # Check for Conduit
    if check_conduit; then
      echo -e "${YELLOW}⚠ Conduit is running${NC}"
      echo -e "${YELLOW}  wata-server also uses port 8008, so they can't both run.${NC}"
      echo ""
      read -p "Stop Conduit and start wata-server? [Y/n] " -n 1 -r
      echo ""
      if [[ $REPLY =~ ^[Yy]$ ]]; then
        stop_conduit
      else
        echo -e "${RED}Conduit is still running. wata-server may fail to start.${NC}"
        exit 1
      fi
    fi

    tdd_mode "$TEST_FILTER"
    exit $?
  fi

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
  if ! check_wata_server; then
    if ! start_wata_server; then
      exit 1
    fi
  fi
  fi

  # Check if TEST_FILTER is provided - if so, run one test and exit
  if [ -n "$TEST_FILTER" ]; then
    echo -e "${YELLOW}▶ Running single test: ${TEST_FILTER}${NC}"
    echo ""
    run_single_test "$TEST_FILTER"
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
