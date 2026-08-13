# Contributing

## Getting set up

```bash
./gradlew build                 # plugin: ktlint, detekt, unit + functional tests
cd sample && ./gradlew stringFitInstallHarness test stringFitReport
```

You need JDK 17+ and an Android SDK for the samples. The plugin itself builds
without one.

## The rule that matters most

**Measurement rules are changed against evidence, not reasoning.**

Nearly every rule in `Budget` is the second or third version of itself, because
the obvious rule was wrong on a real app. Two examples that are now regression
tests:

- `intrinsic > available` looks like truncation. For wrapping text it is
  completely normal, and treating it as a defect reported six broken strings on
  a real app where exactly one was real.
- Comparing a real RTL language against the source locale looks like a mirroring
  check. It is not: Arabic made a sibling button narrower, the freed width looked
  exactly like an asymmetry, and the finding was a false positive.

So if you change a threshold or a verdict, say in the PR what data moved you.
"3.0 felt right" is how the conflict threshold ended up firing on nothing;
measuring real spreads is how it became 1.8.

A false positive costs far more than a missed finding. A tool that cries wolf
once gets switched off permanently.

## Things that will bite you

The harness is a Kotlin source template inside a raw string in `Harness.kt`, so
`$` needs escaping and the generated file is what actually has to compile. After
changing it:

```bash
cd sample && ./gradlew stringFitInstallHarness --overwrite test stringFitReport
```

Compose's own overflow flags are unusable under Robolectric — `didOverflowWidth`
is inverted and `isLineEllipsized` never fires. Ground truth is intrinsic width
re-measured with a `TextMeasurer`. Do not "simplify" that back to a flag.

## Before opening a PR

- `./gradlew build` passes (ktlint and detekt run as part of it).
- `./gradlew ktlintFormat` if formatting is the only complaint.
- New behaviour has a test. Pure logic goes in `BudgetTest`/`LocalesTest`;
  anything about how the plugin behaves in a build goes in `FunctionalTest`,
  which runs against a matrix of Gradle versions.
- If you touched anything user-visible, add a line to `CHANGELOG.md`.

## Releasing

Maintainers only. Update the version in `plugin/build.gradle.kts` and
`CHANGELOG.md`, then push a matching tag:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The Release workflow verifies the build, checks the tag matches the declared
version, and publishes to the Gradle Plugin Portal. It can also be run manually
with **dry run** on to validate without uploading.
