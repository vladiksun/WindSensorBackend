---
description: Development Guide
author: Vladislav Bondarchuk
version: 1.0
globs: ["**/*.java", "**/*.kts", "**/*.xml"]
---

# Project Development Guide

## This file commented sections
You MUST NOT use lines of this file starting with `[//]:`

## Project Overview

[//]: # (TODO)

## Command Conventions

- You MUST run all Gradle commands (except testing ones) with the quiet option to reduce output:
    - Do NOT use: `--stacktrace`, `--info` (excessive output will not fit the context)
- You MUST run all Gradle testing commands without the `-q` option, since that will suppress the output result of each test.
- You MUST run Gradle with the Gradle wrapper: `./gradlew`.

## Main Development Tasks:

[//]: # (TODO)

## Code style

- You SHOULD prefer modern Java idioms: records, pattern matching, sealed interfaces/classes, `var` for local variables.
- You SHOULD use pattern matching in Java `instanceof` if possible.
- You MUST NOT use fully qualified class names unless there is a conflict between 2 class names in different packages.
- You MUST NOT use reflection: Micronaut is a reflection-free framework tailored for integration with GraalVM.
- You MUST use `jakarta.inject` for dependency injection, NOT `javax.inject`.

## Binary compatibility

[//]: # (TODO)

## Implementation Workflow (Required Checklist)

You MUST follow this sequence after editing source files:

1) Lint code in affected modules
   - Spotless check: `./gradlew -q spotlessCheck`
   - If Spotless fails: `./gradlew -q spotlessApply` then re-run `spotlessCheck` 

2) Compile affected modules
    - `./gradlew -q :<module>:compileJava`
    - `./gradlew -q :<module>:compileTestJava`

3) Run targeted tests first (fast feedback)
    - `./gradlew :<module>:test --tests 'pkg.ClassTest'`
    - `./gradlew :<module>:test --tests 'pkg.ClassTest.method'` (optional)

[//]: # (3&#41; Run full tests for all affected modules)

[//]: # (    - `./gradlew :<module>:test`)

[//]: # (4&#41; Static checks)

[//]: # (    - Checkstyle: `./gradlew -q cM`)

[//]: # (5&#41; &#40;Optional&#41; If, and only if you have created new files, you SHOULD run)

- Spotless check: `./gradlew -q spotlessCheck`
- If Spotless fails: `./gradlew -q spotlessApply` then re-run `spotlessCheck`


## Documentation Requirements
[//]: # (TODO)

[//]: # (- You MUST update documentation when necessary, following the project’s documentation rules in `.clinerules/docs.md`.)

[//]: # (- Before writing code, you SHOULD analyze relevant code files to get full context, then implement changes with minimal surface area.)

[//]: # (- You SHOULD list assumptions and uncertainties that need clarification before completing a task.)

[//]: # (- You SHOULD check project configuration/build files before proposing structural or dependency changes.)

## Context7 Usage (Documentation and Examples)
If available you MUST use Context7  to get up-to-date, version-specific documentation and code examples for frameworks and libraries.

## Dependency Management (Version Catalogs)

[//]: # (- Main dependencies are managed in the Gradle version catalog at `gradle/libs.versions.toml`.)

[//]: # (- You MUST use catalogs when adding dependencies &#40;avoid hard-coded coordinates/versions in module builds&#41;.)

[//]: # ()
[//]: # (Adding a new dependency &#40;steps&#41;:)

[//]: # ()
[//]: # (1&#41; Choose or add the version in the appropriate catalog &#40;`libs.versions.toml`&#41;.)

[//]: # ()
[//]: # (2&#41; Add an alias under the relevant section &#40;e.g., `libraries`&#41;.)

[//]: # ()
[//]: # (3&#41; Reference the alias from a module’s `build.gradle`, for example:)

[//]: # ()
[//]: # (    - `implementation&#40;libs.some.library&#41;`)

[//]: # ()
[//]: # (    - `testImplementation&#40;testlibs.some.junit&#41;`)

[//]: # ()
[//]: # (4&#41; Do NOT hardcode versions in module build files; use the catalog entries.)

[//]: # ()
[//]: # (You SHOULD choose the appropriate scope depending on the use of the library:)

[//]: # ()
[//]: # (- `api` for dependencies which appear in public signatures or the API of a module)

[//]: # ()
[//]: # (- `implementation` for dependencies which are implementation details, only used in the method bodies for example)

[//]: # ()
[//]: # (- `compileOnly` for dependencies which are only required at build time but not at runtime)

[//]: # ()
[//]: # (- `runtimeOnly` for dependencies which are only required at run time and not at compile time)

## Build logic

[//]: # (Micronaut projects follow Gradle best practices, in particular usage of convention plugins.)

[//]: # (Convention plugins live under the `buildSrc` directory.)

[//]: # ()
[//]: # (You MUST NOT add custom build logic directly in `build.gradle&#40;.kts&#41;` files.)

[//]: # (You MUST implement build logic as part of convention plugins.)

[//]: # (You SHOULD avoid build logic code duplication by moving common build logic into custom convention plugins.)

[//]: # (You SHOULD try to prefer composition of convention plugins.)

## Key Requirements

You MUST confirm all of the following BEFORE using `attempt_completion`:

- Changes compile successfully (affected modules)
- Targeted tests pass

[//]: # (- Full tests for affected modules pass)

- Spotless (`spotlessCheck`) passes (apply fixes if needed)

[//]: # (- Documentation updated when necessary)

- Working tree is clean (no unrelated diffs)

If ANY item is “no”, you MUST NOT use `attempt_completion`.
While you SHOULD add new files using `git add`, you MUST NOT commit (`git commit`) files yourself.
