# Architecture

This repository contains the EzClassify Java SDK. It adapts the public REST
contract to Java; EzClassify Runtime evaluates marking operations and composes
Contexts. The Maven reactor publishes
the REST contract and an HTTP client. Dependencies flow toward the REST
contract; transport code does not own engine policy.

## Sources of truth

- The [ezclassify/contracts](https://github.com/ezclassify/contracts) repository owns
  canonical wire types, schemas, OpenAPI, and conformance fixtures.
  EzClassify Runtime owns engine behavior and Context composition.
- [`vendor/spec/`](vendor/spec/) is the bot-managed, read-only mirror of those
  contracts. [`vendor/spec/MANIFEST.json`](vendor/spec/MANIFEST.json) records provenance and generator
  inputs. Ordinary SDK work must not edit the mirror.
- [`api-surface.txt`](api-surface.txt) is the reviewed public Java API golden. The semantic and
  conformance fixtures under [`vendor/spec/`](vendor/spec/) define behavior replayed through
  public adapters.

## Generated code

[`types/scripts/gen.mjs`](types/scripts/gen.mjs) consumes the vendored OpenAPI document and JSON Schemas.
Maven runs it during `generate-sources`. Its wire types go to
`types/target/generated-sources/java/com/ezclassify/rest/contract/`. Its one other
output, run from the client module, is the package-private `ResponseShapes`
table in `client/target/generated-sources/java/com/ezclassify/rest/client/`: the
members every success response must carry, read from the OpenAPI `required`
lists. It is not a wire type and is not public. Generated sources are build
artifacts and are never committed or edited by hand.

The generated directory is the sole owner of canonical wire DTOs and wire
enums. The generator recursively follows the reviewed public OpenAPI roots
and fails on unsupported public shapes. It clears its output directory before
each run so removed or renamed contract types cannot survive an incremental
build.

## Response openness

Responses are open; requests are closed. Every schema reachable from a response
tolerates members this SDK does not know, because a server running ahead of a
pinned client adds optional fields and must not break it. `WireJson`'s Gson
configuration ignores unknown members (the Java equivalent of turning Jackson's
`FAIL_ON_UNKNOWN_PROPERTIES` off), and `UnknownResponseFieldConformanceTest`
pins that as the Java side of the cross-SDK conformance case rather than leaving
it to a library default. Requests stay closed: the server rejects unknown
request fields, so this SDK never relies on one being ignored. Removing a field
or changing what it means is a major version.

Enum values are open the same way. Every contract enum is an expandable string
enum in the Azure SDK's shape: a final class extending the handwritten
`ExpandableStringEnum` base, with a public constant per declared value, a
nested `Value` enum (the declared values plus `UNKNOWN`) for `switch`, a
lenient `fromString`, and a strict `fromWire`. It keeps the raw wire token, so
a response token this build does not know decodes instead of failing, and
`toWire()` sends it back unchanged. That round trip is load-bearing: assertion
arrays are the engine's state transport, and the banner rollup feeds processed
portions straight into aggregate. `WireJson` serves every contract enum from one
adapter factory. An enum token that is not a JSON string is a malformed response
rather than an unknown value. Context projections also refuse a required
token that is missing or null. `WireJson` refuses a contract record whose required boolean or number is
missing or null: the generator types those as Java primitives, which have no
absent value, so decoding would otherwise invent `false` or zero.
`UnknownResponseEnumConformanceTest` pins decoding at each kind of position and
the echo back to the server, and `WireEnumContractTest` holds every type in the
package to the codec contract.

Open does not mean incomplete. Before any reader or the response cache sees a
success body, `ResponseCheck` holds it to the operation's shape in
`ResponseShapes`: every member the contract requires is present, at any depth,
and not null unless the contract allows null. A body that fails, or is not
JSON, raises `MalformedResponseException` naming the member and is never cached.
The check judges presence and containers; decoding still judges scalar values.

`ContextJson` delegates generated-record construction to `WireJson`'s named Gson
binding. Its Context projection mode uses the same generated `ResponseShapes`
required-member lists to retain empty required text and result arrays, nullable
optional fields, strict required enum/boolean values, and immutable lists. Only
the existing Context projection record types enroll in that mode; nested source
references, display colors, opaque metadata, and ordinary `WireJson` calls keep
their own decoding rules. The Java-specific `CollectedNotice` name uses the
canonical `Notice` shape. No constructor-component order is duplicated in the
client.

Requests stay closed where the contract puts that check: at the server, which
rejects a value a request may not carry. This SDK does not refuse a value a
response gave it. `fromWire` stays strict for a token a caller types in.

## Handwritten seams

Handwritten files in [`types/src/main/java/com/ezclassify/rest/contract/`](types/src/main/java/com/ezclassify/rest/contract/) are a closed,
reviewed set:

- Encoding boundary: `Utf8Offsets`.
- Java-native projection: `FilterResult`, whose counts are integers where the
  contract types them as JSON numbers.
- Contract enum base: `ExpandableStringEnum`, which every generated contract
  enum extends.
- Public operation support: `ContextOperationResult`, the generic envelope
  every Context operation returns.

[`scripts/check-architecture.mjs`](scripts/check-architecture.mjs) is the executable allowlist and ownership
guard. Adding a handwritten type is an architectural change: update the
generator when the type is a wire contract, or review and document a genuine
Java-specific seam before changing the allowlist.

The [`client/`](client/) Maven module owns only its language boundary: HTTP, strict wire
decoding, version checks, and thin Context operation groups.

## Forbidden ownership

Do not implement parsing, normalization, validation, aggregation, scanning,
rendering, guide evaluation, access decisions, document policy, or Context
composition in the Java adapters. Those operations belong to runtime-owned
implementations.

Do not add handwritten or shadow DTOs for generated wire types, create a second
generated package, patch generated files, or edit [`vendor/spec/`](vendor/spec/) by hand. Do
not make generated types depend on the client.

## Required gates

- `make check-architecture` regenerates types and rejects handwritten allowlist
  drift, shadow declarations, duplicate generated owners, output outside the
  single generated package, or any client-generated source but the
  package-private `ResponseShapes` table.
- `make test` runs the reactor tests, including contract and semantic replay.
- `make check-api-surface` compares the compiled public API with
  [`api-surface.txt`](api-surface.txt); regenerate the golden only for a reviewed API change.
- `mvn spotless:check` enforces Java formatting.
- `node scripts/gen-notices.mjs --check` verifies dependency licenses and the
  committed third-party notices.

Review must reject local engine, policy, or Context-composition behavior even
when all automated gates pass. This repository preserves Java transport
fidelity; it is not an alternate evaluator.
