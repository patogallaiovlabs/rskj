# AGENTS.md

## Big Picture (read this first)
- RSKj is a single-module Gradle project: root includes only `rskj-core` (`settings.gradle`).
- Runtime entrypoint is `co.rsk.Start` (`rskj-core/src/main/java/co/rsk/Start.java`), which builds `RskContext`, runs preflight checks, and starts `NodeRunner`.
- Consensus/chain behavior spans multiple packages; treat these as high-risk: `co.rsk.mine`, `co.rsk.peg`, `co.rsk.vm`, `co.rsk.validators`, `co.rsk.trie`, `co.rsk.remasc`, `co.rsk.pcc`.
- Fork activation is data-driven from network configs (`rskj-core/src/main/resources/config/*.conf`) and loaded by `ActivationConfig.read(...)` (`org/ethereum/config/blockchain/upgrades/ActivationConfig.java`).
- Activation heights (e.g., `hardforkActivationHeights` and `consensusRules` in `config/main.conf`) are consensus-critical.

## Build/Test Workflows That Match CI
- Toolchain is Java 17 + in-repo wrapper; always use `./gradlew`.
- If wrapper jar is missing, run `./configure.sh` before Gradle commands.
- Compile/package without unit tests: `./gradlew build -x test` (matches CI build job).
- Unit tests: `./gradlew test`.
- Integration tests are separate: `./gradlew integrationTest` (not wired into `check`).
- Lint all source sets: `./gradlew checkstyleAll`.
- PR lint parity (changed files only):
  - `./gradlew checkstyleFile -PfilePath="src/main/java/A.java,src/test/java/B.java" -x build`
  - `./gradlew spotlessJavaCheck -PratchetFrom=origin/<base-branch> -x build`
- Build runnable fat jar: `./gradlew fatJar` -> `rskj-core/build/libs/rskj-core-<version>-all.jar`.

## CI Gates You Should Predict
- Main PR workflow (`.github/workflows/build_and_test.yml`): build, unit tests, integration tests, mining tests, then Sonar step.
- Mining tests run external Node.js suite (`rsksmart/mining-integration-tests`) against a started node; failures can be independent of Gradle tests.
- Lint workflow only inspects changed `.java` files and strips `rskj-core/` prefix before `checkstyleFile`.
- `rit.yml` (external Rootstock integration tests) runs only for PRs targeting `master` or `*-rc`.

## Project Conventions (repo-specific)
- Prefer constructor injection with `private final` dependencies; use `Objects.requireNonNull` where practical (`CONTRIBUTING.md`).
- Prefer `Optional<T>` over returning `null`; if null is unavoidable, annotate method with `@Nullable`.
- Always use braces for control structures; keep naming Java-standard (`UpperCamelCase`, `lowerCamelCase`, `CONSTANT_CASE`).
- Avoid introducing new `@VisibleForTesting` unless absolutely necessary (treated as design smell here).
- Keep diffs reviewable: do not mix broad reordering/renaming with functional changes.

## Integration Points and Ownership
- JSON-RPC surface lives under `co.rsk.rpc` and `co.rsk.jsonrpc`; response-shape changes are high-risk.
- Mining and powpeg paths have CODEOWNERS in `.github/CODEOWNERS` (`@rsksmart/rsk-core`, `@rsksmart/rsk-fed`).
- Dependency additions/updates are security-sensitive and expected to be mirrored in `rsksmart/reproducible-builds`.
- For PRs to `master`/`*-rc`, complete all sections in `.github/pull_request_template.md`, including "Requires Activation Code (Hard Fork)" when applicable.

