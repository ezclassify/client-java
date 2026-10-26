# Agent Instructions

## Standards

Read [`STANDARDS.md`](./STANDARDS.md) before any feature, refactor, architectural decision, or task > 30 minutes. These engineering standards apply to you as they do to any contributor. Skip for: quick bug fixes, typos, documentation-only changes.

Read [`ARCHITECTURE.md`](./ARCHITECTURE.md) before architectural work. It is the
ownership contract for generated wire code, handwritten Java seams, dependency
direction, and the required boundary gates.

## Reactor layout

[`pom.xml`](pom.xml) is the parent reactor. Modules:

- [`types/`](types/): REST contract records generated from OpenAPI and JSON Schemas
  under [`vendor/spec/`](vendor/spec/), plus the small Java-native contract projections.
- [`client/`](client/): Context-only HTTP client for the EzClassify REST API. The
  server-resolved Context selects the composed scheme and related policy
  artifacts.
- [`examples/sdk-tour/`](examples/sdk-tour/): runnable example exercising the HTTP client
  (`ClientDemo`). Lives inside the reactor so client changes that break the
  demo fail naturally in `make test`.
- [`build-support/repo-policy/`](build-support/repo-policy/): test sources only, asserting repository policy
  rather than published behavior. Nothing here is published, and no published
  module carries a dependency for its sake.

Build verbs (top-level Makefile):

- `make regen` installs the pinned YAML parser into `tools/node_modules/`
  and regenerates `types/target/generated-sources` from [`vendor/spec/`](vendor/spec/).
  It does not fetch the spec; [`vendor/spec/`](vendor/spec/) is written only by the
  spec bot.
- `make build` runs `mvn package -DskipTests` across every module
  (including the demo).
- `make test` runs `mvn verify` across every module.
- `make api-surface` regenerates [`api-surface.txt`](api-surface.txt), the committed golden
  of the public API (locked at 1.0). CI diffs the current surface against
  it via `make check-api-surface`, so deliberate API changes must
  regenerate the golden and commit it with the change.
