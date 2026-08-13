# StringFit

**Measures how much room each translatable string actually has — by rendering your
Compose `@Preview`s.**

Every translation tool lets you set a character limit per string. All of them make
you type that number in by hand, or guess it from a Figma mockup that drifted from
the code months ago. StringFit measures it from the app you actually ship.

> Status: 0.1.0, the first release. The measurement pipeline is validated end to
> end on real apps; the hosted/collaboration half described in the roadmap does
> not exist yet. Expect rough edges and please open issues.

```
StringFit
=========

Catalog          9 translatable strings
Measured         7 (77.8%) rendered by at least one @Preview
Not measured     1 referenced but never rendered
Unused           1 referenced nowhere

Site classes     hard=9  soft=1  free=0
                 only 90% of sites constrain length at all

CUT OFF — text does not fit today (1)
    action_download_all  needs 259px in 144px

CONFLICT — one string, very different budgets (1)
    action_cancel  1.69x .. 7.01x across 2 sites

UNUSED — referenced nowhere; triage in stringfit.yml (1)
    legacy_unused_hint

NOT MEASURED (1)
  These are used in your app but no @Preview renders them.
  Writing a preview for each is what grows coverage.
    error_offline
```

## Why

A translation that reads perfectly can still be the wrong one, because the button
is 72dp wide. Deciding that requires three facts about every string: the font size,
the number of lines allowed, and the width available. Those live in your layout
code, so that is where StringFit reads them.

The most useful thing it produces is not a limit — it is the **classification**:

| class | meaning | how many |
|---|---|---|
| `free` | unbounded width; length does not matter | 21% |
| `soft` | wraps, but line-limited; the budget is lines | 73% |
| `hard` | one line at a fixed width; length is a real constraint | **6%** |

Those percentages are measured from a shipping app (Seal, 426 strings). Telling a
translator "94% of these can be as long as your language needs, and here are the 6%
that genuinely cannot" beats a blanket character limit, which is what every existing
tool offers.

## Requirements

| | |
|---|---|
| Gradle | **8.8+** — older releases lack `ConfigurableFileCollection.convention` |
| JDK | 17+ |
| Android Gradle Plugin | 8.x or 9.x |
| UI | Jetpack Compose (Android). Compose Multiplatform is detected but not measured |

Every Gradle version in that range is exercised by a TestKit matrix in CI, and
the end-to-end flow runs on both build lines — AGP 9 on Gradle 9, and AGP 8 on
Gradle 8 — on every push.

## Install

```kotlin
// ROOT build.gradle.kts — this is the only file you touch
plugins { id("io.github.sarimmehdi.stringfit") version "0.1.0" }
```

Every Android module in the build is then configured for you: the harness test
dependencies are added to Compose modules, unit tests are told to include Android
resources, and the root gets tasks that report across the whole app at once.

Aggregation is the point, not a convenience. A string declared in `:core:ui` is
usually rendered by a preview in `:feature:home`, and neither module can judge
the fit alone — this is exactly the case the sample covers.

Then install the measurement harness:

```bash
./gradlew stringFitInstallHarness
```

That writes `src/test/java/stringfit/StringFitHarnessTest.kt` — **a file you own**.
Measuring a real app always needs local adjustment (a stub `Application`, DI setup,
extra composition locals), so it is a normal source file rather than hidden codegen.
It is never overwritten unless you pass `--overwrite`.

Re-sync so the new harness is wired in, then:

```bash
./gradlew test               # renders every @Preview and measures
./gradlew stringFitReport
```

Test dependencies are added for you; there is nothing to paste into a module.

## Languages

By default StringFit measures **every locale your project actually ships**,
discovered from `values-XX` directories. Testing German against an app with no
German translation measures nothing, so there is no fixed default list.

```kotlin
stringFit {
    // default: every values-XX in the project
    locales = listOf("de", "fr", "ja", "ar")     // explicit
    locales = listOf("high-risk")                 // de, ru, fr, ar, ja, th, hi
    locales = listOf("popular")                   // 17 widely shipped languages
    locales = listOf("pseudo")                    // en-XA / ar-XB, no translations needed
    locales = listOf("high-risk", "pt-rBR")       // presets mix with explicit codes
}
```

The report shows what each language does to your layout:

```
Languages
  locale     dir     sites   expand  cut off
  ar         RTL        10    0.65x        1
  de         LTR        10    1.21x        1
```

`expand` is mean intrinsic text width relative to the source locale. German runs
~1.2x wider here; Arabic is actually *more compact* at 0.65x, which is why
budgeting by character count misleads.

## Right to left

Every run includes a **direction probe**: the source text rendered with the
layout direction forced to RTL. Holding the text constant is what makes a
difference attributable to the layout:

```
RTL ASYMMETRY — layout does not mirror; check start/end padding (1)
    item_subtitle @ BadlyMirroredRowPreview[mirror-bug]
      [position] sits at x=504px in RTL; mirroring would place it at 264px
```

Comparing a real RTL language against the source cannot do this. When Arabic
made a sibling button narrower, the freed width looked exactly like a mirroring
bug — a false positive the probe removes.

Two kinds are reported:

- `width` — available width changed when only the direction flipped.
- `position` — the element kept the same width but never moved. This is what
  `Modifier.absolutePadding(left = …)` and hardcoded left/right insets do:
  nothing measurable changes except where the text ends up.

## Tasks

| task | what it does |
|---|---|
| `stringFitInstallHarness` | Writes the harness into every module that has `@Preview` functions. |
| `stringFitPrepare` | Resolves which locales to measure (runs automatically before `test`). |
| `stringFitReport` | Reads the measurements and reports budgets, cut-offs and conflicts. |
| `stringFitBaseline` | Records currently-unused strings so only *new* ones get reported. |

## Unused strings

`stringFitBaseline` writes every string referenced nowhere into `stringfit.yml`:

```yaml
unused:
  legacy_hint: ignore        # gone in Q4 — stop reporting, don't translate
  paywall_title: translate   # unused today, feature ships next month
  share_email: keep          # still deciding (default)
```

`ignore` and `translate` are deliberately independent axes: pre-translating a
feature before the UI is wired up is a normal thing to want. Detection is
conservative — anything that looks like a reference counts, including
`searchR.string.x`-style R-class import aliases — because a false "unused" claim
is much more damaging than a missed one.

## One string, several screens

When the same string renders in a roomy dialog *and* a 72dp chip, taking the
minimum budget punishes the translation everywhere it had room. StringFit reports
it as a **conflict** instead, with the spread, so you can choose:

1. **Every site fails** → the translation is genuinely too long. Constrain it.
2. **One tight site, rest fine** → a layout finding, not a translation one:
   `Modifier.widthIn(min = 72.dp)` instead of `Modifier.width(72.dp)`.
3. **The sites mean different things** → split the resource. One `cancel` shared
   by a chip and a dialog is a latent bug in any language.

The threshold is a 1.8x spread across hard-constrained sites, tuned against real
data — real spreads top out around 2.1x, so an intuitively-chosen 3x would never
have fired.

## How it works

1. `ComposablePreviewScanner` enumerates every `@Preview`, expanding multi-previews.
2. Robolectric renders each one on the JVM with real text metrics — no emulator.
3. Every text node's `TextLayoutResult` is re-measured with a `TextMeasurer` at
   unbounded constraints to get the width the text *wants*.
4. Rendered text is matched back to its string resource, including format-argument
   templates.

Rendering is cheap: 68 previews measured in **1.8s** (median 17ms each). Your build
already pays for the compile; the measurement is free on top.

### Things that cost a day to find out

- Compose's own overflow flags are unusable under Robolectric. `didOverflowWidth`
  is **inverted**, `isLineEllipsized` never fires, and `getLineEnd(visibleEnd=true)`
  returns the full length on truncated text. Re-measuring intrinsic width is the
  only reliable ground truth.
- `intrinsic > available` is **normal** for wrapping text — it just wraps. Treating
  it as truncation reported 6 broken strings on Seal where exactly 1 was real.
- Dialogs and bottom sheets compose into their **own root window**; `onRoot()` misses
  every one. Collecting all roots took preview coverage from 42/74 to 68/74.
- `@Preview` functions are idiomatically `private`, and the scanner silently returns
  an empty list without `includePrivatePreviews()`.
- Robolectric boots your real `Application` and every manifest-declared component
  before your test runs, so DI and native libraries load for no reason. The harness
  defaults to a stock `Application`.

## Limitations

- **Android + Compose only.** Compose Multiplatform and moko-resources are detected
  but not measured. XML/Fragment apps are out of scope entirely.
- **Modules are measured per module, aggregated at the root.** Each harness
  scans its own package tree but resolves every module's `R` class, so a string
  from a library rendered by an app preview is still named correctly under
  non-transitive R.
- **Coverage is bounded by your previews.** On a real app, ~61% of statically
  reachable strings actually render; strings behind untaken branches or below a
  lazy list's viewport never appear. The report names them so you can add previews.
- **RTL text shaping** is Robolectric's, and its font stack may not match a
  device for Arabic or Devanagari. Direction and layout findings are reliable;
  treat absolute glyph widths for complex scripts as indicative.
- Reported figures are Robolectric's rendering, which is close to but not identical
  to a device.

## Roadmap

- [x] Static coverage analysis
- [x] Render harness with per-site width and line budgets
- [x] Gradle plugin: install, report, unused triage
- [x] Translated-locale measurement, with RTL mirroring checks
- [x] Multi-module: apply once at the root
- [ ] Screenshots with per-string bounding boxes
- [ ] XLIFF 2.0 export with size restrictions
- [x] Verified Gradle compatibility matrix (8.8 … 9.6) in CI
- [ ] Publish to the Gradle Plugin Portal

## Repository layout

```
plugin/       the Gradle plugin (ktlint + detekt + unit tested)
sample/       a two-module AGP 9 build that applies it at the root:
              :app      previews, and the deliberately broken cases
              :core-ui  strings with no previews of their own
sample-agp8/  the same flow on the AGP 8 / Gradle 8 line
spike/        the research scripts behind the numbers quoted above
```

Run the whole thing locally:

```bash
./gradlew build                                   # plugin: ktlint, detekt, tests
cd sample && ./gradlew stringFitInstallHarness test stringFitReport
```

## Releasing

Publishing runs from CI on a `v*` tag, and refuses to publish if the tag and the
project version disagree. It needs two repository secrets, which are the names
the Gradle Plugin Portal itself uses:

| Secret | Where it comes from |
|---|---|
| `GRADLE_PUBLISH_KEY` | Plugin Portal → your profile → API keys |
| `GRADLE_PUBLISH_SECRET` | issued alongside the key |

`Release` can also be run manually from the Actions tab with **dry run** left on,
which validates the publication without uploading anything.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). The short version: measurement rules
are changed against evidence, and a false positive costs more than a missed
finding.

## Licence

Apache 2.0.
