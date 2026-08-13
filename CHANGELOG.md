# Changelog

Notable changes to StringFit. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [semantic versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] — unreleased

First release. Everything below is new.

### Added

- **Per-string UI budgets measured from Compose `@Preview`s.** Every preview is
  rendered under Robolectric on the JVM and each text node is re-measured to
  find the width it actually wants.
- **Site classification** — `hard` (single line, fixed width), `soft` (wraps,
  line-limited) and `free` (unbounded). On a shipping app only ~6% of sites
  constrain length at all, which is the number that makes the difference between
  a useful budget and a blanket character limit.
- **Cut-off, tight and conflict reporting.** A string rendered in several places
  with very different budgets is reported as a conflict rather than silently
  reduced to its tightest site.
- **Translated-locale measurement.** Locales default to whatever the project
  ships (`values-XX` directories); presets `popular`, `high-risk`, `pseudo` and
  `all` expand in place and mix with explicit codes. Mean text expansion is
  reported per language.
- **RTL mirroring checks** via a direction probe — source text rendered with the
  layout direction forced to RTL — catching both width changes and elements that
  keep their width but never move.
- **Unused-string detection and triage** in `stringfit.yml`, where `ignore`,
  `translate` and `keep` are independent of one another so a feature can be
  translated before its UI is wired up.
- **Multi-module support**: applied once in the root build file, every Android
  module is configured automatically and the root aggregates the report.
- Tasks: `stringFitInstallHarness`, `stringFitPrepare`, `stringFitReport`,
  `stringFitBaseline`.

### Fixed

- `gradlew test` no longer fails on an application module's release unit test.
  `androidx.compose.ui:ui-test-manifest` is debug-only, so the activity the
  harness launches into is absent from a release variant's merged manifest; the
  harness is excluded from those tasks.
- `stringFitPrepare` is wired only into modules that actually have a harness.

### Known limitations

- Android + Jetpack Compose only. Compose Multiplatform and moko-resources are
  detected but not measured; XML/Fragment apps are out of scope.
- Coverage is bounded by your previews. On a real app roughly 61% of statically
  reachable strings actually render; the report names the rest.
- Robolectric's font stack may not match a device for complex scripts. Direction
  and layout findings are geometry and reliable; treat absolute glyph widths for
  Arabic or Devanagari as indicative.

[Unreleased]: https://github.com/sarimmehdi/stringfit/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/sarimmehdi/stringfit/releases/tag/v0.1.0
