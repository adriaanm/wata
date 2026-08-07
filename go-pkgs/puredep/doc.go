// Dependency plumbing for tools/objc-spike (plan 0038) — NOT a shim.
//
// The spike's whole point is that no Go code of ours sits between Sgola and
// the C ABI, so this module holds no functions. It exists because
// `sgo.build`'s `godep` names a local directory: an upstream module rides in
// through a local one that requires it, and the blank import is what keeps
// `go mod tidy` from pruning the require this module exists to carry.
//
// If a function ever appears in this package, the spike has failed and the
// report should say so rather than the package quietly growing a bridge.
package puredep

import _ "github.com/ebitengine/purego"
