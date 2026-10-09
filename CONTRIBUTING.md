# Contributing

Thanks for contributing. This document describes how changes get into the repository.

## Branching

- **Never commit directly to `main`.** It is a protected branch.
- All new features, fixes and other changes go on a feature branch. Branch names must start with either:
  - `f-` (for example `f-combat-initiative`), or
  - `feature/` (for example `feature/encounter-optimizer`).
- Keep branch names short, lowercase and descriptive, with words separated by hyphens.
- Branch from an up-to-date `main` (or from the previous branch in a stack, see below).
- Keep branches short-lived. Delete a branch once its PR has merged.

## Commits

- Commit only to your feature branch, never to `main`.
- Keep commits focused. Each one should describe a single logical change.
- Write the subject line in the imperative mood ("Add rate limiter", not "Added rate limiter"), keep it under about 72 characters, and do not end it with a period.
- Use the body, when needed, to explain *why* the change was made, not just what changed.
- Do not mix unrelated changes, such as refactors, formatting and behaviour changes, in one commit.
- Never commit secrets, API keys, tokens or credentials (`SIM_API_KEYS`, `CF_API_TOKEN` and so on). Use environment variables, and keep local config and build output out of git.
- Do not rewrite history on a branch that others have pulled or that has an open PR with reviewers, unless you have agreed it with them. Never force-push to `main`.

## Pull requests

- **Every PR must ultimately merge into `main`.** Do not merge feature branches into `main` locally or push to it.
- **Stacked PRs are preferred.** Break large pieces of work into a chain of small PRs, where each branch builds on the previous one, so that each change stays isolated and easy to review.
  - Keep each PR small and limited to one concern.
  - The first PR in a stack targets `main`. Later PRs in the stack are based on the branch before them, and are retargeted to `main` as the earlier PRs merge.
  - Name stacked branches so the order is clear, for example `f-combat-1-types`, `f-combat-2-resolution`, `f-combat-3-reports`.
- Give the PR a clear title and a description covering what changed, why, and how you tested it. Link related issues or PRs, and say where a PR sits in a stack.
- Open a draft PR if the work is not ready for review.
- Do not mix unrelated changes in a PR. If you spot something else worth fixing, put it in its own branch.
- Make sure the build and tests pass before requesting review. CI runs `./gradlew build` on every PR and on pushes to `main`, and it must be green before merging.
- Keep your branch up to date with its base so that it merges cleanly, and resolve conflicts yourself.
- Respond to review comments, and mark conversations resolved only once they are addressed.

## Code quality

- Match the style and conventions of the surrounding code. Follow the module boundaries described in the [README](README.md#modules): `sim-domain` stays plain Java with no framework dependencies, and `ArchitectureTest` enforces the package rules.
- Add or update tests for any behaviour you add or change, and fix bugs with a regression test where practical. Do not delete or weaken a test just to make it pass.
- Keep changes minimal and focused. Avoid unrelated reformatting, dead code and commented-out code.
- Add a dependency only when it is needed, and declare its version in `gradle/libs.versions.toml`.
- Update documentation (`README.md`, `docs/`) when behaviour, configuration or APIs change.
- Do not edit generated or synced files by hand. The seed SQL in `sim-content` comes from `scripts/sync-seeds.sh`.

### Static analysis

`./gradlew build` runs PMD on every module with the rulesets in `config/pmd/`, plus `ArchitectureTest`. They catch early the
problems SonarCloud reports, so most Sonar issues never reach CI. Sonar stays the authority; PMD only approximates it.

- **Production code** (`config/pmd/main.xml`): cognitive complexity of 16 or more per method, a string literal repeated three
  times in a file, unused imports and private methods, empty control statements (a comment inside the block is enough), and
  `catch (Throwable)`. Catch `Exception`; if an `Error` must not leave work half-finished, settle it in a `finally` (see
  `JobService.run`).
- **Tests and test fixtures** (`config/pmd/test.xml`): unused imports and private methods, empty control statements, and
  `Thread.sleep`. Wait for a condition with Awaitility instead.
- **`ArchitectureTest`** also caps how many other project classes one class in `domain.combat` may use. Split a class that
  needs more, the way `Encounter` was split into `SpellResolver`, `WeaponAttackResolver` and friends.

When a rule fires, fix the code rather than suppressing it. If a rule is wrong for a case, change the ruleset in a PR that says why.

### Shared helpers to reuse

Before writing a loop, a ternary ladder or a string constant, look for the helper that already exists:

- `FloatOrder` (`domain.core`): compare or sort doubles (`descendingBy`); never `a - b` comparators.
- `Picks.firstMax` / `firstMin` (`domain.core`): the best candidate, the first of ties winning. Seeded results depend on tie order.
- `Tiers.pick` (`domain.core`): a value that steps up with level, instead of `level >= 17 ? ... : level >= 11 ? ...`.
- `ResourceIds`, `Gear`, `Objectives` and `MonsterSlugs`: named ids and equipment names, not string literals. The
  engine must not look a feature up by id - a class ability answers through its `Feature` hook (`allySaveBonus`,
  `resistsDamage`, ...) or a resource pool, so adding one never means editing `Roster` or `TacticalPolicy`.
- `Stats` and the per-run `record` pattern in `SoloEvaluator` / `PartyEvaluator`: collect one record per run, then summarize,
  rather than parallel lists. When summing doubles, keep a left-to-right fold (`reduce(0, Double::sum)`): `DoubleStream.sum()`
  uses compensated summation and can differ in the last bits, which the parity tests would catch.

## Before you open a PR

The project requires JDK 21.

```bash
./gradlew build    # compile, test and coverage report, the same as CI
```

## Security

- Do not open a public issue or PR for a security vulnerability. Report it privately to the maintainers.
- Use a scratch Cloudflare D1 database for development, never production.

## Workflow summary

```bash
git checkout main
git pull
git checkout -b f-my-change
# ...make changes and commit...
./gradlew build
git push -u origin f-my-change
# open a PR targeting main
```
