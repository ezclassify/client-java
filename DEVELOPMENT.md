# Developing the EzClassify Java SDK

Toolchain setup, the build/test command set, conformance running, and the
vendored-spec workflow. For the contribution process (DCO sign-off, issue-first etiquette,
PR expectations), see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Toolchain setup

A clean machine does not have the toolchain. You need a JDK and Maven to build,
plus Node to regenerate sources:

- **JDK 17 or newer.** [Adoptium Temurin](https://adoptium.net/), your distro's
  `openjdk-17-jdk` package, or `brew install openjdk@17` on macOS.
- **Maven 3.10.0.** The checked-in wrapper downloads the reviewed distribution;
  `make` and CI use it by default. An installed Maven can be selected with `make MVN=mvn`.
- **Node.js 22 or 24** on the path, because the `types` module regenerates its records
  during the build. [`tools/package.json`](tools/package.json) declares that floor and CI builds on
  Node 22.

Verify the JDK with `java -version` and the pinned Maven wrapper with
`./mvnw -version`, then run the suite:

```sh
./mvnw -q verify
```

## Building and testing

Everything runs through Maven; the `Makefile` wraps the same targets for parity
with the other SDKs.

| Action | Native command | Notes |
|--------|----------------|-------|
| Build (compile, no tests) | `./mvnw package -DskipTests` | or `make build`. Builds every reactor module. |
| Generate API reference | `make docs` | Aggregate Javadoc for both shipped modules at `target/reports/apidocs/index.html`. |
| Test (all modules) | `./mvnw verify` | or `make test`. `verify` also runs the Spotless format check that `./mvnw test` skips; the architecture and API-surface gates run through `make check-architecture` and `make check-api-surface`. `make test-verbose` runs Surefire without parallel execution, in one fresh fork per test class. |
| Test a single module | `./mvnw test -pl client` | Add `-Dtest=ConformanceTest` to run only the conformance vectors. |
| Regenerate generated sources | `make regen` | Refreshes [`types/`](types/) records after a spec-update PR. There is no local re-vendor target; see below. |
| Package the artifacts | `./mvnw package` | Builds the publishable JARs into each module's `target/`. |
| Install to local Maven repo | `./mvnw install` | Makes the SDK resolvable by a sibling project on this machine. |
| Clean | `make clean` (`make distclean` for the regen toolchain too) | Removes the Maven build output; `distclean` also drops `tools/node_modules`. The vendored spec bundle is never cleaned; it is bot-managed input. |

## API reference

`make docs` uses the pinned Maven wrapper and the existing Javadoc plugin.
It forks the compile lifecycle before creating the aggregate reference so wire
models generated during `generate-sources` are included alongside the authored
client API. It needs the same JDK and Node toolchain as a normal build. The
plugin fails on errors and warnings. Generated HTML stays under ignored
`target/`; tests, the example application, maintenance scripts and package-private
transport/decoder helpers are outside the public reference.

## The vendored spec and upstream engine

- **The spec is vendored, not authored here.** The wire types in
  the [`types/`](types/) module are generated from OpenAPI and JSON Schemas under
  [`vendor/spec/`](vendor/spec/). The Java package is `com.ezclassify.rest.contract`.
  The OpenAPI document is owned by the [ezclassify/contracts](https://github.com/ezclassify/contracts) repo and
  mirrored into this repo by the EzClassify spec bot, which commits as
  `ezclassify-spec-bot[bot]`.
  Don't hand-edit generated types; regenerate them with `make regen` (CI's
  codegen determinism check and clean-tree gate will catch drift either way).
  There is no local command that re-vendors [`vendor/spec/`](vendor/spec/), and there should
  not be: [`.github/workflows/vendor-readonly.yml`](.github/workflows/vendor-readonly.yml) blocks any vendor/ commit
  that the spec bot did not author, so a locally produced bundle could not
  land anyway. To move the spec, change it in [ezclassify/contracts](https://github.com/ezclassify/contracts) and let the spec
  bot propagate it; report spec defects through the [central product tracker](https://github.com/ezclassify/ezclassify/issues/new/choose).
- **Engine behavior is a runtime concern.** This repository contains only REST
  clients and contract types. Report behavioral defects with the endpoint,
  operation, and sanitized request/response through the [central product tracker](https://github.com/ezclassify/ezclassify/issues/new/choose).

## Conformance

The client is conformance-tested against the versioned public contract vectors.

```sh
./mvnw test -pl client -Dtest=ConformanceTest   # HTTP replayer, no server needed
./mvnw test                                      # everything
```

Live deployment acceptance is a separate lane: run `make smoke URL=<server-url>`
against a licensed, content-configured REST deployment. Missing prerequisites
fail that lane; the standalone tests use the committed public recordings.
