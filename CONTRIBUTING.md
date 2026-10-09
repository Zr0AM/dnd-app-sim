# Contributing

Thanks for contributing. This document describes how changes get into the repository.

## Branching

- **Never commit directly to `main`.** It is a protected branch.
- All new features, fixes and other changes go on a feature branch. Branch names must start with either:
  - `f-` (for example `f-combat-initiative`), or
  - `feature/` (for example `feature/encounter-optimizer`).
- Keep branch names short, lowercase and descriptive, with words separated by hyphens.

## Commits

- Commit only to your feature branch, never to `main`.
- Keep commits focused. Each one should describe a single logical change.

## Pull requests

- **Every PR must ultimately merge into `main`.** Do not merge feature branches into `main` locally or push to it.
- **Stacked PRs are preferred.** Break large pieces of work into a chain of small PRs, where each branch builds on the previous one, so that each change stays isolated and easy to review.
  - Keep each PR small and limited to one concern.
  - The first PR in a stack targets `main`. Later PRs in the stack are based on the branch before them, and are retargeted to `main` as the earlier PRs merge.
  - Name stacked branches so the order is clear, for example `f-combat-1-types`, `f-combat-2-resolution`, `f-combat-3-reports`.
- Make sure the build and tests pass before requesting review.

## Workflow summary

```bash
git checkout main
git pull
git checkout -b f-my-change
# ...make changes and commit...
git push -u origin f-my-change
# open a PR targeting main
```
