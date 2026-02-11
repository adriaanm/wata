# Wata Project Memory

## Git Workflow
- After `git add`, always run `git status --short` to verify only expected files are staged before committing
- The project root is `/Users/adriaan/g/wata`, not subdirectories like `test/docker/`

## Buffer and Retry Pattern

When information needed for decision-making arrives asynchronously, buffer events until all information is available.

**Example:** Matrix DM messages arrive before `m.direct` account data or `is_direct` flag.

**Implementation:**
1. Buffer events that can't be processed yet
2. Retry frequently (e.g., every 300ms) to check if conditions are met
3. Prune stale events after a timeout (e.g., 5 minutes)

**Key insight:** Don't use heuristics to guess. Wait for definitive information - it usually arrives within milliseconds.

```
Event arrives → Can we process? → No → Buffer
                    ↓ Yes
              Process immediately

Retry timer (300ms) → Check buffered events → Can process now? → Flush
```

**See:** `src/shared/lib/wata-client/event-buffer.ts`, `docs/dm-room-service.md`

## Integration Test Patterns

Apply the same buffer-and-retry philosophy to tests:

**Poll fast, timeout fast:**
- Poll every 100ms (not exponential backoff)
- Short timeouts: 5-10 seconds (not 30+)
- Things happen quickly; don't wait longer than needed

**No exponential backoff in tests:**
- Exponential backoff is for production resilience
- Tests should fail fast with predictable timing
- Log elapsed time to debug slow operations

**See:** `test/integration/helpers/test-client.ts` - `waitForRoom`, `waitForMessage`, `waitForCondition`
