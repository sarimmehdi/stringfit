# Step 1 — Preview coverage validation

**Question:** of the translatable strings in a real Android app, what fraction is
reachable from a `@Preview` — and what fraction *could* be, if the developer kept
writing previews?

**Bar set before running:** renderable ceiling ≥ 60% → proceed. ~20% → rethink.

**Verdict: PASS.** Median renderable ceiling **76.3%** (range 55–87%) across six
Compose apps. But three findings materially change the plan.

---

## Results

| repo | strings | locales | previews | covered now | ceiling | +id-table | unrealized |
|---|--:|--:|--:|--:|--:|--:|--:|
| Seal | 426 | 61 | 63 | 42.3% | 86.9% | 87.4% | 44.6 pp |
| GotEverything *(yours)* | 190 | 2 | 12 | 61.6% | 73.7% | 76.9% | 12.1 pp |
| ReadYou | 344 | 54 | 25 | 12.2% | 69.8% | 71.8% | 57.6 pp |
| mihon | 935 | 73 | 35 | 65.1%¹ | 67.4% | 76.3% | 2.3 pp |
| tivi | 170 | 3 | 0 | 0.0% | 60.6% | 64.1% | 60.6 pp |
| nowinandroid | 67 | 1 | 53 | 43.3% | 50.7% | 55.2% | 7.4 pp |
| LibreTube | 603 | 77 | 0 | — | — | — | **out of scope** |

- **ceiling** = strings with ≥1 reference site inside *any* composable. This is the
  number that decides the thesis, and it is **independent of call-graph accuracy** —
  a string either has a composable reference site or it does not.
- **+id-table** adds strings referenced outside any function (enum constructors, nav
  tables, top-level vals — `ALWAYS(MR.strings.lock_always)`). Static analysis cannot
  attribute these; **a renderer would catch them**. So the ceiling is a floor.
- **covered now** = reachable from an existing preview today.
- **unrealized** = ceiling − covered now: the preview-writing work available.

¹ mihon's `covered` is unreliable (band 5.8–65.1%): 57 composables share the name
`Content` (Voyager `Screen.Content()` overrides), so name-based call resolution
smears reachability. Its *ceiling* is unaffected.

## Three findings that change the plan

**1. The ceiling is good, but current coverage is far below it.**
Seal 42→87, ReadYou 12→70, tivi 0→61. The "just write more previews" ask is
**30–60 percentage points** of new preview work on a mature codebase — not a nudge.
The coverage report, the per-string "uncovered" list, and the preview-stub printer
are not nice-to-haves; they are the onboarding path. Without them the tool reports
mostly blanks on day one and looks broken.

**2. Market segmentation is real and must gate who you sell to.**
LibreTube: 603 strings, 77 locales, heavily localized — and **zero `@Composable`
functions**. A pure XML/Fragment app is not partially in scope, it is entirely out.
Qualify on "is this a Compose app" before anything else.

**3. Three resource systems, not one.** tivi uses Compose Multiplatform
(`Res.string`, catalogs under `composeResources/values/`), mihon uses moko-resources
(`MR.strings`, catalogs under `moko-resources/base/`). Android `R.string` alone
covers four of seven repos. CMP support is not optional for a 2026 tool.

## Secondary findings

- **Strings in non-UI code (ViewModels, notifications, workers) are only 3–19%** —
  smaller than the design doc feared. That worry was overstated.
- **6–16% of translatable strings are referenced nowhere.** Dead-string detection
  falls out for free and is a credible standalone hook. (Caveat: ignores
  `getIdentifier` and generated code.)
- **R-class import aliases** (`searchR.string.x`) are standard in multi-module apps.
  Missing them cost NiA 19 points of coverage before the fix — the real plugin must
  handle non-transitive R and alias imports from the start.
- **`stringResource()` in default parameter values** is common
  (`fun DismissButton(text: String = stringResource(R.string.dismiss))`). It belongs
  to its own composable and renders normally.

## What this does not prove

The ceiling is *static reachability*, not *render coverage*. A string can sit in a
composable that a preview reaches yet never appear in the rendered output — behind
an `if`, in a collapsed branch, in a `LazyColumn` item that never materializes at
preview size. **Step 2 must measure how much of the static ceiling actually renders.**
Expect a further haircut; the honest question is how big.

## Method / limitations

Static analysis only — nothing is built or rendered. Kotlin is lexed (comments and
string literals blanked), functions and annotations extracted, an approximate
composable call graph is built by simple name, and reachability runs from `@Preview`
entry points including custom multi-preview annotation classes (resolved to fixpoint).

- Call resolution ignores imports and overloads. Reported as a band: `lo` drops all
  ambiguous names, `hi` links every same-named definition. Ambiguity is 4.8–8.6% of
  names outside mihon.
- Test source sets are excluded from the UI graph (`screenshotTest` is kept).
- Strings inside Kotlin string templates (`"${...}"`) are missed.
- Multi-module: modules are pooled, so cross-module name collisions merge.
- Validated against `fixture/`, which has hand-checked ground truth for every bucket,
  and spot-verified by tracing reachability paths in Seal
  (`about` ← `NavigationDrawerSheetContent` ← `NavigationDrawer` ← `ExpandedPreview`).

## Reproduce

```bash
python3 coverage.py fixture                 # ground-truth regression
python3 coverage.py <repo> [<repo> ...]      # JSON to stdout, table to stderr
python3 trace.py <repo> <string_name>...     # why a string is/isn't covered
python3 diag.py <repo>                       # ambiguity + unattributed sites
```
