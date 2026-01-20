# Coding Rules

This document outlines the coding standards and conventions for the WATA project. These rules help maintain consistency and prevent common issues.

## Logging

### TUI (Terminal UI)

**CRITICAL**: Never use `console.log`, `console.warn`, or `console.error` directly in TUI code.

The TUI uses LogService to capture all logs and display them in the LogView (press `l`).

#### Correct Usage

```typescript
import { LogService } from './services/LogService.js';

// Define logging helpers at the top of your file
const log = (message: string): void => {
  try {
    LogService.getInstance()?.addEntry('log', message);
  } catch {
    // LogService not available, silently ignore
  }
};

const logError = (message: string): void => {
  try {
    LogService.getInstance()?.addEntry('error', message);
  } catch {
    // LogService not available, silently ignore
  }
};

// Use the helpers
log('User logged in');
logError(`Failed to connect: ${error}`);
```

#### What NOT to Do

```typescript
// ❌ DON'T: Direct console calls corrupt the TUI
console.log('Something happened');
console.warn('Warning message');
console.error('Error message');

// ❌ DON'T: Even with fancy formatting
console.log('[MyModule] Something happened', { data });
```

#### Why?

1. **UI Corruption**: Direct console output corrupts the Ink terminal interface
2. **User Experience**: Logs appear over the UI, making it unusable
3. **LogService**: Provides a centralized log buffer viewable via `l` key
4. **Cross-platform**: Try/catch pattern works in both TUI and React Native

### React Native

For React Native code, console logging is acceptable for debugging, but consider using a logging library for production.

### Shared Code

For code shared between TUI and React Native (in `src/shared/`):

```typescript
import { LogService } from '@tui/services/LogService';

const log = (message: string): void => {
  try {
    LogService.getInstance()?.addEntry('log', message);
  } catch {
    // LogService not available (React Native), silently ignore
  }
};
```

## Code Style

### General

- Use TypeScript for all new code
- Follow the existing code style (use `pnpm format` before committing)
- Run `pnpm check` before committing (includes typecheck, lint, and format check)
- Keep functions small and focused
- Use descriptive variable and function names

### Imports

- Use path aliases: `@shared/`, `@rn/`, `@tui/`
- Use `.js` extensions in ESM imports (even for TypeScript files)
- Group imports: external libs → internal modules → relative imports

```typescript
import React from 'react';
import { Box } from 'ink';
import { LogService } from './services/LogService.js';
import { MyComponent } from './components/MyComponent.js';
```

### Error Handling

- Always handle errors appropriately
- Use LogService for logging errors in TUI
- Provide meaningful error messages to users
- Don't swallow errors silently

```typescript
try {
  await riskyOperation();
} catch (error) {
  logError(`Operation failed: ${error}`);
  // Show user-friendly message
  setError('Failed to complete operation. Please try again.');
}
```

## Testing

- Write tests for new features
- Run tests before committing
- Use descriptive test names
- Test edge cases and error conditions

## Comments

- Use comments to explain **why**, not **what**
- Keep comments up to date
- Don't comment out code - remove it
- Use JSDoc for public APIs

```typescript
/**
 * Calculate the playback position based on timestamps
 * @param startTime - When playback started (ms since epoch)
 * @param currentTime - Current time (ms since epoch)
 * @returns Playback position in milliseconds
 */
function calculatePosition(startTime: number, currentTime: number): number {
  return currentTime - startTime;
}
```

## Git Commit Conventions

- Use clear, descriptive commit messages
- Start with a verb: "Add", "Fix", "Update", "Refactor"
- Keep the first line under 72 characters
- Add detailed description if needed

```
Add profile selector with interactive list

- Replace blind cycling with arrow key navigation
- Show current profile indicator
- Add number shortcuts (1-2) for quick selection
```
