#!/usr/bin/env python3
"""
StringFit spike -- preview coverage analyzer.

Answers the go/no-go question for the StringFit thesis:

  Of the translatable strings in an Android app, what fraction is reachable
  from at least one @Preview -- and what fraction *could* be reachable if the
  developer wrote more previews?

This is a STATIC, throwaway analyzer. It does not build or render anything.
It parses Kotlin source text and Android resource XML, builds an approximate
composable call graph, and computes reachability from @Preview entry points.

Deliberately over-approximates the call graph (resolves calls by simple name),
so the reported coverage is an UPPER bound. See LIMITATIONS at the bottom.
"""

from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from bisect import bisect_right
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

# --------------------------------------------------------------------------
# Kotlin lexical prep
# --------------------------------------------------------------------------

def strip_kotlin(src: str) -> str:
    """Blank out comments, string literals and char literals, preserving
    offsets and newlines so positions stay comparable to the original."""
    out = list(src)
    n = len(src)

    def blank(a: int, b: int) -> None:
        for k in range(a, min(b, n)):
            if out[k] != "\n":
                out[k] = " "

    i = 0
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            j = n if j == -1 else j
            blank(i, j)
            i = j
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith("/*", j):
                    depth += 1
                    j += 2
                elif src.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            blank(i, j)
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j == -1 else j + 3
            blank(i, j)
            i = j
        elif c == '"' or c == "'":
            quote, j = c, i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == quote:
                    j += 1
                    break
                if src[j] == "\n":
                    break
                j += 1
            blank(i, j)
            i = j
        else:
            i += 1
    return "".join(out)


def match_delim(src: str, start: int, open_c: str, close_c: str) -> int:
    """Index just past the delimiter matching the one at `start`."""
    depth, i, n = 0, start, len(src)
    while i < n:
        if src[i] == open_c:
            depth += 1
        elif src[i] == close_c:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


# --------------------------------------------------------------------------
# Kotlin structure extraction
# --------------------------------------------------------------------------

MODIFIERS = {
    "private", "public", "internal", "protected", "inline", "noinline",
    "crossinline", "suspend", "override", "open", "abstract", "final",
    "operator", "infix", "tailrec", "external", "expect", "actual", "const",
    "lateinit", "vararg", "data", "sealed", "annotation", "companion",
    "reified", "value", "inner", "fun",
}

KEYWORDS = {
    "if", "when", "while", "for", "do", "else", "try", "catch", "finally",
    "return", "throw", "is", "as", "in", "by", "object", "class", "fun", "val",
    "var", "super", "this", "null", "true", "false", "and", "or", "not",
}

ANN_RE = re.compile(r"@([\w.]+)")
FUN_RE = re.compile(r"\bfun\s+(?:<[^<>]*>\s*)?([\w.`]+)\s*\(")
ANN_CLASS_RE = re.compile(r"\bannotation\s+class\s+(\w+)")
CALL_RE = re.compile(r"\b([A-Za-z_]\w*)\s*[({]")
REF_RE = re.compile(r"::([A-Za-z_]\w*)")

# Compose Preview markers shipped by AndroidX (incl. Wear / Glance).
BASE_PREVIEW_ANNOTATIONS = {
    "Preview",
    "PreviewLightDark",
    "PreviewFontScale",
    "PreviewScreenSizes",
    "PreviewDynamicColors",
    "PreviewParameterProvider",  # not a marker, filtered below
    "WearPreviewDevices",
    "WearPreviewSmallRound",
    "WearPreviewLargeRound",
    "WearPreviewSquare",
    "WearPreviewFontScales",
    "GlancePreview",
}
BASE_PREVIEW_ANNOTATIONS.discard("PreviewParameterProvider")


def find_annotations(src: str) -> list[tuple[int, int, str]]:
    """(start, end, simple_name) for every @Annotation, args included."""
    out = []
    for m in ANN_RE.finditer(src):
        name = m.group(1).split(".")[-1]
        end = m.end()
        j = end
        while j < len(src) and src[j] in " \t":
            j += 1
        if j < len(src) and src[j] == "(":
            end = match_delim(src, j, "(", ")")
        out.append((m.start(), end, name))
    return out


def annotations_before(src: str, pos: int, anns: list[tuple[int, int, str]],
                       ann_ends: list[int]) -> list[str]:
    """Annotations forming a contiguous run immediately before `pos`
    (only whitespace and modifier keywords may sit between them)."""
    names: list[str] = []
    cursor = pos
    idx = bisect_right(ann_ends, cursor)
    while idx > 0:
        a_start, a_end, a_name = anns[idx - 1]
        if a_end > cursor:
            idx -= 1
            continue
        between = src[a_end:cursor]
        tokens = re.findall(r"[\w]+", between)
        if any(t not in MODIFIERS for t in tokens):
            break
        if re.search(r"[^\s\w]", re.sub(r"[\w\s]", "", between)):
            # punctuation between annotation and decl -> not an annotation run
            break
        names.append(a_name)
        cursor = a_start
        idx -= 1
    return names


@dataclass
class Fun:
    fid: str
    name: str
    file: str
    start: int          # offset of body start
    end: int            # offset of body end
    sig_start: int      # offset of the '(' of the parameter list
    decl_pos: int
    annotations: list[str]
    is_composable: bool
    is_preview: bool = False


# A newline at nesting depth 0 followed by any of these starts a new
# declaration, which means the function we were scanning had no body.
NEXT_DECL_RE = re.compile(
    r"\s*(?:@|\}|\b(?:fun|val|var|class|object|interface|enum|companion|"
    r"private|internal|public|protected|override|abstract|open|suspend|"
    r"inline|operator|sealed|data|annotation)\b)"
)


def body_span(src: str, after_params: int) -> tuple[int, int] | None:
    """Locate the function body following the parameter list."""
    n = len(src)
    i = after_params
    depth = 0
    while i < n:
        c = src[i]
        if c == "{" and depth == 0:
            return (i, match_delim(src, i, "{", "}"))
        if c == "=" and depth == 0 and (i + 1 >= n or src[i + 1] != "="):
            # expression body: consume until balance returns to 0 at a newline
            j, bal = i + 1, 0
            while j < n:
                ch = src[j]
                if ch in "({[":
                    bal += 1
                elif ch in ")}]":
                    if bal == 0:
                        break
                    bal -= 1
                elif ch == "\n" and bal == 0 and NEXT_DECL_RE.match(src, j + 1):
                    break
                j += 1
            return (i, j)
        if c in "<([":
            depth += 1
        elif c in ">)]":
            depth = max(0, depth - 1)
        elif c in "{}":
            return None
        elif c == "\n" and depth == 0 and NEXT_DECL_RE.match(src, i + 1):
            return None
        i += 1
    return None


def parse_kotlin_file(path: Path, rel: str) -> tuple[list[Fun], str, dict[str, list[str]]]:
    """Parse one file. Returns functions (with raw annotation names),
    the stripped source, and {annotation class name -> its annotations}."""
    raw = path.read_text(encoding="utf-8", errors="replace")
    src = strip_kotlin(raw)
    anns = find_annotations(src)
    ann_ends = [a[1] for a in anns]

    ann_classes: dict[str, list[str]] = {}
    for m in ANN_CLASS_RE.finditer(src):
        ann_classes[m.group(1)] = annotations_before(src, m.start(), anns, ann_ends)

    funs: list[Fun] = []
    for m in FUN_RE.finditer(src):
        name = m.group(1).split(".")[-1].strip("`")
        paren = m.end() - 1                      # FUN_RE ends on the '('
        span = body_span(src, match_delim(src, paren, "(", ")"))
        if span is None:
            continue
        names = annotations_before(src, m.start(), anns, ann_ends)
        funs.append(Fun(
            fid=f"{rel}#{name}@{span[0]}",
            name=name,
            file=rel,
            start=span[0],
            end=span[1],
            sig_start=paren,
            decl_pos=m.start(),
            annotations=names,
            is_composable="Composable" in names,
        ))
    return funs, src, ann_classes


# --------------------------------------------------------------------------
# Resource parsing
# --------------------------------------------------------------------------

SKIP_DIRS = {"build", ".git", ".gradle", ".idea", "node_modules", "out"}

# Reference syntaxes across the three resource systems Android apps use.
STR_REF_RES = {
    # `\w*R` also catches the R-class import aliases that multi-module apps
    # rely on (`searchR.string.x`, `CoreUiR.string.x`) with non-transitive R.
    "android": re.compile(r"\b\w*R\.(?:string|plurals|array)\.(\w+)"),
    "cmp": re.compile(r"\bRes\.(?:string|plurals|array)\.(\w+)"),
    "moko": re.compile(r"\bMR\.(?:strings|plurals|arrays)\.(\w+)"),
}
XML_REF_RE = re.compile(r"@(?:string|plurals|array)/(\w+)")

# Test source sets are excluded from the UI graph. `screenshotTest` is kept:
# that is where Compose Preview Screenshot Testing previews legitimately live.
TEST_PATH_RE = re.compile(r"(?:^|/)src/(?:test|androidTest|commonTest|"
                          r"jvmTest|iosTest|testFixtures)[^/]*/")


def is_test_path(rel: str) -> bool:
    return bool(TEST_PATH_RE.search("/" + rel.replace(os.sep, "/")))


def walk(root: Path):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for f in filenames:
            yield Path(dirpath) / f


@dataclass
class Repo:
    name: str
    root: Path
    strings: dict[str, dict] = field(default_factory=dict)
    locales: set[str] = field(default_factory=set)
    systems: dict[str, int] = field(default_factory=lambda: defaultdict(int))


def classify_catalog(parts: tuple[str, ...]) -> tuple[str, str] | None:
    """Identify a resource catalog file -> (system, qualifier).
    Qualifier "" means the default/source locale."""
    for anchor, is_values in (("res", True), ("composeResources", True)):
        if anchor in parts:
            i = len(parts) - 1 - parts[::-1].index(anchor)
            if i + 2 <= len(parts) - 1:
                qual = parts[i + 1]
                if qual.startswith("values"):
                    return (("android" if anchor == "res" else "cmp"),
                            qual[len("values-"):] if qual != "values" else "")
    for anchor in ("MR", "moko-resources"):             # moko-resources
        if anchor in parts:
            i = len(parts) - 1 - parts[::-1].index(anchor)
            if i + 2 <= len(parts) - 1:
                qual = parts[i + 1]
                return "moko", ("" if qual == "base" else qual)
    return None


def parse_resources(repo: Repo) -> None:
    for p in walk(repo.root):
        if p.suffix != ".xml":
            continue
        rel = str(p.relative_to(repo.root))
        if is_test_path(rel):
            continue
        info = classify_catalog(p.parts)
        if info is None:
            continue
        system, qual = info
        repo.systems[system] += 1
        if qual:
            repo.locales.add(qual)
            continue
        try:
            root = ET.parse(p).getroot()
        except ET.ParseError:
            continue
        for el in root:
            if el.tag not in ("string", "plurals", "string-array"):
                continue
            name = el.attrib.get("name")
            if not name:
                continue
            repo.strings.setdefault(name, {
                "name": name,
                "kind": el.tag,
                "translatable": el.attrib.get("translatable", "true") != "false",
                "value": "".join(el.itertext()).strip()[:160],
                "system": system,
                "file": rel,
            })


# --------------------------------------------------------------------------
# Analysis
# --------------------------------------------------------------------------

def analyze(root: Path, name: str) -> dict:
    repo = Repo(name=name, root=root)
    parse_resources(repo)

    all_kt = [p for p in walk(root) if p.suffix in (".kt", ".java")]
    kt_files = [p for p in all_kt
                if not is_test_path(str(p.relative_to(root)))]
    test_files = [p for p in all_kt if p not in set(kt_files)]
    xml_files = [p for p in walk(root)
                 if p.suffix == ".xml" and classify_catalog(p.parts) is None
                 and not is_test_path(str(p.relative_to(root)))]

    # single parse pass; the multi-preview fixpoint then runs over names only
    parsed: dict[str, tuple[list[Fun], str]] = {}
    ann_classes: dict[str, list[str]] = {}
    for p in kt_files:
        rel = str(p.relative_to(root))
        funs, src, classes = parse_kotlin_file(p, rel)
        parsed[rel] = (funs, src)
        ann_classes.update(classes)

    preview_anns = set(BASE_PREVIEW_ANNOTATIONS)
    while True:
        grown = {name for name, on in ann_classes.items()
                 if any(a in preview_anns for a in on)}
        if grown <= preview_anns:
            break
        preview_anns |= grown

    all_funs: list[Fun] = []
    for funs, _ in parsed.values():
        for f in funs:
            f.is_preview = any(a in preview_anns for a in f.annotations)
        all_funs.extend(funs)

    composables = [f for f in all_funs if f.is_composable]
    previews = [f for f in all_funs if f.is_preview]
    by_name: dict[str, list[Fun]] = defaultdict(list)
    for f in composables:
        by_name[f.name].append(f)

    # per-file sorted spans for enclosing-function lookup
    # Spans run from the parameter list, so that `stringResource(...)` used as
    # a default argument value is attributed to its own composable.
    spans: dict[str, list[Fun]] = defaultdict(list)
    for f in all_funs:
        spans[f.file].append(f)
    for v in spans.values():
        v.sort(key=lambda f: (f.sig_start, -(f.end)))

    def enclosing(rel: str, pos: int) -> Fun | None:
        best = None
        for f in spans.get(rel, ()):
            if f.sig_start <= pos < f.end:
                if best is None or (f.end - f.sig_start) < (best.end - best.sig_start):
                    best = f
            elif f.sig_start > pos:
                break
        return best

    # Call graph over composables. Two variants: `hi` resolves an ambiguous
    # simple name to every definition (over-approximates reachability), `lo`
    # drops ambiguous names entirely. Truth sits between them.
    edges_hi: dict[str, set[str]] = defaultdict(set)
    edges_lo: dict[str, set[str]] = defaultdict(set)
    for rel, (funs, src) in parsed.items():
        for f in funs:
            if not f.is_composable:
                continue
            body = src[f.sig_start:f.end]
            callees = {m.group(1) for m in CALL_RE.finditer(body)
                       if m.group(1) not in KEYWORDS and m.group(1) in by_name}
            callees |= {m.group(1) for m in REF_RE.finditer(body)
                        if m.group(1) in by_name}
            for nm in callees:
                for target in by_name[nm]:
                    if target.fid == f.fid:
                        continue
                    edges_hi[f.fid].add(target.fid)
                    if len(by_name[nm]) == 1:
                        edges_lo[f.fid].add(target.fid)

    def reach(edges: dict[str, set[str]]) -> set[str]:
        seen = {f.fid for f in previews}
        stack = list(seen)
        while stack:
            for nxt in edges.get(stack.pop(), ()):
                if nxt not in seen:
                    seen.add(nxt)
                    stack.append(nxt)
        return seen

    reachable = reach(edges_hi)
    reachable_lo = reach(edges_lo)

    # string reference sites
    sites: dict[str, list[dict]] = defaultdict(list)
    layer_counts: dict[str, int] = defaultdict(int)
    for rel, (funs, src) in parsed.items():
        for rx in STR_REF_RES.values():
            for m in rx.finditer(src):
                nm = m.group(1)
                fn = enclosing(rel, m.start())
                if fn is None:
                    layer = "kotlin_toplevel"
                elif fn.is_composable:
                    layer = "composable_previewed" if fn.fid in reachable \
                        else "composable_unpreviewed"
                else:
                    layer = "kotlin_non_ui"
                layer_counts[layer] += 1
                sites[nm].append({"layer": layer, "file": rel,
                                  "lo": fn is not None and fn.fid in reachable_lo,
                                  "fn": fn.name if fn else None})

    for p in test_files:                       # tracked, but never "covered"
        rel = str(p.relative_to(root))
        src = strip_kotlin(p.read_text(encoding="utf-8", errors="replace"))
        for rx in STR_REF_RES.values():
            for m in rx.finditer(src):
                layer_counts["test"] += 1
                sites[m.group(1)].append({"layer": "test", "file": rel,
                                          "fn": None})

    for p in xml_files:
        rel = str(p.relative_to(root))
        try:
            txt = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        layer = "manifest" if p.name == "AndroidManifest.xml" else "xml_res"
        for m in XML_REF_RE.finditer(txt):
            layer_counts[layer] += 1
            sites[m.group(1)].append({"layer": layer, "file": rel, "fn": None})

    # classify each translatable string
    translatable = {k: v for k, v in repo.strings.items() if v["translatable"]}
    buckets: dict[str, list[str]] = defaultdict(list)
    for nm in translatable:
        layers = {s["layer"] for s in sites.get(nm, ())}
        if not layers:
            buckets["unreferenced"].append(nm)
        elif "composable_previewed" in layers:
            buckets["covered"].append(nm)
        elif "composable_unpreviewed" in layers:
            buckets["gap_write_a_preview"].append(nm)
        elif "kotlin_toplevel" in layers:
            # enclosing function unresolved -> parser limitation, not a finding
            buckets["unattributed_parse_gap"].append(nm)
        elif "kotlin_non_ui" in layers:
            buckets["non_ui_layer"].append(nm)
        elif layers == {"test"}:
            buckets["test_only"].append(nm)
        elif "xml_res" in layers:
            buckets["xml_only"].append(nm)
        else:
            buckets["manifest_only"].append(nm)

    total = len(translatable)
    covered = len(buckets["covered"])
    covered_lo = sum(1 for nm in translatable
                     if any(s.get("lo") for s in sites.get(nm, ())))
    gap = len(buckets["gap_write_a_preview"])
    pct = lambda x: round(100.0 * x / total, 1) if total else 0.0

    return {
        "repo": name,
        "totals": {
            "strings_default_locale": len(repo.strings),
            "translatable": total,
            "locales": len(repo.locales),
            "kotlin_files": len(kt_files),
            "composables": len(composables),
            "previews": len(previews),
            "composables_reachable_from_preview": sum(
                1 for f in composables if f.fid in reachable),
            "multipreview_annotations": sorted(
                preview_anns - BASE_PREVIEW_ANNOTATIONS),
            "resource_systems": dict(repo.systems),
            "ref_sites_by_layer": dict(sorted(layer_counts.items(),
                                              key=lambda kv: -kv[1])),
        },
        "coverage": {
            "covered": covered,
            "covered_pct": pct(covered),
            "covered_lo": covered_lo,
            "covered_lo_pct": pct(covered_lo),
            "gap_write_a_preview": gap,
            "gap_pct": pct(gap),
            "ceiling": covered + gap,
            "ceiling_pct": pct(covered + gap),
            "non_ui_layer": len(buckets["non_ui_layer"]),
            "non_ui_pct": pct(len(buckets["non_ui_layer"])),
            "xml_only": len(buckets["xml_only"]),
            "xml_only_pct": pct(len(buckets["xml_only"])),
            "manifest_only": len(buckets["manifest_only"]),
            "test_only": len(buckets["test_only"]),
            "unattributed_parse_gap": len(buckets["unattributed_parse_gap"]),
            "unattributed_pct": pct(len(buckets["unattributed_parse_gap"])),
            "unreferenced": len(buckets["unreferenced"]),
            "unreferenced_pct": pct(len(buckets["unreferenced"])),
        },
        "samples": {k: sorted(v)[:15] for k, v in buckets.items()},
    }


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: coverage.py <repo-dir> [<repo-dir> ...]")
        return 2
    results = []
    for arg in sys.argv[1:]:
        root = Path(arg).resolve()
        if not root.is_dir():
            print(f"skip (not a dir): {root}", file=sys.stderr)
            continue
        print(f"analyzing {root.name} ...", file=sys.stderr)
        results.append(analyze(root, root.name))

    print(json.dumps(results, indent=2))

    cols = [("repo", 20), ("strings", 8), ("locs", 6), ("prevs", 7),
            ("covered (lo-hi)", 20), ("+preview gap", 15), ("CEILING", 9),
            ("non-UI", 8), ("xml", 7), ("test", 6), ("dead", 7), ("id-table", 10)]
    hdr = "".join(f"{n:>{w}}" if i else f"{n:<{w}}"
                  for i, (n, w) in enumerate(cols))
    print("\n" + hdr, file=sys.stderr)
    print("-" * len(hdr), file=sys.stderr)
    for r in results:
        t, c = r["totals"], r["coverage"]
        row = [
            r["repo"][:19],
            t["translatable"],
            t["locales"],
            t["previews"],
            f"{c['covered_lo_pct']}-{c['covered_pct']}% ({c['covered']})",
            f"{c['gap_write_a_preview']} ({c['gap_pct']}%)",
            f"{c['ceiling_pct']}%",
            f"{c['non_ui_pct']}%",
            f"{c['xml_only_pct']}%",
            c["test_only"],
            f"{c['unreferenced_pct']}%",
            f"{c['unattributed_pct']}%",
        ]
        print("".join(f"{str(v):>{w}}" if i else f"{str(v):<{w}}"
                      for i, (v, (_, w)) in enumerate(zip(row, cols))),
              file=sys.stderr)
    return 0


# --------------------------------------------------------------------------
# LIMITATIONS (read before trusting any number)
#
#  * Call graph resolves by simple name, ignoring imports and overloads.
#    Over-approximates -> coverage is an UPPER bound.
#  * Strings inside Kotlin string templates ("${...}") are blanked with the
#    literal and therefore missed.
#  * Only Android `res/values/**` catalogs are parsed. moko-resources and
#    Compose Multiplatform `Res.string` usage is counted but not resolved.
#  * "unreferenced" ignores dynamic lookup (getIdentifier) and generated code.
#  * Multi-module: all modules are pooled; cross-module name collisions merge.
# --------------------------------------------------------------------------

if __name__ == "__main__":
    raise SystemExit(main())
