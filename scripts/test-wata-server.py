#!/usr/bin/env python3
"""
Quick iteration workflow for wata-server development

TDD Mode: Run one test at a time, iterate until green, move to next

Usage:
  python scripts/test-wata-server.py [--tdd] [test-filter]

Examples:
  python scripts/test-wata-server.py              # Interactive mode
  python scripts/test-wata-server.py --tdd         # TDD mode (auto-advance)
  python scripts/test-wata-server.py --tdd matrix    # TDD mode starting with specific test
"""

import argparse
import os
import subprocess
import sys
import time
import signal
import re
from pathlib import Path
from typing import List, Optional

# ANSI colors
class Colors:
    RED = '\033[0;31m'
    GREEN = '\033[0;32m'
    YELLOW = '\033[1;33m'
    BLUE = '\033[0;34m'
    CYAN = '\033[0;36m'
    BOLD = '\033[1m'
    NC = '\033[0m'

# Paths
SCRIPT_DIR = Path(__file__).parent
PROJECT_ROOT = SCRIPT_DIR.parent
LOG_FILE = Path("/tmp/wata-server.log")


def run_cmd(cmd: str, capture: bool = True, check: bool = False) -> subprocess.CompletedProcess:
    """Run a shell command."""
    if capture:
        return subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return subprocess.run(cmd, shell=True, check=check)


def check_wata_server() -> bool:
    """Check if wata-server is running on port 8008."""
    result = run_cmd("curl -sf http://localhost:8008/_matrix/client/versions")
    return result.returncode == 0


def check_conduit() -> bool:
    """Check if Conduit docker container is running."""
    result = run_cmd("docker -f wata-matrix ps 2>/dev/null", check=False)
    return "wata-matrix" in result.stdout and "Up" in result.stdout


def stop_conduit() -> bool:
    """Stop Conduit docker container."""
    print(f"{Colors.YELLOW}⏹ Stopping Conduit...{Colors.NC}")
    result = run_cmd("cd test/docker && docker-compose down 2>/dev/null || true")
    print(f"{Colors.GREEN}✓ Conduit stopped{Colors.NC}")
    return result.returncode == 0


def start_wata_server() -> bool:
    """Start wata-server in background."""
    if check_wata_server():
        print(f"{Colors.YELLOW}⚠ wata-server already running on port 8008{Colors.NC}")
        return True

    print(f"{Colors.YELLOW}▶ Starting wata-server...{Colors.NC}", end="", flush=True)
    run_cmd(f"pnpm wata-server > {LOG_FILE} 2>&1 &", capture=False)

    # Wait for server to be ready
    for _ in range(30):
        if check_wata_server():
            print(f" {Colors.GREEN}ready!{Colors.NC}")
            print()
            # Get PID
            pid_result = run_cmd("pgrep -f 'tsx src/server/index.ts'")
            if pid_result.returncode == 0:
                print(f"{Colors.GREEN}Server running with PID: {pid_result.stdout.strip()}{Colors.NC}")
            print(f"{Colors.GREEN}Logs: tail -f {LOG_FILE}{Colors.NC}")
            print()
            return True
        print(".", end="", flush=True)
        time.sleep(0.2)

    print(f" {Colors.RED}failed!{Colors.NC}")
    print(f"{Colors.RED}Check logs: cat {LOG_FILE}{Colors.NC}")
    return False


def stop_wata_server() -> None:
    """Stop wata-server."""
    run_cmd("pkill -f 'tsx src/server/index.ts' 2>/dev/null || true")


def get_all_tests() -> List[str]:
    """Get list of all available tests."""
    cmd = "NODE_OPTIONS='--experimental-vm-modules' npx jest --config test/integration/jest.config.js --listTests 2>/dev/null"
    result = run_cmd(cmd)
    tests = []
    for line in result.stdout.splitlines():
        line = line.strip()
        if line and not line.startswith("Test Suites"):
            tests.append(line)
    return tests


def run_single_test(test_name: str) -> bool:
    """Run a single test and return True if passed."""
    print(f"{Colors.BLUE}{'━' * 60}{Colors.NC}")
    print(f"{Colors.BLUE}  {Colors.BOLD}Running:{Colors.NC} {Colors.CYAN}{test_name}{Colors.NC}")
    print(f"{Colors.BLUE}{'━' * 60}{Colors.NC}")
    print()

    cmd = f"NODE_OPTIONS='--experimental-vm-modules' jest --config test/integration/jest.config.js --forceExit --no-coverage --testNamePattern='{test_name}'"
    result = run_cmd(cmd)
    print()

    if result.returncode == 0:
        print(f"{Colors.GREEN}✅ PASSED{Colors.NC} - {Colors.CYAN}{test_name}{Colors.NC}")
        return True
    else:
        print(f"{Colors.RED}❌ FAILED{Colors.NC} - {Colors.CYAN}{test_name}{Colors.NC}")
        print()
        print(f"{Colors.YELLOW}💡 Fix the issue, then press Enter to retry{Colors.NC}")
        return False


def show_logs() -> None:
    """Show recent server logs."""
    if LOG_FILE.exists():
        print(f"{Colors.YELLOW}📋 Recent server logs:{Colors.NC}")
        print()
        result = run_cmd(f"tail -n 50 {LOG_FILE}")
        print(result.stdout)
        print()
        print(f"{Colors.YELLOW}Follow live logs with: tail -f {LOG_FILE}{Colors.NC}")
    else:
        print(f"{Colors.RED}No server logs found at {LOG_FILE}{Colors.NC}")


def restart_server() -> bool:
    """Restart wata-server."""
    print(f"{Colors.YELLOW}Restarting wata-server...{Colors.NC}")
    stop_wata_server()
    time.sleep(1)
    result = start_wata_server()
    print()
    return result


def print_box(title: str, width: 60) -> None:
    """Print a formatted box."""
    padding = (width - len(title) - 2) // 2
    print(f"{Colors.GREEN}╔{'═' * width}╗{Colors.NC}")
    print(f"{Colors.GREEN}║{Colors.NC}{' ' * padding}{title}{' ' * (width - padding - len(title))}{Colors.GREEN}║{Colors.NC}")
    print(f"{Colors.GREEN}╚{'═' * width}╝{Colors.NC}")


def tdd_mode(starting_filter: str = "") -> None:
    """Run TDD mode - iterate through tests one at a time."""
    print_box("TDD Mode - Test Driven Development", 60)
    print(f"{Colors.GREEN}║{Colors.NC}  Runs one test at a time, retry until it passes         {Colors.GREEN}║{Colors.NC}")
    print(f"{Colors.GREEN}║{Colors.NC}  Auto-advances to next failing test when current passes  {Colors.GREEN}║{Colors.NC}")
    print(f"{Colors.GREEN}╚{'═' * 60}╝{Colors.NC}")
    print()

    # Check for server
    if not check_wata_server():
        print(f"{Colors.YELLOW}Starting wata-server...{Colors.NC}")
        if not start_wata_server():
            sys.exit(1)
        print()
    else:
        print(f"{Colors.GREEN}✓ wata-server already running{Colors.NC}")
        print()

    # Get all tests
    all_tests = get_all_tests()
    if not all_tests:
        print(f"{Colors.RED}No tests found!{Colors.NC}")
        sys.exit(1)

    # Find starting point
    current_index = 0
    if starting_filter:
        for i, test in enumerate(all_tests):
            if starting_filter.lower() in test.lower():
                current_index = i
                break

    # Main TDD loop
    while current_index < len(all_tests):
        current_test = all_tests[current_index]
        remaining = len(all_tests) - current_index - 1

        print(f"{Colors.BOLD}{'═' * 60}{Colors.NC}")
        print(f"{Colors.BOLD}║{Colors.NC}   Test {Colors.YELLOW}{current_index + 1}{Colors.NC}/{len(all_tests)}: {Colors.CYAN}{current_test}{Colors.NC}")
        print(f"{Colors.BOLD}║{Colors.NC}   Remaining: {Colors.YELLOW}{remaining}{Colors.NC} tests")
        print(f"{Colors.BOLD}╠{'═' * 60}╣{Colors.NC}")
        print()
        print(f"{Colors.YELLOW}Commands:{Colors.NC}  {Colors.CYAN}r{Colors.NC}=run  {Colors.CYAN}s{Colors.NC}=skip  {Colors.CYAN}l{Colors.NC}=logs  {Colors.CYAN}q{Colors.NC}=quit  {Colors.CYAN}j{Colors.NC}=jump to test")
        print()

        while True:
            try:
                cmd = input(f"{Colors.BLUE}➤{Colors.NC} ").strip().lower()
            except EOFError:
                cmd = "q"

            if cmd == "r" or cmd == "":
                if run_single_test(current_test):
                    current_index += 1
                    break
            elif cmd == "s":
                print(f"{Colors.YELLOW}Skipping: {current_test}{Colors.NC}")
                print()
                current_index += 1
                break
            elif cmd == "l":
                print()
                show_logs()
                print()
            elif cmd == "q":
                print()
                print(f"{Colors.YELLOW}Stopping wata-server...{Colors.NC}")
                stop_wata_server()
                print(f"{Colors.GREEN}✓ Server stopped{Colors.NC}")
                sys.exit(0)
            elif cmd == "j":
                print()
                try:
                    jump_input = input(f"{Colors.CYAN}Jump to test (1-{len(all_tests)}):{Colors.NC} ")
                    jump_index = int(jump_input)
                    if 1 <= jump_index <= len(all_tests):
                        current_index = jump_index - 1
                        print(f"{Colors.GREEN}Jumped to: {all_tests[current_index]}{Colors.NC}")
                    else:
                        print(f"{Colors.RED}Invalid test number{Colors.NC}")
                except ValueError:
                    print(f"{Colors.RED}Invalid input{Colors.NC}")
                print()
            else:
                print(f"{Colors.RED}Unknown command. Press Enter to run test.{Colors.NC}")

    print_box("All tests passed!", 60)
    print()
    print(f"{Colors.YELLOW}🎉 TDD cycle complete!{Colors.NC}")
    stop_wata_server()


def interactive_mode() -> None:
    """Run interactive mode with menu."""
    print(f"{Colors.GREEN}╔{'═' * 60}╗{Colors.NC}")
    print(f"{Colors.GREEN}║{Colors.NC}                    {Colors.YELLOW}Interactive Mode{Colors.NC}                    {Colors.GREEN}║{Colors.NC}")
    print(f"{Colors.GREEN}╠{'═' * 60}╣{Colors.NC}")
    print(f"{Colors.GREEN}║{Colors.NC} Commands:                                             {Colors.GREEN}║{Colors.NC}")
    print(f"{Colors.GREEN}║{Colors.NC}   {Colors.YELLOW}t{Colors.NC} - Run tests              {Colors.GREEN}║{Colors.NC}")
    print(f"{Colors.GREEN}║{Colors.NC}   {Colors.YELLOW}o{Colors.NC} - Run one test (prompt for name) {Colors.GREEN}║{Colors.NC}")
    print(f"{Colors.GREEN}║{Colors.NC}   {Colors.YELLOW}l{Colors.NC} - Show recent server logs  {Colors.GREEN}║{Colors.NC}")
    print(f"{Colors.GREEN}║{Colors.NC}   {Colors.YELLOW}r{Colors.NC} - Restart wata-server      {Colors.GREEN}║{Colors.NC}")
    print(f"{Colors.GREEN}║{Colors.NC}   {Colors.YELLOW}s{Colors.NC} - Stop wata-server         {Colors.GREEN}║{Colors.NC}")
    print(f"{Colors.GREEN}║{Colors.NC}   {Colors.YELLOW}--{Colors.NC} - List all available tests {Colors.GREEN}║{Colors.NC}")
    print(f"{Colors.GREEN}║{Colors.NC}   {Colors.YELLOW}q{Colors.NC} - Quit                    {Colors.GREEN}║{Colors.NC}")
    print(f"{Colors.GREEN}╚{'═' * 60}╝{Colors.NC}")
    print()

    while True:
        try:
            cmd = input(f"{Colors.YELLOW}> {Colors.NC}").strip().lower()
        except EOFError:
            cmd = "q"

        if cmd == "t":
            # Run all tests
            result = run_cmd("pnpm test:integration", capture=False)
            if result.returncode == 0:
                print(f"{Colors.GREEN}✅ All tests passed!{Colors.NC}")
            else:
                print(f"{Colors.RED}❌ Tests failed{Colors.NC}")
        elif cmd == "o":
            test_pattern = input(f"{Colors.CYAN}Enter test name or pattern:{Colors.NC}\n> ")
            run_single_test(test_pattern)
        elif cmd == "--":
            tests = get_all_tests()
            print(f"{Colors.CYAN}Available tests:{Colors.NC}")
            for i, test in enumerate(tests, 1):
                print(f"  {i}. {test}")
        elif cmd == "l":
            show_logs()
        elif cmd == "r":
            restart_server()
        elif cmd == "s":
            print(f"{Colors.YELLOW}Stopping wata-server...{Colors.NC}")
            stop_wata_server()
            print(f"{Colors.GREEN}✓ Server stopped{Colors.NC}")
            print()
            break
        elif cmd == "q":
            print(f"{Colors.YELLOW}Stopping wata-server...{Colors.NC}")
            stop_wata_server()
            print(f"{Colors.GREEN}✓ Server stopped{Colors.NC}")
            print()
            sys.exit(0)
        else:
            print(f"Unknown command: {cmd}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Wata-server test runner")
    parser.add_argument("--tdd", action="store_true", help="Enable TDD mode")
    parser.add_argument("test_filter", nargs="?", default="", help="Test name filter")
    args = parser.parse_args()

    os.chdir(PROJECT_ROOT)

    # Check for Conduit and offer to stop it
    if check_conduit():
        print(f"{Colors.YELLOW}⚠ Conduit is running (detected wata-matrix container){Colors.NC}")
        print(f"{Colors.YELLOW}  wata-server also uses port 8008, so they can't both run.{Colors.NC}")
        print()

        if sys.stdin.isatty():
            reply = input("Stop Conduit and start wata-server? [Y/n] ").strip().lower()
            if reply in ("", "y"):
                stop_conduit()
            else:
                print(f"{Colors.RED}Conduit is still running. wata-server may fail to start.{Colors.NC}")
        else:
            print(f"{Colors.YELLOW}Stop Conduit manually with: cd test/docker && docker-compose down{Colors.NC}")

    # TDD mode
    if args.tdd:
        tdd_mode(args.test_filter)
        return

    # Start wata-server
    if not check_wata_server():
        if not start_wata_server():
            sys.exit(1)

    # Single test mode
    if args.test_filter:
        print(f"{Colors.YELLOW}▶ Running single test: {args.test_filter}{Colors.NC}")
        print()
        passed = run_single_test(args.test_filter)
        stop_wata_server()
        sys.exit(0 if passed else 1)

    # Non-interactive mode (CI)
    if not sys.stdin.isatty():
        result = run_cmd("pnpm test:integration", capture=False)
        stop_wata_server()
        sys.exit(result.returncode)

    # Interactive mode
    interactive_mode()


if __name__ == "__main__":
    # Set up signal handlers for clean shutdown
    def signal_handler(sig, frame):
        print()
        print(f"{Colors.YELLOW}Stopping wata-server...{Colors.NC}")
        stop_wata_server()
        sys.exit(0)

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    main()
