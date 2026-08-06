#!/usr/bin/env python3
"""Generate Go bindings for Apple frameworks from clang's JSON AST.

The SDK on disk is the authority: an umbrella .m imports the frameworks a
target names, `clang -Xclang -ast-dump=json` prints the declarations, and this
generator turns the ones an allowlist names into readable Go over the ObjC
runtime (github.com/ebitengine/purego/objc).

Two stages, split so the second is testable without Xcode:

  load   AST nodes for the allowlisted names  ->  a small IR (JSON-able)
  emit   IR  ->  Go source files + a refusal list

`regen.sh` runs both. The unit tests run only `emit`, over committed AST
excerpts under fixtures/.

Anything the type mapper cannot express in Go is refused per declaration, with
a reason, and the refusals are written to REFUSALS.md next to the generated
code: the refusal list is the worklist.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
PUREGO_OBJC = "github.com/ebitengine/purego/objc"
OBJCRT = "github.com/adriaanm/wata/go-pkgs/appleptt/objcrt"


# ── refusals ──────────────────────────────────────────────────────────────────


class Unmappable(Exception):
    """A declaration this generator cannot express in Go."""


@dataclass
class Refusal:
    owner: str  # the class/protocol/enum it was reached through
    decl: str  # the selector or member
    reason: str

    def line(self) -> str:
        return f"- `{self.owner}` `{self.decl}` — {self.reason}"


# ── the IR ────────────────────────────────────────────────────────────────────
#
# Deliberately small and JSON-able: everything `emit` needs and nothing about
# clang. Ordering is fixed at load time (sorted), so emission is deterministic.


@dataclass
class Param:
    name: str
    type: dict  # {"qualType": ..., "desugaredQualType": ...}


@dataclass
class Method:
    sel: str
    instance: bool
    ret: dict
    params: list[Param]
    doc: str = ""
    unavailable: bool = False


@dataclass
class Prop:
    name: str
    type: dict
    readonly: bool
    doc: str = ""
    on_class: bool = False  # @property (class, ...): an accessor on the metaclass


@dataclass
class Class:
    name: str
    super: str
    methods: list[Method] = field(default_factory=list)
    props: list[Prop] = field(default_factory=list)
    doc: str = ""


@dataclass
class Protocol:
    name: str
    methods: list[Method] = field(default_factory=list)
    doc: str = ""


@dataclass
class Enum:
    name: str
    underlying: str
    consts: list[tuple[str, int, str]] = field(default_factory=list)
    doc: str = ""


@dataclass
class StructField:
    name: str
    type: dict
    bitfield: bool = False
    doc: str = ""


@dataclass
class Struct:
    name: str  # the typedef name declarations use (NSRange)
    record: str  # the record spelling clang desugars to (struct _NSRange)
    fields: list[StructField] = field(default_factory=list)
    union: bool = False
    doc: str = ""


@dataclass
class Target:
    package: str
    out: str
    sdk: str
    triple: str
    imports: list[str]
    classes: list[str]
    protocols: list[str]
    enums: list[str]
    opaque: list[str]
    structs: list[str] = field(default_factory=list)
    frameworks: list[str] = field(default_factory=list)

    @staticmethod
    def load(d: dict) -> "Target":
        return Target(
            package=d["package"],
            out=d["out"],
            sdk=d["sdk"],
            triple=d["triple"],
            imports=d["imports"],
            classes=sorted(d.get("classes", [])),
            protocols=sorted(d.get("protocols", [])),
            enums=sorted(d.get("enums", [])),
            opaque=sorted(d.get("opaque", [])),
            structs=sorted(d.get("structs", [])),
            frameworks=d.get("frameworks", []),
        )


# ── stage 1: clang AST -> IR ──────────────────────────────────────────────────


def sdk_path(sdk: str) -> str:
    return subprocess.run(
        ["xcrun", "--sdk", sdk, "--show-sdk-path"], capture_output=True, text=True, check=True
    ).stdout.strip()


def ast_dump(umbrella: Path, sysroot: str, triple: str, name_filter: str) -> list[dict]:
    """Every top-level declaration whose name contains `name_filter`.

    The filter is what keeps this tractable: an unfiltered dump of Foundation +
    PushToTalk is ~116 MB of JSON, a filtered one is a few hundred KB.
    """
    cmd = [
        "xcrun", "clang", "-x", "objective-c",
        "-isysroot", sysroot,
        "-target", triple,
        "-fno-modules",  # modules hide the imported declarations from the dump
        "-fsyntax-only",
        "-Xclang", "-ast-dump=json",
        "-Xclang", "-ast-dump-filter=" + name_filter,
        str(umbrella),
    ]
    out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
    return split_json(out)


def split_json(s: str) -> list[dict]:
    """clang prints one JSON value per matched declaration, concatenated."""
    dec = json.JSONDecoder()
    objs, i = [], 0
    while i < len(s):
        while i < len(s) and s[i].isspace():
            i += 1
        if i >= len(s):
            break
        obj, i = dec.raw_decode(s, i)
        objs.append(obj)
    return objs


class DocIndex:
    """Doc comments, read back out of the headers.

    clang's JSON dump carries no comment nodes, so the text comes from the
    source: walk back from the declaration's start offset over whitespace and
    collect the `///` run (or the `/** */` block) directly above it.
    """

    def __init__(self) -> None:
        self._files: dict[str, str] = {}

    def text(self, path: str) -> str:
        if path not in self._files:
            try:
                self._files[path] = Path(path).read_text(errors="replace")
            except OSError:
                self._files[path] = ""
        return self._files[path]

    def at(self, path: str, offset: int) -> str:
        """The doc comment above the declaration starting at `offset`.

        clang's JSON offsets are 1-based, so the declaration's first character
        is src[offset-1].
        """
        src = self.text(path)
        if not src or offset <= 0 or offset > len(src):
            return ""
        head = src[: offset - 1]
        # Skip back over the whitespace between the comment and the decl.
        j = len(head)
        while j > 0 and head[j - 1] in " \t\r\n":
            j -= 1
        if j == 0:
            return ""
        if head[:j].endswith("*/"):
            k = head.rfind("/*", 0, j)
            if k < 0:
                return ""
            body = head[k + 2 : j - 2]
            lines = [re.sub(r"^\s*\*+ ?", "", ln).strip() for ln in body.splitlines()]
            return "\n".join(ln for ln in lines if ln.strip()).strip()
        lines: list[str] = []
        end = j
        while True:
            start = head.rfind("\n", 0, end) + 1
            line = head[start:end].strip()
            if not line.startswith("///"):
                break
            lines.append(line[3:].strip())
            if start == 0:
                break
            end = start - 1
        return "\n".join(reversed(lines)).strip()


def stamp_files(nodes: list[dict]) -> None:
    """Record the source file of every node, in place, as `wataFile`.

    clang prints `loc.file` only when it changes from the previous node in the
    dump, so a node's file is the last one printed at or before it — which is
    only recoverable by walking the dump in order.
    """
    current = ""

    def walk(node: dict) -> None:
        nonlocal current
        current = (node.get("loc") or {}).get("file") or current
        node["wataFile"] = current
        for child in _children(node):
            walk(child)

    for node in nodes:
        walk(node)


def _doc_for(node: dict, docs: DocIndex) -> str:
    if "wataDoc" in node:  # fixtures carry the extracted doc directly
        return node["wataDoc"]
    off = ((node.get("range") or {}).get("begin") or {}).get("offset")
    file = node.get("wataFile", "")
    if off is None or not file:
        return ""
    return docs.at(file, off)


def _children(node: dict) -> list[dict]:
    return [c for c in node.get("inner", []) if isinstance(c, dict)]


def _has(node: dict, kind: str) -> bool:
    return any(c.get("kind") == kind for c in _children(node))


def _skip(node: dict) -> bool:
    """Deprecated declarations are not worth binding; NS_UNAVAILABLE is handled
    separately, because a refusal there would be noise."""
    return node.get("isImplicit") or _has(node, "DeprecatedAttr")


MANGLED = re.compile(r"^[+-]\[(\w+)[ (]")


def owner_of(node: dict) -> str | None:
    """The class a dumped node contributes members to.

    A class's API is spread over its @interface, its categories, and — when a
    category's own name does not match the dump filter — loose method nodes,
    which name their class in `mangledName`.
    """
    kind = node.get("kind")
    if kind == "ObjCInterfaceDecl":
        return node.get("name")
    if kind == "ObjCCategoryDecl":
        return (node.get("interface") or {}).get("name")
    if kind == "ObjCMethodDecl":
        m = MANGLED.match(node.get("mangledName", ""))
        return m.group(1) if m else None
    return None


def _method(node: dict, docs: DocIndex) -> Method:
    params = [
        Param(name=c.get("name") or f"arg{i}", type=c.get("type", {}))
        for i, c in enumerate(c for c in _children(node) if c.get("kind") == "ParmVarDecl")
    ]
    return Method(
        sel=node["name"],
        instance=bool(node.get("instance")),
        ret=node.get("returnType", {}),
        params=params,
        doc=_doc_for(node, docs),
        unavailable=_has(node, "UnavailableAttr"),
    )


def collect_class(nodes: list[dict], name: str, docs: DocIndex) -> Class:
    """Everything the dump says about class `name`, merged and deduplicated."""
    cls = Class(name=name, super="NSObject")
    seen_methods: set[tuple[bool, str]] = set()
    seen_props: set[str] = set()
    for node in nodes:
        if owner_of(node) != name:
            continue
        if node.get("kind") == "ObjCInterfaceDecl":
            cls.super = (node.get("super") or {}).get("name") or cls.super
            cls.doc = cls.doc or _doc_for(node, docs)
        members = [node] if node.get("kind") == "ObjCMethodDecl" else _children(node)
        for c in members:
            kind = c.get("kind")
            if kind == "ObjCMethodDecl" and not _skip(c):
                key = (bool(c.get("instance")), c["name"])
                if key in seen_methods:
                    continue
                seen_methods.add(key)
                cls.methods.append(_method(c, docs))
            elif kind == "ObjCPropertyDecl" and c["name"] not in seen_props:
                seen_props.add(c["name"])
                cls.props.append(
                    Prop(
                        name=c["name"],
                        type=c.get("type", {}),
                        readonly=bool(c.get("readonly")),
                        doc=_doc_for(c, docs),
                        on_class=bool(c.get("class")),
                    )
                )
    cls.methods.sort(key=lambda m: (not m.instance, m.sel))
    cls.props.sort(key=lambda p: (p.on_class, p.name))
    return cls


def parse_protocol(node: dict, docs: DocIndex) -> Protocol:
    proto = Protocol(name=node["name"], doc=_doc_for(node, docs))
    seen: set[str] = set()
    for c in _children(node):
        if c.get("kind") == "ObjCMethodDecl" and not _skip(c) and c["name"] not in seen:
            seen.add(c["name"])
            proto.methods.append(_method(c, docs))
    proto.methods.sort(key=lambda m: m.sel)
    return proto


def collect_struct(nodes: list[dict], name: str, docs: DocIndex) -> Struct | None:
    """The record declaration behind the struct name declarations use.

    Two shapes in the SDK headers: `typedef struct _NSRange {…} NSRange;`
    (a named record; the typedef's underlying type spells its name) and
    `typedef struct {…} NSOperatingSystemVersion;` (an anonymous record,
    which clang dumps immediately before the typedef that names it).
    A bare `struct name {…}` without a typedef is also accepted.
    """
    records: dict[str, dict] = {}
    last_anon: dict | None = None
    typedef: dict | None = None
    for n in nodes:
        if n.get("kind") == "RecordDecl" and n.get("completeDefinition"):
            if n.get("name"):
                records[n["name"]] = n
            else:
                last_anon = n
        if n.get("kind") == "TypedefDecl" and n.get("name") == name and typedef is None:
            under = (n.get("type") or {}).get("qualType", "")
            if under.startswith(("struct ", "union ")):
                typedef = n
                record_node = records.get(under.split(" ", 1)[1]) or last_anon
                record_spelling = under
    if typedef is None:
        record_node = records.get(name)
        record_spelling = f"struct {name}"
    if record_node is None:
        return None
    st = Struct(
        name=name,
        record=record_spelling,
        union=record_node.get("tagUsed") == "union",
        doc=_doc_for(typedef or record_node, docs) or _doc_for(record_node, docs),
    )
    for c in _children(record_node):
        if c.get("kind") != "FieldDecl":
            continue
        st.fields.append(
            StructField(
                name=c.get("name") or "",
                type=c.get("type", {}),
                bitfield=bool(c.get("isBitfield")),
                doc=_doc_for(c, docs),
            )
        )
    return st


def parse_enum(node: dict, docs: DocIndex) -> Enum:
    under = (node.get("fixedUnderlyingType") or {}).get("desugaredQualType", "int")
    enum = Enum(name=node["name"], underlying=under, doc=_doc_for(node, docs))
    nxt = 0
    for c in _children(node):
        if c.get("kind") != "EnumConstantDecl":
            continue
        val = _const_value(c)
        if val is None:
            val = nxt
        nxt = val + 1
        enum.consts.append((c["name"], val, _doc_for(c, docs)))
    return enum


def _const_value(node: dict) -> int | None:
    """The initializer of an enum constant, if it has one.

    NS_OPTIONS members are written as shifts, so the value comes off whichever
    node in the initializer clang folded to a constant.
    """
    stack = list(_children(node))
    while stack:
        n = stack.pop(0)
        v = n.get("value")
        if v is not None:
            try:
                return int(v)
            except ValueError:
                pass
        stack.extend(_children(n))
    return None


def load(target: Target, umbrella: Path, docs: DocIndex) -> dict:
    """Run clang once per allowlisted name and build the IR."""
    sysroot = sdk_path(target.sdk)
    ir: dict = {
        "package": target.package,
        "classes": [], "protocols": [], "enums": [], "opaque": [], "structs": [],
    }
    wanted = {
        "class": target.classes + target.opaque,
        "protocol": target.protocols,
        "enum": target.enums,
        "struct": target.structs,
    }
    for kind, names in wanted.items():
        for name in names:
            nodes = ast_dump(umbrella, sysroot, target.triple, name)
            stamp_files(nodes)
            if kind == "struct":
                st = collect_struct(nodes, name, docs)
                if st is None:
                    raise SystemExit(f"bindgen: struct {name!r} not found in the {target.sdk} SDK")
                ir["structs"].append(_asdict(st))
                continue
            if kind == "class":
                cls = collect_class(nodes, name, docs)
                if not cls.methods and not cls.props and name not in target.opaque:
                    raise SystemExit(f"bindgen: class {name!r} has no members in the {target.sdk} SDK")
                if name in target.opaque:
                    cls.methods, cls.props = [], []
                    ir["opaque"].append(_asdict(cls))
                else:
                    ir["classes"].append(_asdict(cls))
                continue
            picked = _pick(nodes, name, kind)
            if picked is None:
                raise SystemExit(f"bindgen: {kind} {name!r} not found in the {target.sdk} SDK")
            if kind == "protocol":
                ir["protocols"].append(_asdict(parse_protocol(picked, docs)))
            else:
                ir["enums"].append(_asdict(parse_enum(picked, docs)))
    return ir


def _pick(nodes: list[dict], name: str, kind: str) -> dict | None:
    """The real definition among a filter's matches.

    A substring filter also returns forward declarations (no members) and
    unrelated names; the definition is the last node with the exact name, the
    right kind, and members.
    """
    want = {"protocol": "ObjCProtocolDecl", "enum": "EnumDecl"}[kind]
    best = None
    for n in nodes:
        if n.get("kind") != want or n.get("name") != name:
            continue
        if _children(n):
            best = n
    return best


def _asdict(obj) -> dict:
    import dataclasses

    return dataclasses.asdict(obj)


def _from_ir(ir: dict) -> tuple[list[Class], list[Protocol], list[Enum], list[Class], list[Struct]]:
    def cls(d: dict) -> Class:
        return Class(
            name=d["name"],
            super=d["super"],
            doc=d.get("doc", ""),
            methods=[_m(m) for m in d.get("methods", [])],
            props=[Prop(**p) for p in d.get("props", [])],
        )

    def _m(d: dict) -> Method:
        return Method(
            sel=d["sel"],
            instance=d["instance"],
            ret=d["ret"],
            params=[Param(**p) for p in d["params"]],
            doc=d.get("doc", ""),
            unavailable=d.get("unavailable", False),
        )

    classes = [cls(d) for d in ir.get("classes", [])]
    protos = [
        Protocol(name=d["name"], doc=d.get("doc", ""), methods=[_m(m) for m in d.get("methods", [])])
        for d in ir.get("protocols", [])
    ]
    enums = [
        Enum(
            name=d["name"],
            underlying=d["underlying"],
            doc=d.get("doc", ""),
            consts=[tuple(c) for c in d.get("consts", [])],
        )
        for d in ir.get("enums", [])
    ]
    opaque = [cls(d) for d in ir.get("opaque", [])]
    structs = [
        Struct(
            name=d["name"],
            record=d["record"],
            union=d.get("union", False),
            doc=d.get("doc", ""),
            fields=[StructField(**f) for f in d.get("fields", [])],
        )
        for d in ir.get("structs", [])
    ]
    return classes, protos, enums, opaque, structs


# ── the type mapper ───────────────────────────────────────────────────────────

NULLABILITY = re.compile(
    r"\b(_Nonnull|_Nullable|_Null_unspecified|__kindof|__strong|__weak|__autoreleasing|const|volatile)\b"
)

PRIMITIVES = {
    "void": ("", ""),
    "BOOL": ("bool", "bool"),
    "bool": ("bool", "bool"),
    "_Bool": ("bool", "bool"),
    "signed char": ("int8", "int8"),
    "char": ("int8", "int8"),
    "unsigned char": ("uint8", "uint8"),
    "short": ("int16", "int16"),
    "unsigned short": ("uint16", "uint16"),
    "int": ("int32", "int32"),
    "unsigned int": ("uint32", "uint32"),
    "long": ("int", "int"),
    "unsigned long": ("uint", "uint"),
    "long long": ("int64", "int64"),
    "unsigned long long": ("uint64", "uint64"),
    "float": ("float32", "float32"),
    "double": ("float64", "float64"),
}

# Typedefs that appear inside block signatures, where clang gives no desugaring.
TYPEDEFS = {
    "NSInteger": "long",
    "NSUInteger": "unsigned long",
    "CGFloat": "double",
    "NSTimeInterval": "double",
    "BOOL": "BOOL",
}

OBJ_PTR = re.compile(r"^(\w+)\s*(<[^<>]*>)?\s*\*$")


@dataclass
class GoType:
    """A mapped type: how it is spelled in Go and how it crosses the ABI."""

    go: str  # the Go type in a wrapper signature
    abi: str  # the Go type handed to / received from objc.Send
    to_objc: str = "{}"  # Go expression template: wrapper value -> ABI value
    from_objc: str = "{}"  # Go expression template: ABI value -> wrapper value
    is_void: bool = False
    is_error_out: bool = False
    is_struct: bool = False  # a C struct passed/returned by value
    needs_objcrt: bool = False
    block: "BlockType | None" = None

    def into(self, expr: str) -> str:
        return self.to_objc.format(expr)

    def outof(self, expr: str) -> str:
        return self.from_objc.format(expr)


@dataclass
class BlockType:
    ret: GoType
    params: list[GoType]


VOID = GoType(go="", abi="", is_void=True)


class Mapper:
    def __init__(
        self,
        classes: set[str],
        enums: dict[str, str],
        opaque: set[str],
        structs: dict[str, str] | None = None,
        bad_structs: dict[str, tuple[str, str]] | None = None,
    ):
        self.classes = classes
        self.enums = enums  # name -> Go underlying type
        self.opaque = opaque
        # Both keyed by every spelling a use site shows: the typedef name
        # (NSRange) and the desugared record (struct _NSRange).
        self.structs = structs or {}  # spelling -> Go type name
        self.bad_structs = bad_structs or {}  # spelling -> (name, reason)

    def known_class(self, name: str) -> bool:
        return name in self.classes or name in self.opaque

    def clean(self, s: str) -> str:
        s = NULLABILITY.sub(" ", s)
        s = re.sub(r"\s+", " ", s).strip()
        return re.sub(r"\s+\*", " *", s)

    def map(self, t: dict | str, *, self_class: str | None = None, out_param: bool = False) -> GoType:
        if isinstance(t, str):
            t = {"qualType": t}
        q = self.clean(t.get("qualType", ""))
        d = self.clean(t.get("desugaredQualType", "") or q)

        if q == "void":
            return VOID
        if "(^" in q or "(^" in d:
            return self.block(q if "(^" in q else d, self_class)
        if q in ("NSError * *", "NSError **"):
            if not out_param:
                raise Unmappable("NSError ** is only mapped as a trailing out-parameter")
            return GoType(go="error", abi="unsafe.Pointer", is_error_out=True, needs_objcrt=True)
        if q in ("instancetype", "instancetype *") or d == "instancetype":
            if self_class is None:
                raise Unmappable("instancetype outside a class")
            return self.wrapper(self_class)
        if q == "id" or d == "id" or re.match(r"^id\s*<[^<>]*>$", q):
            return GoType(go="objc.ID", abi="objc.ID")
        if q == "Class":
            return GoType(go="objc.Class", abi="objc.Class")
        if q == "SEL":
            return GoType(go="objc.SEL", abi="objc.SEL")

        for cand in (q, d):
            if cand in self.structs:
                name = self.structs[cand]
                return GoType(go=name, abi=name, is_struct=True)
            if cand in self.bad_structs:
                name, reason = self.bad_structs[cand]
                raise Unmappable(f"struct {name} is refused: {reason}")

        for cand in (q, d, q.removeprefix("enum "), d.removeprefix("enum ")):
            if cand in self.enums:
                under = self.enums[cand]
                return GoType(
                    go=cand,
                    abi=under,
                    to_objc=under + "({})",
                    from_objc=cand + "({})",
                )

        m = OBJ_PTR.match(q) or OBJ_PTR.match(d)
        if m and m.group(1) not in ("void", "char", "unsigned", "signed", "int", "float", "double"):
            name = m.group(1)
            if name == "NSString":
                return GoType(
                    go="string",
                    abi="objc.ID",
                    to_objc="objcrt.NSString({})",
                    from_objc="objcrt.GoString({})",
                    needs_objcrt=True,
                )
            if name == "NSError":
                return GoType(
                    go="error",
                    abi="objc.ID",
                    to_objc="objcrt.NSErrorID({})",
                    from_objc="objcrt.GoError({})",
                    needs_objcrt=True,
                )
            if self.known_class(name):
                return self.wrapper(name)
            raise Unmappable(f"{name} is not on the allowlist (add it to classes or opaque)")

        for cand in (q, d, TYPEDEFS.get(q, ""), TYPEDEFS.get(d, "")):
            if cand in PRIMITIVES:
                go, abi = PRIMITIVES[cand]
                return GoType(go=go, abi=abi)

        for cand in (d, q):
            if cand.endswith("*"):
                continue
            if cand.startswith("struct "):
                raise Unmappable(
                    f"{cand} passed by value is not on the allowlist (add it to structs)"
                )
            if cand.startswith("union "):
                raise Unmappable(f"{cand} passed by value (Go cannot express a C union)")

        raise Unmappable(f"no Go mapping for {t.get('qualType', '?')!r}")

    def wrapper(self, name: str) -> GoType:
        return GoType(go=name, abi="objc.ID", to_objc="{}.ID", from_objc=name + "{{{}}}")

    def block(self, sig: str, self_class: str | None) -> GoType:
        m = re.match(r"^(.*?)\s*\(\^\s*\)\s*\((.*)\)$", sig)
        if not m:
            raise Unmappable(f"unparsed block signature {sig!r}")
        ret_s, args_s = m.group(1), m.group(2)
        ret = self.map(ret_s, self_class=self_class)
        args = [] if args_s.strip() in ("", "void") else _split_args(args_s)
        params = [self.map(a, self_class=self_class) for a in args]
        if any(p.block for p in params) or ret.block:
            raise Unmappable("nested block")
        if any(p.is_struct for p in params) or ret.is_struct:
            # Both block directions ride on purego callbacks, which reject
            # struct arguments; a by-value struct cannot cross a block yet.
            raise Unmappable("struct in a block signature")
        go = "func(" + ", ".join(p.go for p in params) + ")"
        if not ret.is_void:
            go += " " + ret.go
        return GoType(go=go, abi="objc.Block", block=BlockType(ret=ret, params=params))


def _split_args(s: str) -> list[str]:
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return [a.strip() for a in out]


# ── naming ────────────────────────────────────────────────────────────────────

RESERVED = {
    "break", "case", "chan", "const", "continue", "default", "defer", "else",
    "fallthrough", "for", "func", "go", "goto", "if", "import", "interface",
    "map", "package", "range", "return", "select", "struct", "switch", "type",
    "var", "len", "cap", "new", "make", "copy", "string", "error", "type",
}


def cap(name: str) -> str:
    return name[:1].upper() + name[1:]


def go_name(sel: str) -> str:
    parts = [p for p in sel.split(":") if p]
    return "".join(cap(p) for p in parts)


def sel_var(sel: str) -> str:
    return "sel" + go_name(sel)


def arg_name(name: str, i: int) -> str:
    n = name or f"arg{i}"
    n = n[:1].lower() + n[1:]
    if n in RESERVED:
        n += "_"
    return n


def handle_name(params: list[GoType]) -> str:
    """The name of the per-signature incoming-block handle type.

    Derived from the Go parameter types, so one signature is one type and two
    callbacks sharing a signature share the handle: `void (^)(void)` is
    IncomingBlockVoid, `void (^)(double, NSString *)` IncomingBlockFloat64String.
    """
    if not params:
        return "IncomingBlockVoid"
    parts = []
    for t in params:
        n = t.go.split(".")[-1]  # objc.ID -> ID
        parts.append(n[:1].upper() + n[1:])
    return "IncomingBlock" + "".join(parts)


def godoc(name: str, doc: str, fallback: str) -> list[str]:
    lines = [f"// {name} {fallback}"] if not doc else [f"// {name} — {doc.splitlines()[0]}"]
    if doc:
        for extra in doc.splitlines()[1:]:
            lines.append("//" + (" " + extra if extra else ""))
    return lines


# ── stage 2: IR -> Go ─────────────────────────────────────────────────────────


class Emitter:
    def __init__(self, ir: dict, provenance: str = ""):
        self.package = ir["package"]
        self.provenance = provenance
        self.classes, self.protocols, self.enums, self.opaque, structs = _from_ir(ir)
        self.refusals: list[Refusal] = []
        self.selectors: dict[str, str] = {}
        self.blocks: dict[str, list[GoType]] = {}  # handle type name -> block params
        enum_types = {e.name: PRIMITIVES.get(e.underlying, ("int", "int"))[0] for e in self.enums}
        good, bad = self._validate_structs(structs)
        self.structs = good  # Go type name -> (Struct, [(go field, go type)])
        self.m = Mapper(
            classes={c.name for c in self.classes},
            enums=enum_types,
            opaque={c.name for c in self.opaque},
            structs={sp: st.name for st, _ in good.values() for sp in (st.name, st.record)},
            bad_structs={
                sp: (st.name, reason) for st, reason in bad for sp in (st.name, st.record)
            },
        )

    def _validate_structs(
        self, structs: list[Struct]
    ) -> tuple[dict[str, tuple[Struct, list[tuple[str, str]]]], list[tuple[Struct, str]]]:
        """Split the allowlisted structs into mappable and refused.

        A struct maps when every field is a mappable primitive or another
        allowlisted (and itself mappable) struct — that is what purego's
        AAPCS64 classification can carry. Bitfields, unions and array fields
        have no Go spelling with the same layout, so the struct is refused
        once and every declaration using it points at that reason. Nesting
        order is free (the allowlist is sorted), hence the fixed point.
        """
        good: dict[str, tuple[Struct, list[tuple[str, str]]]] = {}
        bad: list[tuple[Struct, str]] = []
        clean = Mapper(classes=set(), enums={}, opaque=set()).clean
        spellings: dict[str, str] = {}  # use-site spelling -> Go type name

        def field_type(f: StructField) -> str | None:
            q = clean(f.type.get("qualType", ""))
            d = clean(f.type.get("desugaredQualType", "") or q)
            for cand in (q, d):
                if cand in spellings:
                    return spellings[cand]
            for cand in (q, d, TYPEDEFS.get(q, ""), TYPEDEFS.get(d, "")):
                if cand in PRIMITIVES and PRIMITIVES[cand][0]:
                    return PRIMITIVES[cand][0]
            return None

        def hard_reason(st: Struct) -> str | None:
            if st.union:
                return "a C union has no Go spelling"
            if not st.fields:
                return "no fields (an opaque record cannot cross by value)"
            for f in st.fields:
                if f.bitfield:
                    return f"field {f.name} is a bitfield"
                if "[" in f.type.get("qualType", ""):
                    return f"field {f.name} is an array"
            return None

        pending = list(structs)
        while pending:
            progress = False
            still: list[Struct] = []
            for st in pending:
                reason = hard_reason(st)
                if reason:
                    bad.append((st, reason))
                    progress = True
                    continue
                fields = [(cap(f.name), field_type(f)) for f in st.fields]
                if all(t for _, t in fields):
                    good[st.name] = (st, [(n, t) for n, t in fields if t])
                    spellings[st.name] = st.name
                    spellings[st.record] = st.name
                    progress = True
                else:
                    still.append(st)
            pending = still
            if not progress:
                break
        for st in pending:  # fields that never resolved: name the first one
            f = next(f for f in st.fields if field_type(f) is None)
            bad.append((st, f"field {f.name} ({f.type.get('qualType', '?')}) has no Go mapping"))
        for st, reason in bad:
            self.refusals.append(Refusal(st.name, f"struct {st.name}", reason))
        return good, bad

    # -- helpers

    def sel(self, s: str) -> str:
        self.selectors[s] = sel_var(s)
        return sel_var(s)

    def header(self, imports: list[str]) -> str:
        out = [
            "// Code generated by tools/bindgen. DO NOT EDIT.",
            "//",
        ]
        if self.provenance:
            for ln in self.provenance.splitlines():
                out.append("// " + ln)
            out.append("//")
        out.append("// Regenerate with: tools/bindgen/regen.sh")
        out.append("")
        out.append("//go:build darwin")
        out.append("")
        out.append(f"package {self.package}")
        if imports:
            out.append("")
            if len(imports) == 1:
                out.append(f'import "{imports[0]}"')
            else:
                out.append("import (")
                for i in sorted(imports):
                    out.append(f'\t"{i}"')
                out.append(")")
        out.append("")
        return "\n".join(out)

    # -- enums

    def emit_enums(self) -> str:
        body: list[str] = []
        for e in sorted(self.enums, key=lambda x: x.name):
            go = PRIMITIVES.get(e.underlying, ("int", "int"))[0]
            body += godoc(e.name, e.doc, f"is the ObjC enum {e.name} ({e.underlying}).")
            body.append(f"type {e.name} {go}")
            body.append("")
            if e.consts:
                body.append("const (")
                for name, val, doc in e.consts:
                    for ln in doc.splitlines():
                        body.append(f"\t// {ln}")
                    body.append(f"\t{name} {e.name} = {val}")
                body.append(")")
                body.append("")
        if not body:
            return ""
        return self.header([]) + "\n".join(body) + "\n"

    # -- structs

    def emit_structs(self) -> str:
        """One Go struct per mappable allowlisted C struct, layout order kept.

        The field order and widths are the ABI: purego classifies the Go
        struct exactly as AAPCS64 classifies the C one, so the declaration
        must mirror the record. No imports — these are plain value types.
        """
        body: list[str] = []
        for name in sorted(self.structs):
            st, fields = self.structs[name]
            body += godoc(name, st.doc, f"is the C struct {st.name} ({st.record}), by value.")
            body.append(f"type {name} struct {{")
            for (goname, gotype), f in zip(fields, st.fields):
                for ln in f.doc.splitlines():
                    body.append(f"\t// {ln}")
                body.append(f"\t{goname} {gotype}")
            body.append("}")
            body.append("")
        if not body:
            return ""
        return self.header([]) + "\n".join(body) + "\n"

    # -- selectors

    def emit_selectors(self) -> str:
        if not self.selectors:
            return ""
        body = ["// Selectors, registered once at package init.", "var ("]
        for s in sorted(self.selectors):
            body.append(f'\t{self.selectors[s]} = objc.RegisterName("{s}")')
        body.append(")")
        body.append("")
        return self.header([PUREGO_OBJC]) + "\n".join(body) + "\n"

    # -- classes

    def emit_class(self, cls: Class, opaque: bool = False) -> tuple[str, set[str]]:
        imports = {PUREGO_OBJC}
        body: list[str] = []
        kind = "an opaque handle for" if opaque else "wraps"
        body += godoc(cls.name, cls.doc, f"{kind} the ObjC class {cls.name} ({cls.super} subclass).")
        if opaque:
            body.append(
                f"// Only the identity crosses: no method of {cls.name} is on the allowlist."
            )
        body.append(f"type {cls.name} struct{{ objc.ID }}")
        body.append("")
        body.append(f"// {cls.name}Class is the class object of {cls.name}.")
        body.append(f"type {cls.name}Class struct{{ objc.Class }}")
        body.append("")
        body.append(f"// Get{cls.name}Class looks the class up in the ObjC runtime.")
        body.append(
            f'func Get{cls.name}Class() {cls.name}Class {{ return {cls.name}Class{{objc.GetClass("{cls.name}")}} }}'
        )
        body.append("")
        if not opaque:
            body.append(f"// Alloc allocates an uninitialized {cls.name} (-[NSObject alloc]).")
            body.append(
                f"func (c {cls.name}Class) Alloc() {cls.name} {{ "
                f"return {cls.name}{{objc.ID(c.Class).Send({self.sel('alloc')})}} }}"
            )
            body.append("")

        taken = {True: {"Alloc"}, False: set()}
        for p in cls.props:
            body += self.emit_prop(cls, p, imports, taken[p.on_class])
        for meth in cls.methods:
            body += self.emit_method(cls, meth, imports, taken[not meth.instance])
        return self.header(sorted(imports)) + "\n".join(body).rstrip() + "\n", imports

    def emit_prop(self, cls: Class, p: Prop, imports: set[str], taken: set[str]) -> list[str]:
        try:
            t = self.m.map(p.type, self_class=cls.name)
        except Unmappable as exc:
            self.refusals.append(Refusal(cls.name, f"@property {p.name}", str(exc)))
            return []
        out: list[str] = []
        getter = go_name(p.name)
        if getter in taken:
            self.refusals.append(Refusal(cls.name, f"@property {p.name}", f"Go name {getter} taken"))
            return []
        taken.add(getter)
        self._note_imports(t, imports)
        recv = f"c {cls.name}Class" if p.on_class else f"o {cls.name}"
        target = "objc.ID(c.Class)" if p.on_class else "o.ID"
        kind = "class property" if p.on_class else "property"
        out += godoc(getter, p.doc, f"reads the {cls.name} {kind} {p.name}.")
        out.append(f"func ({recv}) {getter}() {t.go} {{")
        out.append(f"\treturn {t.outof(self._send(t, target, self.sel(p.name), []))}")
        out.append("}")
        out.append("")
        if not p.readonly:
            setter = "Set" + getter
            sel = f"set{p.name[:1].upper()}{p.name[1:]}:"
            taken.add(setter)
            out.append(f"// {setter} writes the {cls.name} {kind} {p.name}.")
            out.append(f"func ({recv}) {setter}(v {t.go}) {{")
            out.append(f"\t{target}.Send({self.sel(sel)}, {t.into('v')})")
            out.append("}")
            out.append("")
        return out

    def emit_method(self, cls: Class, meth: Method, imports: set[str], taken: set[str]) -> list[str]:
        if meth.unavailable:
            return []  # NS_UNAVAILABLE: the SDK says it is not callable
        name = go_name(meth.sel)
        if name in taken:
            self.refusals.append(Refusal(cls.name, meth.sel, f"Go name {name} taken"))
            return []
        try:
            sig = self.signature(meth, cls.name)
        except Unmappable as exc:
            self.refusals.append(Refusal(cls.name, meth.sel, str(exc)))
            return []
        taken.add(name)
        for t in sig["types"]:
            self._note_imports(t, imports)
        recv = f"o {cls.name}" if meth.instance else f"c {cls.name}Class"
        target = "o.ID" if meth.instance else "objc.ID(c.Class)"
        arrow = "-" if meth.instance else "+"
        doc = godoc(name, meth.doc, f"calls {arrow}[{cls.name} {meth.sel}].")
        return self.body(doc, f"func ({recv}) {name}", sig, target, meth.sel, imports)

    def signature(self, meth: Method, self_class: str | None) -> dict:
        params: list[tuple[str, GoType]] = []
        err_out = False
        for i, p in enumerate(meth.params):
            last = i == len(meth.params) - 1
            t = self.m.map(p.type, self_class=self_class, out_param=last)
            if t.is_error_out:
                err_out = True
                continue
            params.append((arg_name(p.name, i), t))
        ret = self.m.map(meth.ret, self_class=self_class)
        return {
            "params": params,
            "ret": ret,
            "err": err_out,
            "types": [t for _, t in params] + [ret],
        }

    def body(
        self,
        doc: list[str],
        head: str,
        sig: dict,
        target: str,
        sel: str,
        imports: set[str],
    ) -> list[str]:
        params, ret, err = sig["params"], sig["ret"], sig["err"]
        args = ", ".join(f"{n} {t.go}" for n, t in params)
        results: list[str] = []
        if not ret.is_void:
            results.append(ret.go)
        if err:
            results.append("error")
        res = ""
        if len(results) == 1:
            res = " " + results[0]
        elif results:
            res = " (" + ", ".join(results) + ")"

        out = list(doc)
        out.append(f"{head}({args}){res} {{")
        call_args = [self.sel(sel)]
        for n, t in params:
            if t.block:
                imports.add(PUREGO_OBJC)
                out.append(f"\tblock{go_name(n)} := {self.block_literal(t, n)}")
                out.append(f"\tdefer block{go_name(n)}.Release()")
                call_args.append(f"block{go_name(n)}")
            else:
                call_args.append(t.into(n))
        if err:
            imports.add(OBJCRT)
            out.append("\tvar errOut objcrt.ErrOut")
            call_args.append("errOut.Ptr()")
        call = self._send(ret, target, call_args[0], call_args[1:])
        if ret.is_void:
            out.append("\t" + call)
            if err:
                out.append("\treturn errOut.Err()")
        elif err:
            out.append(f"\tret := {call}")
            out.append(f"\treturn {ret.outof('ret')}, errOut.Err()")
        else:
            out.append(f"\treturn {ret.outof(call)}")
        out.append("}")
        out.append("")
        return out

    def block_literal(self, t: GoType, name: str) -> str:
        b = t.block
        assert b is not None
        args = [f"a{i} {p.abi}" for i, p in enumerate(b.params)]
        head = "objc.NewBlock(func(_ objc.Block" + ("".join(", " + a for a in args)) + ")"
        if not b.ret.is_void:
            head += " " + b.ret.abi
        inner = ", ".join(p.outof(f"a{i}") for i, p in enumerate(b.params))
        if b.ret.is_void:
            return head + " {\n\t\t" + f"{name}({inner})" + "\n\t})"
        return head + " {\n\t\t" + f"return {b.ret.into(name + '(' + inner + ')')}" + "\n\t})"

    def _send(self, ret: GoType, target: str, sel: str, args: list[str]) -> str:
        joined = "".join(", " + a for a in args)
        if ret.is_void:
            return f"{target}.Send({sel}{joined})"
        if ret.abi == "objc.ID":
            return f"{target}.Send({sel}{joined})"
        return f"objc.Send[{ret.abi}]({target}, {sel}{joined})"

    def _note_imports(self, t: GoType, imports: set[str]) -> None:
        if t.needs_objcrt or t.to_objc.startswith("objcrt") or t.from_objc.startswith("objcrt"):
            imports.add(OBJCRT)
        if t.is_error_out:
            imports.add(OBJCRT)
        if t.block:
            for p in t.block.params:
                self._note_imports(p, imports)
            self._note_imports(t.block.ret, imports)

    # -- protocols

    def emit_protocol(self, proto: Protocol) -> str:
        imports = {PUREGO_OBJC, OBJCRT}
        body: list[str] = []
        body += godoc(
            proto.name, proto.doc, f"is the Go side of the ObjC protocol {proto.name}."
        )
        body.append("//")
        body.append("// A nil field is not installed on the synthesized class, which is exactly")
        body.append("// ObjC's optional-method semantics: -respondsToSelector: answers NO for it.")
        body.append(f"type {proto.name} struct {{")
        installs: list[list[str]] = []
        for meth in proto.methods:
            name = go_name(meth.sel)
            try:
                sig = self.signature(meth, None)
            except Unmappable as exc:
                self.refusals.append(Refusal(proto.name, meth.sel, str(exc)))
                continue
            if sig["err"]:
                self.refusals.append(
                    Refusal(proto.name, meth.sel, "NSError ** out-parameter in a callback")
                )
                continue
            if sig["ret"].block:
                self.refusals.append(
                    Refusal(proto.name, meth.sel, "callback returning a block")
                )
                continue
            if any(t.is_struct for t in sig["types"]):
                self.refusals.append(
                    Refusal(
                        proto.name, meth.sel,
                        "struct in a callback signature (purego callbacks cannot carry structs)",
                    )
                )
                continue
            try:
                sig["params"] = [
                    (n, self.incoming_block(t) if t.block else t) for n, t in sig["params"]
                ]
            except Unmappable as exc:
                self.refusals.append(Refusal(proto.name, meth.sel, str(exc)))
                continue
            sig["types"] = [t for _, t in sig["params"]] + [sig["ret"]]
            for t in sig["types"]:
                self._note_imports(t, imports)
            args = ", ".join(f"{n} {t.go}" for n, t in sig["params"])
            ret = "" if sig["ret"].is_void else " " + sig["ret"].go
            for ln in meth.doc.splitlines():
                body.append(f"\t// {ln}")
            body.append(f"\t// -[{proto.name} {meth.sel}]")
            body.append(f"\t{name} func({args}){ret}")
            installs.append(self.install(proto, meth, sig, name))
        body.append("}")
        body.append("")
        body.append(f"// New{proto.name} synthesizes an ObjC object conforming to {proto.name}")
        body.append("// that forwards to d's non-nil funcs. The object is retained forever: the")
        body.append("// ObjC runtime cannot unregister a class.")
        body.append(f"func New{proto.name}(d {proto.name}) objc.ID {{")
        body.append("\tvar ms []objcrt.Method")
        for ins in installs:
            body += ins
        body.append(
            f'\treturn objcrt.NewDelegate("Wata{proto.name}", []string{{"{proto.name}"}}, ms)'
        )
        body.append("}")
        body.append("")
        return self.header(sorted(imports)) + "\n".join(body).rstrip() + "\n"

    def incoming_block(self, t: GoType) -> GoType:
        """The handle type standing in for a block received by a callback.

        The block would outlive the call, so the trampoline copies it
        (objcrt.CopyBlock) into a per-signature handle type with a typed Call
        and a Release. Only void-returning blocks are mapped: every completion
        handler is one, and a non-void return would make Call's ABI story
        bigger than anything needs yet.
        """
        b = t.block
        assert b is not None
        if not b.ret.is_void:
            raise Unmappable("incoming block with a non-void return")
        name = handle_name(b.params)
        self.blocks[name] = b.params
        return GoType(
            go=name,
            abi="objc.Block",
            from_objc=name + "{{objcrt.CopyBlock({})}}",
            needs_objcrt=True,
        )

    def emit_blocks(self) -> str:
        imports = {OBJCRT}
        body: list[str] = []
        for name in sorted(self.blocks):
            params = self.blocks[name]
            for t in params:
                self._note_imports(t, imports)
                if t.go.startswith("objc."):
                    imports.add(PUREGO_OBJC)
            args = ", ".join(f"a{i} {t.go}" for i, t in enumerate(params))
            call = ", ".join(t.into(f"a{i}") for i, t in enumerate(params))
            body.append(f"// {name} is an ObjC block received by a callback, copied")
            body.append("// (_Block_copy) so it can be invoked after the callback returns — from any")
            body.append("// goroutine. Release it exactly once when done: a second Release, or a Call")
            body.append("// after Release, panics. Call may repeat while the handle is live.")
            body.append(f"type {name} struct{{ h *objcrt.BlockHandle }}")
            body.append("")
            body.append("// Call invokes the block through its own invoke pointer.")
            body.append(f"func (b {name}) Call({args}) {{ b.h.Invoke({call}) }}")
            body.append("")
            body.append("// Release frees the copied block (_Block_release).")
            body.append(f"func (b {name}) Release() {{ b.h.Release() }}")
            body.append("")
        return self.header(sorted(imports)) + "\n".join(body).rstrip() + "\n"

    def install(self, proto: Protocol, meth: Method, sig: dict, name: str) -> list[str]:
        params = sig["params"]
        ret = sig["ret"]
        args = ", ".join(f"a{i} {t.abi}" for i, (_, t) in enumerate(params))
        head = f"\t\t\tFn: func(_ objc.ID, _ objc.SEL{''.join(', ' + a for a in [args] if a)})"
        if not ret.is_void:
            head += " " + ret.abi
        call = f"d.{name}(" + ", ".join(t.outof(f"a{i}") for i, (_, t) in enumerate(params)) + ")"
        out = [f"\tif d.{name} != nil {{", "\t\tms = append(ms, objcrt.Method{"]
        out.append(f'\t\t\tSel: "{meth.sel}",')
        out.append(head + " {")
        out.append("\t\t\t\t" + (call if ret.is_void else f"return {ret.into(call)}"))
        out.append("\t\t\t},")
        out.append("\t\t})")
        out.append("\t}")
        return out

    # -- everything

    def run(self) -> dict[str, str]:
        files: dict[str, str] = {}
        for cls in sorted(self.classes, key=lambda c: c.name):
            src, _ = self.emit_class(cls)
            files[cls.name.lower() + ".go"] = src
        for cls in sorted(self.opaque, key=lambda c: c.name):
            src, _ = self.emit_class(cls, opaque=True)
            files[cls.name.lower() + ".go"] = src
        for proto in sorted(self.protocols, key=lambda p: p.name):
            files[proto.name.lower() + ".go"] = self.emit_protocol(proto)
        if self.blocks:
            files["blocks.go"] = self.emit_blocks()
        structs = self.emit_structs()
        if structs:
            files["structs.go"] = structs
        enums = self.emit_enums()
        if enums:
            files["enums.go"] = enums
        sels = self.emit_selectors()
        if sels:
            files["selectors.go"] = sels
        return {k: gofmt(v) for k, v in sorted(files.items())}

    def refusal_doc(self) -> str:
        out = [
            f"# {self.package}: refused declarations",
            "",
            "Generated by `tools/bindgen/regen.sh`; do not edit.",
            "",
            "Every allowlisted declaration this generator could not express in Go,",
            "with the reason. This is the worklist: a refusal is either a mapping the",
            "generator should learn or a decl the allowlist should stop asking for.",
            "",
        ]
        if not self.refusals:
            out.append("Nothing refused.")
        for r in sorted(self.refusals, key=lambda r: (r.owner, r.decl)):
            out.append(r.line())
        return "\n".join(out) + "\n"


def gofmt(src: str) -> str:
    """Formatting is gofmt's job, not the emitter's — and it type-checks the syntax."""
    if not shutil.which("gofmt"):
        raise SystemExit("bindgen: gofmt not on PATH; generated Go must be gofmt-formatted")
    r = subprocess.run(["gofmt"], input=src, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit("gofmt rejected generated source:\n" + r.stderr + "\n" + src)
    return r.stdout


# ── verification ──────────────────────────────────────────────────────────────


def verify(modules: set[str]) -> None:
    """Prove the generated Go is buildable, on this Mac and for the phone.

    `go vet` on the host covers everything the compiler and vet can see;
    GOOS=ios GOARCH=arm64 is the leg that matters — the bindings only pay off
    if they compile for the device. Both are cheap, so regeneration always runs
    them: generated code that does not build is not generated code.
    """
    roots = set()
    for out in modules:
        mod = REPO / out
        while not (mod / "go.mod").exists() and mod != REPO:
            mod = mod.parent
        roots.add(mod)
    for mod in sorted(roots):
        env = dict(os.environ, GOWORK="off", GOFLAGS="-mod=mod")
        stale = subprocess.run(
            ["gofmt", "-l", "."], cwd=mod, capture_output=True, text=True, check=True
        ).stdout.strip()
        if stale:
            raise SystemExit(f"bindgen: not gofmt-clean:\n{stale}")
        _run(["go", "vet", "./..."], mod, env, "go vet")
        sdk = sdk_path("iphoneos")
        ios = dict(
            env,
            GOOS="ios",
            GOARCH="arm64",
            CGO_ENABLED="1",
            CC=subprocess.run(
                ["xcrun", "--sdk", "iphoneos", "-f", "clang"],
                capture_output=True, text=True, check=True,
            ).stdout.strip(),
            CGO_CFLAGS=f"-isysroot {sdk} -target arm64-apple-ios16.0",
        )
        _run(["go", "build", "./..."], mod, ios, "ios/arm64 build")
        print(f"bindgen: {mod.name}: gofmt, go vet and the ios/arm64 build are clean")


def _run(cmd: list[str], cwd: Path, env: dict, what: str) -> None:
    r = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"bindgen: {what} failed in {cwd}:\n{r.stdout}{r.stderr}")


# ── CLI ───────────────────────────────────────────────────────────────────────


def umbrella_for(target: Target, tmp: Path) -> Path:
    path = tmp / f"umbrella_{target.package}.m"
    path.write_text("".join(f"#import <{h}>\n" for h in target.imports))
    return path


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--config", default=str(Path(__file__).parent / "bindgen.json"))
    ap.add_argument("--target", action="append", help="only this package (repeatable)")
    ap.add_argument("--ir-only", metavar="DIR", help="write the IR and stop")
    ap.add_argument("--from-ir", metavar="DIR", help="emit from a previously written IR")
    ap.add_argument("--no-verify", action="store_true", help="skip gofmt/vet/ios build")
    args = ap.parse_args(argv)

    cfg = json.loads(Path(args.config).read_text())
    targets = [Target.load(t) for t in cfg["targets"]]
    if args.target:
        targets = [t for t in targets if t.package in args.target]
    tmp = Path(os.environ.get("TMPDIR", "/tmp")) / "wata-bindgen"
    tmp.mkdir(parents=True, exist_ok=True)
    written: set[str] = set()

    for target in targets:
        if args.from_ir:
            ir = json.loads((Path(args.from_ir) / f"{target.package}.json").read_text())
            prov = ir.get("provenance", "")
        else:
            docs = DocIndex()
            ir = load(target, umbrella_for(target, tmp), docs)
            prov = (
                f"Source: {Path(sdk_path(target.sdk)).name} (Xcode {cfg['xcode']}), "
                f"target {target.triple}\n"
                "Frameworks: " + ", ".join(h.split("/")[0] for h in target.imports)
            )
            ir["provenance"] = prov
        if args.ir_only:
            out = Path(args.ir_only)
            out.mkdir(parents=True, exist_ok=True)
            (out / f"{target.package}.json").write_text(json.dumps(ir, indent=1, sort_keys=True) + "\n")
            continue

        em = Emitter(ir, prov)
        files = em.run()
        outdir = REPO / target.out
        outdir.mkdir(parents=True, exist_ok=True)
        for stale in outdir.glob("*.go"):
            if stale.read_text().startswith("// Code generated by tools/bindgen."):
                stale.unlink()
        for name, src in files.items():
            (outdir / name).write_text(src)
        (outdir / "REFUSALS.md").write_text(em.refusal_doc())
        print(
            f"bindgen: {target.package}: {len(files)} files, {len(em.refusals)} refusals -> {target.out}"
        )
        for r in sorted(em.refusals, key=lambda r: (r.owner, r.decl)):
            print("  refused " + r.line()[2:])
        written.add(target.out)
    if written and not args.no_verify:
        verify(written)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
