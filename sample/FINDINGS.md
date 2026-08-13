# Step 2 — Render harness (Android Compose only)

**Question:** how much of step 1's *static* ceiling actually renders, and can a
Robolectric harness measure real text geometry per string resource?

**Verdict: the harness works.** Compose renders under Robolectric with real text
metrics, every text node yields geometry, and each node maps back to its string
resource. But **12.5–25% of statically-reachable strings never render**, and
Compose's built-in overflow flags are unusable — the harness must measure
truncation itself.

Stack: AGP 9.3.0, Kotlin 2.4.10, Compose BOM 2026.06.01, Robolectric 4.16.1,
ComposablePreviewScanner 0.9.2 — matching a real project's versions.

---

## 1. The render haircut

Sample has 8 statically-reachable UI strings. Measured:

| viewport | rendered | missed |
|---|--:|---|
| `w800dp-h1200dp` | 7/8 (87.5%) | `error_offline` |
| `w360dp-h640dp` | 6/8 (75.0%) | `error_offline`, `footer_note` |

- `error_offline` sits behind `if (isOffline)` and the preview passes `false`.
  **No viewport can reach it** — only a second preview can.
- `footer_note` is item 40 of a `LazyColumn`. It renders at 1200dp but not at
  640dp. **Render coverage depends on harness viewport height**, so the tool must
  separate two passes: a realistic-size pass for geometry verdicts, and
  optionally a tall pass purely for coverage (marking those sites "off-screen,
  geometry not trustworthy").

Applied to step 1's median static ceiling of 76.3%, the measurable share lands
around **57–67%**. Above the 60% bar at the top, marginal at the bottom.

## 2. Compose's overflow flags are unusable here

Ground truth: "Download everything" wants 259px, gets a 144px box → cut off.

| case | layoutW/maxW | `isLineEllipsized` | `didOverflowWidth` | `getLineEnd(visibleEnd)` |
|---|---|---|---|---|
| unconstrained (fits) | 259/1600 | false | **true** ❌ | 19/19 |
| 72dp box, Ellipsis (cut) | 144/144 | **false** ❌ | **false** ❌ | **19/19** ❌ |
| 72dp box, Clip (cut) | 144/144 | false ❌ | false ❌ | 19/19 ❌ |

`didOverflowWidth` is *inverted*. `isLineEllipsized` never fires.
`getLineRight(0) - getLineLeft(0)` returned a constant 114.0 across all three,
and disagreed with node bounds elsewhere (85px node → 37px).

**Use instead:** re-measure the captured `layoutInput` with a `TextMeasurer` at
unbounded constraints.

```kotlin
val li = textLayoutResult.layoutInput
val measurer = TextMeasurer(li.fontFamilyResolver, li.density, li.layoutDirection)

// width the text WANTS, on one line
val intrinsic = measurer.measure(
    text = li.text, style = li.style,
    softWrap = false, maxLines = 1, constraints = Constraints(),
).size.width

// lines it NEEDS at the width it was actually given
val linesNeeded = measurer.measure(
    text = li.text, style = li.style,
    softWrap = li.softWrap, maxLines = Int.MAX_VALUE,
    constraints = Constraints(maxWidth = li.constraints.maxWidth),
).lineCount

val cutOff = intrinsic > li.constraints.maxWidth || linesNeeded > li.maxLines
```

This is strictly better than a boolean: `available / intrinsic` is the **headroom
ratio**, the unit the whole product trades in.

Also use `TextLayoutResult.size.width` for laid-out width (it agrees with
`boundsInRoot`); never `getLineRight - getLineLeft`.

## 3. Measured output

| string | preview | fontScale | intrinsic/available | headroom | verdict |
|---|---|--:|---|--:|---|
| action_cancel | library (chip) | 1.0 | 85/144 | 1.69x | fits |
| action_cancel | dialog (button) | 1.0 | 89/624 | 7.01x | fits |
| action_download_all | greeting | 1.0 | 259/144 | 0.56x | **CUT OFF** |
| action_download_all | greeting | 1.3 | 344/144 | 0.42x | **CUT OFF** |
| button_confirm | dialog | 1.0 | 103/439 | 4.26x | fits |
| footer_note | library | 1.0 | 320/656 | 2.05x | fits |
| greeting | greeting | 1.0 | 255/720 | 2.82x | fits |
| greeting | greeting | 1.3 | 332/720 | 2.17x | fits |
| item_subtitle | library | 1.0 | 216/656 | 3.04x | fits |
| screen_title | library | 1.0 | 240/656 | 2.73x | fits |

Font scale works and matters: `greeting` 255px → 332px (+30%),
`action_download_all` 259px → 344px (+33%). One extra render at 1.3 is cheap and
catches real bugs.

`action_cancel` is the multi-site case, measured: **1.69x at the chip vs 7.01x at
the dialog — a 4.1× spread.** The chip is the binding constraint.

## 4. Harness gotchas (each cost a debugging cycle)

1. **Private previews are invisible.** `@Preview` functions are idiomatically
   `private`; the scanner silently returns an empty list without
   `.includePrivatePreviews()`. Always enable it.
2. **Scan outside the Robolectric sandbox.** ClassGraph cannot see the classpath
   from inside Robolectric's classloader. Discovery must run in
   `@Parameters` of `ParameterizedRobolectricTestRunner`.
3. **`@Parameters` runs once per sandbox.** A shared output file gets truncated
   repeatedly — 49 measurements collapsed to 2. Write one file per preview.
4. **`Classpath(packagePath, rootDir)` double-appends** the package and finds
   nothing. The default classpath resolution already handles AGP 9's
   `built_in_kotlinc/` output; don't override it.
5. **`setContent` is once per test**, so probes need separate `@Test` methods.
6. `enableScanningLogs()` / `scanAllPackages()` are compile *errors* without
   opt-in; enable `testLogging.showStandardStreams` instead.

## 5. What step 3 must resolve

- **Style resolution:** `layoutInput.style.fontSize` reads `Unspecified` for
  theme-inherited text. Fine for measurement (the resolved style is used), but
  the frontend overlay needs a concrete px size — derive it from line metrics.
- **Off-screen sites** need an explicit `offscreen` flag, not silent omission.
- **Cost:** 4 previews × 2 axes ≈ 8s wall clock here. Needs measuring at
  ~300 previews before claiming CI viability.

## Reproduce

```bash
./gradlew :app:testDebugUnitTest --tests '*RenderCoverageTest*'   # writes app/build/stringfit/sites/
./gradlew :app:testDebugUnitTest --tests '*OverflowSignalTest*'   # the flag comparison
```
