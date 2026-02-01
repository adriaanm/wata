#!/bin/bash
set -e

cd "$(dirname "$0")"

# Parse arguments
CLEAN=false
if [[ "$1" == "--clean" || "$1" == "-c" ]]; then
  CLEAN=true
fi

# Function to check if Docker is available
check_docker() {
  docker info > /dev/null 2>&1
  return $?
}

# Function to check if Colima is installed
check_colima_installed() {
  command -v colima > /dev/null 2>&1
  return $?
}

# Function to check if Colima is running
check_colima_running() {
  colima status 2>/dev/null | grep -q "colima is running"
  return $?
}

# Ensure Docker is available
echo "Checking Docker availability..."
if ! check_docker; then
  echo "Docker is not available."

  # Check if Colima is installed
  if check_colima_installed; then
    echo "Colima is installed. Checking status..."

    if check_colima_running; then
      echo "Colima is running but Docker is not available. Waiting for Docker..."
      # Give it a moment to become available
      sleep 2
      if ! check_docker; then
        echo "ERROR: Colima is running but Docker is still not available."
        echo "Try running: colima restart"
        exit 1
      fi
    else
      echo "Starting Colima..."
      colima start

      # Wait for Docker to become available
      echo "Waiting for Docker to be ready..."
      for i in {1..30}; do
        if check_docker; then
          echo "Docker is ready!"
          break
        fi
        if [ "$i" -eq 30 ]; then
          echo "ERROR: Docker failed to become available after starting Colima."
          exit 1
        fi
        echo "  Waiting... ($i/30)"
        sleep 2
      done
    fi
  else
    echo "ERROR: Docker is not available and Colima is not installed."
    echo "Please install Docker Desktop or Colima:"
    echo "  - Docker Desktop: https://www.docker.com/products/docker-desktop"
    echo "  - Colima: brew install colima"
    exit 1
  fi
else
  echo "Docker is available!"
fi

# Clean up if requested
if $CLEAN; then
  echo "Cleaning up existing containers and volumes..."
  docker-compose down -v 2>/dev/null || true
  echo "Clean complete."
  echo ""
fi

# Start Conduit
echo "Starting Conduit Matrix server..."
docker-compose up -d

# Wait for server to be healthy
echo "Waiting for Conduit to be ready..."
for i in {1..30}; do
  if curl -sf http://localhost:8008/_matrix/client/versions > /dev/null 2>&1; then
    echo "Conduit is ready!"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "ERROR: Conduit failed to start. Check logs with: docker-compose logs"
    exit 1
  fi
  echo "  Waiting... ($i/30)"
  sleep 2
done

# Create test users
echo "Creating test users..."

register_user() {
  local username=$1
  local password=$2

  local result=$(curl -s -X POST http://localhost:8008/_matrix/client/v3/register \
    -H "Content-Type: application/json" \
    -d "{\"username\": \"$username\", \"password\": \"$password\", \"auth\": {\"type\": \"m.login.dummy\"}}" 2>&1)

  if echo "$result" | grep -q "user_id"; then
    echo "  Created user: @$username:localhost"
  elif echo "$result" | grep -q "M_USER_IN_USE"; then
    echo "  User already exists: @$username:localhost"
  else
    echo "  Warning: $username registration response: $result"
  fi
}

register_user "alice" "testpass123"
register_user "bob" "testpass123"

echo ""
echo "=========================================="
echo "Setup complete!"
echo "=========================================="
echo "Matrix server: http://localhost:8008"
echo "Test users:"
echo "  - @alice:localhost / testpass123"
echo "  - @bob:localhost / testpass123"
echo ""
echo "Commands:"
echo "  Run tests:  npm run test:integration"
echo "  Stop:       cd test/docker && docker-compose down"
echo "  Clean:      cd test/docker && docker-compose down -v"
