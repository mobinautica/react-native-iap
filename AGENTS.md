# Repository Guidelines

This guide helps contributors work efficiently in this repo.

## Project Structure & Module Organization
- `src/` TypeScript source (public API in `src/index.ts`, platform modules in `src/modules/`, hooks in `src/hooks/`, tests in `src/__tests__/`).
- `android/`, `ios/` Native bridge implementations (Kotlin/Swift/Obj‑C).
- `IapExample/` Example app for manual testing (Play/Amazon/iOS).
- `test/` Shared test utilities and mocks (e.g., `test/mocks`).
- `plugin/`, `app.plugin.js` Expo plugin source/build.
- `docs/` Docusaurus site and guides.

## Build, Test, and Development Commands
- `yarn` Install dependencies.
- `yarn bootstrap` Install root + example and iOS pods.
- `yarn example` Open example app workspace; then inside `IapExample`:
  - `yarn start`, `yarn ios`, `yarn android:play`, `yarn android:amazon`.
- `yarn test` Run Jest unit tests.
- `yarn lint` Run TypeScript checks, ESLint (with import sort), and Prettier.
- `yarn gen:doc` Generate API docs.
- `yarn release` Maintainers: cut a release via release‑it.

## Coding Style & Naming Conventions
- TypeScript, 2‑space indent, LF endings (`.editorconfig`).
- Prettier: single quotes, trailing commas, no bracket spacing.
- ESLint with `@react-native-community`, `simple-import-sort` enforced.
- SwiftLint (`yarn lint:swift`) and ktlint (`yarn lint:kotlin`) for native code.
- Filenames: `camelCase.ts`; types/interfaces PascalCase in `src/types`.
- Hooks prefixed `use*` in `src/hooks`.

## Testing Guidelines
- Framework: Jest (`preset: react-native`).
- Location/pattern: `src/__tests__/*.test.ts` (example: `src/__tests__/iap.test.ts`).
- Mocks: place under `test/mocks` (configured in `jest.config.js`).
- Coverage outputs to `coverage/`. New features should include tests.

## Commit & Pull Request Guidelines
- Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`.
- PRs: small, focused; include description, linked issues, and any behavior notes.
- Ensure `yarn lint` and `yarn test` pass; validate changes in `IapExample` when touching native code.

## Architecture Notes
- Public JS API in `src/` delegates to native modules under `android/` and `ios/` via a shared event emitter.
- Expo module plugin sources live in `plugin/` and are built in CI/prepare.
