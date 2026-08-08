#!/usr/bin/env python3
"""Unit tests for tools/bindgen, over committed clang-AST fixtures.

No Xcode, no SDK, no device: every test feeds the generator AST excerpts from
fixtures/ and compares the emitted Go byte for byte against the expected
output. This is the leg of the bindings work that `just ci` runs.

    tools/bindgen/test_bindgen.py            # run
    tools/bindgen/test_bindgen.py --update   # rewrite the expected output
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import bindgen as B  # noqa: E402

FIXTURES = HERE / "fixtures"
UPDATE = "--update" in sys.argv


def ir_from_fixture(path: Path) -> dict:
    """Parse a fixture's clang AST nodes into the generator's IR."""
    spec = json.loads(path.read_text())
    docs = B.DocIndex()
    ir: dict = {"package": spec.get("package", "fixture")}
    ir["frameworks"] = spec.get("frameworks", [])
    for key in ("classes", "opaque"):
        nodes = spec.get(key, [])
        B.stamp_files(nodes)
        names = [n["name"] for n in nodes if n.get("kind") == "ObjCInterfaceDecl"]
        ir[key] = [B._asdict(B.collect_class(nodes, name, docs)) for name in names]
    ir["protocols"] = [B._asdict(B.parse_protocol(n, docs)) for n in spec.get("protocols", [])]
    ir["enums"] = [B._asdict(B.parse_enum(n, docs)) for n in spec.get("enums", [])]
    struct_nodes = spec.get("structs", [])
    B.stamp_files(struct_nodes)
    ir["structs"] = []
    for name in spec.get("structNames", []):
        st = B.collect_struct(struct_nodes, name, docs)
        if st is None:
            raise AssertionError(f"fixture struct {name!r} not found")
        ir["structs"].append(B._asdict(st))
    return ir


def generate(path: Path) -> tuple[dict[str, str], str]:
    em = B.Emitter(ir_from_fixture(path), provenance="Source: tools/bindgen/fixtures")
    return em.run(), em.refusal_doc()


class Fixtures(unittest.TestCase):
    """Each fixture's emitted Go must match its expected directory exactly."""

    def test_fixtures(self) -> None:
        names = sorted(p.stem for p in FIXTURES.glob("*.json"))
        self.assertTrue(names, "no fixtures found")
        for name in names:
            with self.subTest(fixture=name):
                files, refusals = generate(FIXTURES / f"{name}.json")
                files["REFUSALS.md"] = refusals
                expected_dir = FIXTURES / f"{name}.expected"
                if UPDATE:
                    if expected_dir.exists():
                        for stale in expected_dir.iterdir():
                            stale.unlink()
                    expected_dir.mkdir(exist_ok=True)
                    for fname, src in files.items():
                        (expected_dir / fname).write_text(src)
                    continue
                self.assertTrue(expected_dir.is_dir(), f"no expected output for {name}")
                self.assertEqual(
                    sorted(p.name for p in expected_dir.iterdir()),
                    sorted(files),
                    f"{name}: emitted a different set of files",
                )
                for fname, src in sorted(files.items()):
                    self.assertEqual(
                        (expected_dir / fname).read_text(),
                        src,
                        f"{name}/{fname} differs (rerun with --update to review the diff)",
                    )

    def test_generation_is_deterministic(self) -> None:
        """Two runs over the same IR emit identical bytes."""
        for path in sorted(FIXTURES.glob("*.json")):
            a, _ = generate(path)
            b, _ = generate(path)
            self.assertEqual(a, b, path.name)


class Refusals(unittest.TestCase):
    """Every unmappable declaration is refused with a reason, not dropped."""

    def refusals(self, fixture: str) -> dict[str, str]:
        em = B.Emitter(ir_from_fixture(FIXTURES / f"{fixture}.json"))
        em.run()
        return {r.decl: r.reason for r in em.refusals}

    def test_unmapped_return_type(self) -> None:
        self.assertIn("NSComparisonResult", self.refusals("method_object")["compare:"])

    def test_error_out_must_be_trailing(self) -> None:
        r = self.refusals("error_out")
        self.assertIn("recoverFromError:path:", r)
        self.assertIn("trailing out-parameter", r["recoverFromError:path:"])

    def test_nested_block(self) -> None:
        self.assertEqual(self.refusals("block_param")["runNested:"], "nested block")

    def test_block_in_callback_is_mapped(self) -> None:
        """A void-returning block parameter becomes an incoming-block handle."""
        r = self.refusals("protocol")
        self.assertNotIn("session:handle:", r)
        self.assertNotIn("session:progress:", r)
        files, _ = generate(FIXTURES / "protocol.json")
        self.assertIn(
            "SessionHandle func(session WFSession, done IncomingBlockVoid)",
            files["wfsessiondelegate.go"],
        )
        self.assertIn("IncomingBlockVoid{objcrt.CopyBlock(a1)}", files["wfsessiondelegate.go"])
        blocks = files["blocks.go"]
        self.assertIn("type IncomingBlockVoid struct{ h *objcrt.BlockHandle }", blocks)
        self.assertIn("func (b IncomingBlockVoid) Call()", blocks)
        self.assertIn("func (b IncomingBlockVoid) Release()", blocks)
        self.assertIn("func (b IncomingBlockFloat64String) Call(a0 float64, a1 string)", blocks)
        self.assertIn("b.h.Invoke(a0, objcrt.NSString(a1))", blocks)

    def test_incoming_block_with_return_is_refused(self) -> None:
        r = self.refusals("protocol")
        self.assertEqual(r["session:validate:"], "incoming block with a non-void return")

    def test_nested_block_in_callback_is_refused(self) -> None:
        self.assertEqual(self.refusals("protocol")["session:transform:"], "nested block")

    def test_handle_names(self) -> None:
        m = B.Mapper(classes={"WFThing"}, enums={}, opaque=set())
        self.assertEqual(B.handle_name([]), "IncomingBlockVoid")
        params = [m.map("double"), m.map("NSString *"), m.map("id"), m.map("WFThing *")]
        self.assertEqual(B.handle_name(params), "IncomingBlockFloat64StringIDWFThing")

    def test_struct_property(self) -> None:
        r = self.refusals("property")["@property frame"]
        self.assertIn("CGRect", r)
        self.assertIn("add it to structs", r)

    def test_struct_by_value_is_mapped(self) -> None:
        """An allowlisted struct becomes a Go struct used in signatures."""
        r = self.refusals("struct_by_value")
        self.assertNotIn("rangeOfSize:", r)
        self.assertNotIn("boxAt:", r)
        self.assertNotIn("@property contentSize", r)
        files, _ = generate(FIXTURES / "struct_by_value.json")
        structs = files["structs.go"]
        self.assertIn("type WFRange struct {", structs)
        self.assertIn("Location uint", structs)
        self.assertIn("Width  float64", structs)
        self.assertIn("Size  WFSize", structs)  # nested allowlisted struct
        geom = files["wfgeom.go"]
        self.assertIn("func (o WFGeom) RangeOfSize(size WFSize) WFRange {", geom)
        self.assertIn("return objc.Send[WFRange](o.ID, selRangeOfSize, size)", geom)
        self.assertIn("func (o WFGeom) SetContentSize(v WFSize) {", geom)

    def test_struct_refused_shapes(self) -> None:
        """Bitfields, unions and arrays refuse the struct once; every use
        points at that reason; un-allowlisted structs name the fix."""
        r = self.refusals("struct_by_value")
        self.assertEqual(r["struct WFBits"], "field flags is a bitfield")
        self.assertEqual(r["struct WFMix"], "a C union has no Go spelling")
        self.assertEqual(r["struct WFArr"], "field m is an array")
        self.assertEqual(r["applyBits:"], "struct WFBits is refused: field flags is a bitfield")
        self.assertIn("add it to structs", r["applyRect:"])
        self.assertEqual(r["enumerateRanges:"], "struct in a block signature")

    def test_struct_callbacks_are_typed(self) -> None:
        """Structs cross protocol callbacks as the generated struct types, in
        both directions, and the install carries the TRUE ObjC encoding."""
        r = self.refusals("struct_by_value")
        for sel in ("geom:didResize:", "geom:rangeForSpan:", "boxForGeom:", "geom:heightForBand:"):
            self.assertNotIn(sel, r)
        files, _ = generate(FIXTURES / "struct_by_value.json")
        d = files["wfgeomdelegate.go"]
        self.assertIn("GeomDidResize func(geom WFGeom, size WFSize)", d)
        self.assertIn("GeomRangeForSpan func(geom WFGeom, span uint) WFRange", d)
        self.assertIn("BoxForGeom func(geom WFGeom) WFBox", d)
        self.assertIn('Enc: "v@:@{WFSize=dd}"', d)
        self.assertIn('Enc: "{_WFRange=QQ}@:@Q"', d)
        self.assertIn('Enc: "{_WFBox={WFSize=dd}q}@:@"', d)
        # a method without a struct keeps the derived-encoding path (no Enc)
        self.assertNotIn('Sel: "geomDidFinish:",\n\t\t\tEnc:', d)

    def test_cgfloat_callback_return(self) -> None:
        """A CGFloat callback return: the user field returns a plain float64,
        the trampoline wraps it in objcrt.CGFloatRet (a one-member HFA,
        returned in d0), and the registered encoding stays the true "d"."""
        files, _ = generate(FIXTURES / "struct_by_value.json")
        d = files["wfgeomdelegate.go"]
        self.assertIn("GeomHeightForBand func(geom WFGeom, band int) float64", d)
        self.assertIn('Enc: "d@:@q"', d)
        self.assertIn("objcrt.CGFloatRet{V: d.GeomHeightForBand(", d)

    def test_float32_callback_return_is_refused(self) -> None:
        r = self.refusals("struct_by_value")
        self.assertIn("float return from a callback", r["scaleForGeom:"])

    def test_unavailable_is_not_a_refusal(self) -> None:
        """NS_UNAVAILABLE says the method is not callable: skip it, silently."""
        self.assertNotIn("new", self.refusals("method_object"))
        files, _ = generate(FIXTURES / "method_object.json")
        self.assertNotIn("func (c WFThingClass) New()", files["wfthing.go"])


class Categories(unittest.TestCase):
    """A class's API is spread over its @interface and its categories."""

    def setUp(self) -> None:
        self.ir = ir_from_fixture(FIXTURES / "category.json")
        self.cls = self.ir["classes"][0]
        self.sels = [m["sel"] for m in self.cls["methods"]]

    def test_only_one_class(self) -> None:
        self.assertEqual([c["name"] for c in self.ir["classes"]], ["WFBlob"])

    def test_members_are_merged(self) -> None:
        self.assertIn("blobWithName:", self.sels)  # from a category node
        self.assertIn("describeBlob", self.sels)  # from a loose method node
        self.assertEqual([p["name"] for p in self.cls["props"]], ["label"])

    def test_redeclaration_is_deduplicated(self) -> None:
        self.assertEqual(self.sels.count("length"), 1)

    def test_deprecated_is_dropped(self) -> None:
        self.assertNotIn("oldBlobWithName:", self.sels)

    def test_other_classes_are_not_absorbed(self) -> None:
        self.assertNotIn("somethingElse", self.sels)


class AvailabilityDeprecation(unittest.TestCase):
    """API_DEPRECATED expands to AvailabilityAttr nodes that look exactly like
    API_AVAILABLE in the JSON dump; the macro name at the attribute's expansion
    location is what tells them apart, read back out of the header."""

    HEADER = "- (void)oldThing API_DEPRECATED(\"gone\", macos(10.0,10.14));\n"

    def node(self, path: str, tok: str) -> dict:
        off = self.HEADER.index(tok) + 1  # clang offsets are 1-based
        return {
            "kind": "ObjCMethodDecl",
            "name": "oldThing",
            "inner": [
                {
                    "kind": "AvailabilityAttr",
                    "range": {
                        "begin": {
                            "expansionLoc": {"offset": off, "tokLen": len(tok), "file": path}
                        }
                    },
                }
            ],
        }

    def test_deprecated_macro_is_skipped(self) -> None:
        import tempfile

        with tempfile.NamedTemporaryFile("w", suffix=".h", delete=False) as f:
            f.write(self.HEADER)
        self.assertTrue(B._skip(self.node(f.name, "API_DEPRECATED"), B.DocIndex()))

    def test_plain_availability_is_kept(self) -> None:
        import tempfile

        header = "- (void)newThing API_AVAILABLE(macos(13.0));\n"
        with tempfile.NamedTemporaryFile("w", suffix=".h", delete=False) as f:
            f.write(header)
        node = self.node(f.name, "API_DEPRECATED")
        loc = node["inner"][0]["range"]["begin"]["expansionLoc"]
        loc["offset"] = header.index("API_AVAILABLE") + 1
        loc["tokLen"] = len("API_AVAILABLE")
        self.assertFalse(B._skip(node, B.DocIndex()))

    def test_unreadable_file_is_kept(self) -> None:
        self.assertFalse(B._skip(self.node("/nonexistent/x.h", "API_DEPRECATED"), B.DocIndex()))


class SelectorCollisions(unittest.TestCase):
    """Two selectors whose Go var names coincide (`setNeedsDisplay` vs
    `setNeedsDisplay:`) would emit a package that does not compile; generation
    must fail loudly instead of leaving that to go vet."""

    def test_colliding_selector_vars_fail_loudly(self) -> None:
        ir = {
            "package": "fixture",
            "classes": [
                {
                    "name": "WFOne",
                    "super": "NSObject",
                    "methods": [
                        {"sel": "poke", "instance": True, "ret": {"qualType": "void"}, "params": []}
                    ],
                },
                {
                    "name": "WFTwo",
                    "super": "NSObject",
                    "methods": [
                        {
                            "sel": "poke:",
                            "instance": True,
                            "ret": {"qualType": "void"},
                            "params": [{"name": "v", "type": {"qualType": "int"}}],
                        }
                    ],
                },
            ],
        }
        with self.assertRaises(SystemExit) as ctx:
            B.Emitter(ir).run()
        self.assertIn("poke", str(ctx.exception))


class TypeMapping(unittest.TestCase):
    def setUp(self) -> None:
        self.m = B.Mapper(classes={"WFThing"}, enums={"WFReason": "int"}, opaque={"NSData"})

    def go(self, qual: str, **kw) -> str:
        return self.m.map(qual, **kw).go

    def test_bridged_types(self) -> None:
        self.assertEqual(self.go("NSString * _Nonnull"), "string")
        self.assertEqual(self.go("NSError * _Nullable"), "error")
        self.assertEqual(self.go("WFThing * _Nonnull"), "WFThing")
        self.assertEqual(self.go("NSData * _Nonnull"), "NSData")
        self.assertEqual(self.go("id _Nullable"), "objc.ID")
        self.assertEqual(self.go("id<WFThing> _Nonnull"), "objc.ID")
        self.assertEqual(self.go("WFReason"), "WFReason")
        self.assertEqual(self.go("instancetype", self_class="WFThing"), "WFThing")

    def test_primitives(self) -> None:
        self.assertEqual(self.go("BOOL"), "bool")
        self.assertEqual(self.go("double"), "float64")
        self.assertEqual(self.go("NSInteger"), "int")
        self.assertEqual(self.go("NSUInteger"), "uint")
        self.assertEqual(self.go("void"), "")

    def test_blocks(self) -> None:
        t = self.m.map("void (^ _Nonnull)(WFThing * _Nullable, NSError * _Nullable)")
        self.assertEqual(t.go, "func(WFThing, error)")
        self.assertEqual(self.m.map("BOOL (^)(NSString *)").go, "func(string) bool")
        self.assertEqual(self.m.map("void (^)(void)").go, "func()")

    def test_struct_spellings(self) -> None:
        m = B.Mapper(
            classes=set(), enums={}, opaque=set(),
            structs={"NSRange": "NSRange", "struct _NSRange": "NSRange"},
            bad_structs={"WFBits": ("WFBits", "field flags is a bitfield")},
        )
        for spelling in ("NSRange", "struct _NSRange"):
            t = m.map(spelling)
            self.assertEqual((t.go, t.abi, t.is_struct), ("NSRange", "NSRange", True))
        got = m.map({"qualType": "NSRange", "desugaredQualType": "struct _NSRange"})
        self.assertEqual(got.go, "NSRange")
        with self.assertRaises(B.Unmappable) as caught:
            m.map("WFBits")
        self.assertIn("bitfield", str(caught.exception))
        with self.assertRaises(B.Unmappable) as caught:
            m.map({"qualType": "CGPoint", "desugaredQualType": "struct CGPoint"})
        self.assertIn("add it to structs", str(caught.exception))

    def test_unknown_class_is_refused(self) -> None:
        with self.assertRaises(B.Unmappable) as caught:
            self.m.map("NSTimer * _Nonnull")
        self.assertIn("allowlist", str(caught.exception))


class DocComments(unittest.TestCase):
    """The extractor reads doc comments back out of the header by offset."""

    def setUp(self) -> None:
        self.h = str(FIXTURES / "docs.h")
        self.src = Path(self.h).read_text()
        self.docs = B.DocIndex()

    def doc_for(self, decl: str) -> str:
        # clang's offsets are 1-based, so the +1 is what the loader also does.
        return self.docs.at(self.h, self.src.index(decl) + 1)

    def test_single_line(self) -> None:
        self.assertEqual(self.doc_for("- (void)single"), "One line, above the declaration.")

    def test_multi_line(self) -> None:
        self.assertEqual(self.doc_for("- (void)twoLines"), "First line.\nSecond line.")

    def test_block_comment(self) -> None:
        self.assertEqual(
            self.doc_for("- (void)blockComment"), "A block comment.\nWith a second line."
        )

    def test_undocumented(self) -> None:
        self.assertEqual(self.doc_for("- (void)undocumented"), "")


class Naming(unittest.TestCase):
    def test_selector_to_go_name(self) -> None:
        self.assertEqual(B.go_name("requestJoinChannelWithUUID:descriptor:"),
                         "RequestJoinChannelWithUUIDDescriptor")
        self.assertEqual(B.go_name("parse"), "Parse")

    def test_reserved_words_are_escaped(self) -> None:
        self.assertEqual(B.arg_name("type", 0), "type_")
        self.assertEqual(B.arg_name("", 2), "arg2")


if __name__ == "__main__":
    argv = [a for a in sys.argv if a != "--update"]
    unittest.main(argv=argv, verbosity=2)
