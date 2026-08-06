# bindgen fixtures

One fixture per declaration shape that the type mapper has to get right. Each
`<name>.json` is a hand-cut excerpt of clang's JSON AST — the same node shapes
`clang -Xclang -ast-dump=json` prints over the real SDK headers, trimmed to the
one declaration under test and stripped of `loc`/`range`/attribute noise. The
matching `<name>.expected/` directory holds the Go the generator must emit for
it, byte for byte, plus the refusals it must produce.

Raw SDK dumps are not committed: an unfiltered Foundation + PushToTalk dump is
~116 MB, and a fixture that large would be unreadable and unreviewable.

A fixture file is:

```json
{
  "package": "fixture",
  "classes":   [ <ObjCInterfaceDecl node>, ... ],
  "opaque":    [ <ObjCInterfaceDecl node>, ... ],
  "protocols": [ <ObjCProtocolDecl node>, ... ],
  "enums":     [ <EnumDecl node>, ... ],
  "structNames": [ "WFRange", ... ],
  "structs":   [ <RecordDecl / TypedefDecl nodes>, ... ]
}
```

`structs` holds the record and typedef nodes as clang dumps them (a named
record, or an anonymous one immediately before its typedef); `structNames`
is the allowlist the loader resolves against them.

`wataDoc` on a node stands in for the doc comment the loader would have read
out of the header, so fixtures need no headers. Doc extraction itself is tested
separately, against `docs.h`.

Update the expected output with `tools/bindgen/test_bindgen.py --update` and
review the diff — it is the generator's behavior, not a formality.
