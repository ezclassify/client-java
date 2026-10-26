# Engineering Standards

Design, code quality, testing, and decision-making rules for this project. All contributors follow these.

---

## Design

- **Readability first; performance when it matters.** If a less-readable path has significant performance gains, take it, but document why with a comment explaining the tradeoff.
- **Purist design over quick fixes.** Invest in correct abstractions upfront. Architectural debt that compounds should be prevented, not queued.
- **Interfaces over concrete types.** Depend on interfaces and abstractions, never concrete classes. Factories accept implementations; they don't construct them across layers.
- **Own the blast radius of your changes.** When you change something, know the full ripple and address it in the same PR. Don't leave broken dependents "to fix later" without an explicit decision to do so.

## Code Quality

- **Javadoc on the public API.** Every public class, interface, record, and method gets Javadoc for purpose and contract, not a description of what the code does. Document parameters, return values, and thrown exceptions; the published modules ship a javadoc jar, so this is user-facing reference material.

- **Comments explain WHY, not WHAT.** Only comment when the next developer might not understand why you didn't do the obvious thing. Explain constraints, workarounds, hidden dependencies, not logic flow.

- **Formatting is google-java-format, enforced by Spotless.** `mvn verify` fails on drift; run `mvn spotless:apply` before committing. Never hand-align code or argue style in review; the formatter is the style guide.
- **Records for data carriers, interfaces for boundaries.** Immutable domain data is a `record` with compact canonical constructors for validation. Behavior contracts are interfaces; concrete implementations stay out of API signatures.
- **Generated and handwritten code never mix.** Generated types land in `types/target/generated-sources` via `make regen` and are never committed or hand-edited. Anything that must diverge from the generator's output belongs in a handwritten class in `src/main/java`.
- **Zero trust at public boundaries.** Validate all inputs on the public API and external API responses; fail fast with clear exceptions at the boundary. Trust internal calls between private methods.
- **No warnings left behind.** Compile clean. Remove unused imports and dead members instead of suppressing them; use `@SuppressWarnings` only with a comment saying why the warning is wrong here.

## Testing

- **Order: Contract, then tests, then implementation.** Write the Javadoc contract first, tests second, implementation third. Tests are specs, not afterthoughts.
- **One responsibility per method.** Single purpose, a clear contract, no side effects unless that's the method's entire point.
- **Test behavior, not coverage percentages.** Each test verifies a specific path: happy path, bad path, or defensive guard. Skip tests that add no value; a compile-time fact restated as an assertion is not a test.
- **Separate test types strictly.**
  - **Unit:** JUnit 5 against a class in isolation; no network, no engine bundle.
  - **Integration:** exercise the public module API against real collaborators, such as the in-JVM stub HTTP server; no mocks of internal classes.
  - **Conformance:** the shared cross-SDK vectors, run by the `ConformanceTest` classes; they pin wire-level behavior against the reference engine.
- **Bug fixes require regression tests.** Every fix needs a test that fails before the fix and passes after. No exceptions.

## Decision-Making

- **Stable contracts, incremental changes.** Preserve published API compatibility. Prefer incremental, reversible changes and follow semantic versioning for breaking changes.
- **Inline small debt, backlog large debt.** Found debt under 30 min effort: fix inline. Larger refactors, dependency upgrades, or architectural changes: log to backlog, don't auto-execute.
- **Don't let tasks balloon.** If you discover work that significantly exceeds the original scope, stop, log it, and stay on scope. Surface it for explicit prioritization.
