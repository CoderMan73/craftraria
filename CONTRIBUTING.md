# Contributing to craftraria

Thank you for your interest in contributing to craftraria! We welcome contributions from the community.

## Table of Contents

- [Getting Started](#getting-started)
- [How to Report a Bug](#how-to-report-a-bug)
- [How to Suggest Enhancements](#how-to-suggest-enhancements)
- [Development Environment](#development-environment)
- [Coding Conventions](#coding-conventions)
- [Pull Request Process](#pull-request-process)

## Getting Started

See the [README.md](README.md) for installation and usage instructions. A Fabric mod for Minecraft 1.26.2 that lets a player run the real Terraria from inside Minecraft. The player supplies their own Terraria Content directory; the mod serves the Terrarium...

## How to Report a Bug

1. Check existing issues on the repository to avoid duplicates.
2. Open a bug report using the issue template.
3. Include: expected behavior, actual behavior, reproduction steps, environment details.

## How to Suggest Enhancements

- Use the feature request/enhancement template.
- Encourage testing of suggested enhancements before review.

## Development Environment

1. Prerequisites (see PLAN.md target stack): Java 21 or newer (toolchain pinned at M1 against
   Rinku 3.0.5's class-file version; its own build declares Java 25), Minecraft Java Edition
   1.26.2, Fabric modloader 1.26.2 with Fabric API, and the Gradle wrapper (auto-provisioned by
   `./gradlew`).
2. Clone the repository:
   ```bash
   git clone https://github.com/CoderMan73/craftraria
   cd craftraria
   ```
3. Generate IDE configuration:
   ```bash
   ./gradlew genIntelliJ
   ```
4. Run a dev client to test changes:
   ```bash
   ./gradlew runClient
   ```
5. Run tests:
   ```bash
   ./gradlew check
   ```

## Coding Conventions

- **Commit messages**: use [Conventional Commits](https://www.conventionalcommits.org/) - a type
  (`feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`),
  then a colon, then an imperative subject without a trailing period. Wrap the body at 72
  characters and say why, not what. The full rules, the versioning scheme, and the release runbook
  live in [docs/commits-and-releases.md](docs/commits-and-releases.md) (written at M0).
  ```bash
  feat: add local HTTP server for love.js serving

  Serves the love.js package with COOP/COEP headers on localhost. Closes #12.
  ```
- **Code style**: Follow standard language conventions. Write self-documenting code; avoid
  inline comments unless explaining non-obvious behavior.

## Pull Request Process

1. Fork the repository.
2. Create a branch: `git checkout -b feat/my-feature`
3. Make your changes and ensure tests pass.
4. Commit your changes with a clear, imperative message.
5. Push to your branch: `git push origin feat/my-feature`
6. Open a pull request and address review feedback.

> Guidance from `references/contributing-guide.md`. Fill from PLAN.md before publishing.
