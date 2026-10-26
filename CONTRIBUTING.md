# Contributing to EzClassify Java SDK

Thanks for your interest in contributing. This page is the process contract. For
toolchain setup, the build/test command set, conformance running, and the
vendored-spec workflow, see [`DEVELOPMENT.md`](DEVELOPMENT.md).

## Before You Start

1. **Sign off your commits.** Contributions are accepted under the
   [Developer Certificate of Origin](https://github.com/ezclassify/ezclassify/blob/main/DCO.md). There is nothing to sign and no
   agreement to return: `git commit -s` adds the `Signed-off-by` line that
   certifies you have the right to submit the work. CI checks every commit a
   pull request adds, and a pull request whose commits are not all signed off
   cannot be merged.
2. **Fork the repository.** All contributions come through pull requests from
   forks.
3. **Open an issue first** for non-trivial changes.

## Pull Request Expectations

- **Bring tests.** New behavior needs tests; a bug fix needs a regression test
  that fails without the fix. `mvn verify` runs the suite.
- **Conformance plans define parity.** The client is conformance-tested against
  the same vectors as the TypeScript engine. A change
  that breaks a conformance plan needs the plan changed in the [ezclassify/contracts](https://github.com/ezclassify/contracts) repository first (see
  [`DEVELOPMENT.md`](DEVELOPMENT.md)).
- **Read the standards.** Engineering standards are in
  [`STANDARDS.md`](STANDARDS.md); read it before any significant work.

## Commit Messages

Conventional commits (`fix:`, `feat:`, `docs:`). Every commit also carries a
`Signed-off-by` line; see the [DCO text](https://github.com/ezclassify/ezclassify/blob/main/DCO.md).

## Security

Do not open public issues for vulnerabilities. Follow
the [security policy](https://github.com/ezclassify/ezclassify/blob/main/SECURITY.md) for the disclosure process.

## Code of Conduct

This project follows the [Code of Conduct](https://github.com/ezclassify/ezclassify/blob/main/CODE_OF_CONDUCT.md). By
participating, you agree to uphold it.
