# Wata

A Matrix homeserver (`wata-server`) and a framebuffer device client
(`wata-fb`) over a shared portable client core (`wataclient`), written in
**Sgola** — restricted Scala 3 compiled to readable Go source, no JVM at
runtime.

```
just            # every recipe this repo has
just sync       # fetch and build the pinned sgola toolchain
just build      # both apps
just ci         # the whole gate
```

Building needs the sgola compiler, pinned by `tools/toolchain-pin.txt`.
`just sync` clones it to `.toolchain/sgola`; a preset `$SGOLA_HOME`
overrides the pin.

[CLAUDE.md](CLAUDE.md) has the layout, the toolchain rules, and how work
is organized here. Design docs are in [docs/design/](docs/design/), plans
in [docs/plans/](docs/plans/), open work in [TODO.jsonl](TODO.jsonl).
