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
