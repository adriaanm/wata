#!/bin/bash
# Helper script for wata-server development workflow

set -e

SERVER_LOG="/tmp/wata-server.log"
SERVER_PATTERN="node.*server/index"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to check if server is running
is_server_running() {
    pgrep -f "$SERVER_PATTERN" > /dev/null 2>&1
}

# Function to get server PID
get_server_pid() {
    pgrep -f "$SERVER_PATTERN" || true
}

# Function to stop the server
stop_server() {
    if is_server_running; then
        echo -e "${YELLOW}Stopping wata-server...${NC}"
        pkill -f "$SERVER_PATTERN"
        # Wait for process to actually terminate
        local count=0
        while is_server_running && [ $count -lt 10 ]; do
            sleep 0.5
            count=$((count + 1))
        done
        if is_server_running; then
            echo -e "${RED}Failed to stop server gracefully, killing with -9${NC}"
            pkill -9 -f "$SERVER_PATTERN"
        fi
        echo -e "${GREEN}✓ Server stopped${NC}"
    else
        echo -e "${YELLOW}Server not running${NC}"
    fi
}

# Function to start the server
start_server() {
    if is_server_running; then
        echo -e "${YELLOW}Server already running (PID: $(get_server_pid))${NC}"
        return 1
    fi

    echo -e "${GREEN}Starting wata-server...${NC}"
    # Clear log file
    rm -f "$SERVER_LOG"
    # Start server in background
    pnpm wata-server > "$SERVER_LOG" 2>&1 &
    # Wait for server to be ready
    local count=0
    while ! curl -s http://localhost:8008/_matrix/client/versions > /dev/null 2>&1 && [ $count -lt 20 ]; do
        sleep 0.3
        count=$((count + 1))
    done
    if curl -s http://localhost:8008/_matrix/client/versions > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Server started (PID: $(get_server_pid))${NC}"
    else
        echo -e "${RED}✗ Server failed to start${NC}"
        return 1
    fi
}

# Function to restart the server
restart_server() {
    stop_server
    sleep 1
    start_server
}

# Function to show server logs
show_logs() {
    if [ ! -f "$SERVER_LOG" ]; then
        echo -e "${YELLOW}No log file found${NC}"
        return 1
    fi
    local lines="${1:-50}"
    echo -e "${GREEN}Showing last $lines lines of $SERVER_LOG:${NC}"
    echo "---"
    tail -n "$lines" "$SERVER_LOG"
}

# Function to tail logs (follow mode)
tail_logs() {
    if [ ! -f "$SERVER_LOG" ]; then
        echo -e "${YELLOW}No log file found${NC}"
        return 1
    fi
    echo -e "${GREEN}Following $SERVER_LOG (Ctrl+C to exit):${NC}"
    echo "---"
    tail -f "$SERVER_LOG"
}

# Function to search logs
search_logs() {
    if [ -z "$1" ]; then
        echo -e "${RED}Usage: $0 search <pattern>${NC}"
        return 1
    fi
    if [ ! -f "$SERVER_LOG" ]; then
        echo -e "${YELLOW}No log file found${NC}"
        return 1
    fi
    echo -e "${GREEN}Searching logs for: $1${NC}"
    echo "---"
    grep -E "$1" "$SERVER_LOG" || true
}

# Function to show server status
status() {
    if is_server_running; then
        local pid=$(get_server_pid)
        echo -e "${GREEN}✓ Server running (PID: $pid)${NC}"
        # Check if responding
        if curl -s http://localhost:8008/_matrix/client/versions > /dev/null 2>&1; then
            echo -e "${GREEN}✓ Server responding on port 8008${NC}"
        else
            echo -e "${RED}✗ Server not responding${NC}"
        fi
    else
        echo -e "${YELLOW}Server not running${NC}"
    fi
}

# Function to run a single test file
run_test() {
    if [ -z "$1" ]; then
        echo -e "${RED}Usage: $0 test <test-file>${NC}"
        return 1
    fi
    local test_file="$1"
    if [[ ! "$test_file" =~ \.test\.ts$ ]]; then
        test_file="${test_file}.test.ts"
    fi
    local test_path="test/integration/$test_file"
    if [ ! -f "$test_path" ]; then
        echo -e "${RED}Test file not found: $test_path${NC}"
        return 1
    fi
    echo -e "${GREEN}Running test: $test_file${NC}"
    npx jest -c test/integration/jest.config.js "$test_path"
}

# Main command dispatch
case "${1:-}" in
    start)
        start_server
        ;;
    stop)
        stop_server
        ;;
    restart|reload)
        restart_server
        ;;
    logs|log)
        show_logs "${2:-50}"
        ;;
    tail|follow)
        tail_logs
        ;;
    search|grep)
        search_logs "$2"
        ;;
    status)
        status
        ;;
    test)
        run_test "$2"
        ;;
    "")
        echo -e "${GREEN}wata-server development helper${NC}"
        echo ""
        echo "Usage: $0 <command> [args]"
        echo ""
        echo "Commands:"
        echo "  start              Start the server"
        echo "  stop               Stop the server"
        echo "  restart, reload     Restart the server"
        echo "  logs [lines]      Show last N lines of logs (default: 50)"
        echo "  tail, follow      Follow logs in real-time"
        echo "  search <pattern>   Search logs for pattern"
        echo "  status             Show server status"
        echo "  test <file>        Run a single test file"
        echo ""
        echo "Examples:"
        echo "  $0 restart              # Restart server"
        echo "  $0 logs 100            # Show last 100 log lines"
        echo "  $0 search 'POST.*login' # Search for login requests"
        echo "  $0 test matrix          # Run matrix.test.ts"
        ;;
    *)
        echo -e "${RED}Unknown command: $1${NC}"
        echo "Run '$0' for usage"
        exit 1
        ;;
esac
