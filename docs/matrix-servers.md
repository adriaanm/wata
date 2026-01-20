# Matrix Server Comparison & Recommendations

## Push Notifications Support in Conduit

**Status**: Conduit DOES support push notifications.

**History**:
- Earlier versions (v0.3.0) had issues with push notifications where nulls were sent in wrong places
- These were fixed in later releases
- The popular fork [conduwuit v0.4.2](https://github.com/girlbossceo/conduwuit/releases) specifically addressed push notification bugs
- Conduit v0.10.0 (released May 2025) brought improvements with more active maintenance

## Known Limitations of Conduit

Conduit is in Beta status. Known limitations include:

**Federation limitations:**
- E2EE emoji comparison over federation (E2EE chat itself works fine)
- Outgoing read receipts, typing indicators, and presence over federation
- Doesn't support batched key requests (trusted_servers list should only contain Synapse servers)

**Performance notes:**
- Can be slow when joining large rooms/channels (especially with default SQLite)
- Small channels join almost instantly

**User experience:**
- Not all Matrix features are supported
- May encounter occasional bugs

## Server Comparison

| Server | Status | Language | Resource Usage | Performance | Best For |
|--------|--------|----------|----------------|-------------|----------|
| **Conduit** | Beta | Rust | ~500 MB RAM | Extremely fast (4s benchmark) | Lightweight local development, small deployments |
| **Synapse** | Stable (reference) | Python | 4-8 cores, 8GB+ RAM, 100GB+ disk | Slowest (1m46s benchmark) | Production, public servers, full feature set |
| **Dendrite** | Inactive | Go | More efficient than Synapse | Moderate (2m45s benchmark) | Not recommended (no longer actively developed) |

### Synapse
- Reference implementation, most mature and feature-complete
- Incredibly resource hungry, requires extensive configuration
- Better tooling for public deployments
- Used by matrix.org

### Dendrite
- Second-generation server with 100% server-server parity
- 93% client-server parity (missing SSO, Third-party ID APIs)
- **Not actively developed** due to funding issues at Element/Matrix.org Foundation
- Not recommended for new projects

### Conduit
- Written in Rust, blazingly fast performance
- Very lightweight (500 MB RAM vs 8GB+ for Synapse)
- Active development with regular updates
- Smaller feature set, some bugs expected

## Recommendation for This Project

**Continue using Conduit** for local development and testing.

### Why Conduit is sufficient:
- ✅ Supports voice messages (standard `m.audio` events)
- ✅ Supports push notifications (ready for Phase 6 testing)
- ✅ Very fast and lightweight for local development
- ✅ Perfect for integration testing with Metro bundler workflow
- ✅ Missing features (federation extras, typing indicators) don't affect PTT voice messaging use case
- ✅ Works great with the `pnpm dev:server` workflow

### When to consider Synapse:
- If you encounter specific bugs with push notifications during Phase 6 testing
- If you need to test advanced federation features
- For production deployment (though you're using matrix.org for that)
- If you need 100% Matrix spec compliance

## References

- [Playing with Matrix: Conduit and Synapse](https://akselmo.dev/posts/playing-with-matrix-conduit-and-synapse/)
- [Conduit - Your own chat server](https://conduit.rs/)
- [GitHub - timokoesters/conduit](https://github.com/timokoesters/conduit)
- [Matrix.org - Servers](https://matrix.org/ecosystem/servers/)
- [The future of Synapse and Dendrite](https://matrix.org/blog/2023/11/06/future-of-synapse-dendrite/)
- [Your favorite "next-gen" matrix server - Lemmy.World](https://lemmy.world/post/2369001)
