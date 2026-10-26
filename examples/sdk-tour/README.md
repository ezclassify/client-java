# EzClassify Java SDK Tour

`ClientDemo` walks the Java REST client through ping, validate, process, aggregate, suggest, and guide scan. It is part of the Maven reactor, and its smoke test runs against an in-JVM HTTP stub.

By default it runs against the Cedar Valley replay server, which serves recorded responses. Start the server:

```sh
npx @ezclassify/conformance-server --scenario cedar-valley --port 4010
```

Then run the tour from the repository root, in another terminal:

```sh
./mvnw install -DskipTests --batch-mode --no-transfer-progress
./mvnw -pl examples/sdk-tour exec:java \
    -Dexec.mainClass=com.ezclassify.demo.ClientDemo \
    --batch-mode --no-transfer-progress
```

The first command installs the reactor artifacts into your local Maven repository so the example can resolve its sibling dependencies. The checked-in wrapper supplies Maven 3.10.0; Java 17+ and Node.js 22 or 24 are required to build. The replay server needs Node.js 22 or 24 too; the tour itself does not.

The tour uses inputs the replay server has recorded, so it runs as written against `http://127.0.0.1:4010`, Context `cedar-valley` version `1.0.0`. The replay server answers any request it has not recorded with HTTP 501, so `explain`, scheme `scan` and your own markings need a real runtime.

To run against a real runtime, pass its base URL, Context id and version as arguments and substitute markings that Context recognizes:

```sh
./mvnw -pl examples/sdk-tour exec:java \
    -Dexec.mainClass=com.ezclassify.demo.ClientDemo \
    -Dexec.args="https://runtime.example.com my-context 1.0.0" \
    --batch-mode --no-transfer-progress
```

The tour builds an unauthenticated client, so use a test runtime that permits anonymous requests. For authenticated applications, see [swap to your runtime](../../README.md#swap-to-your-runtime) in the SDK quickstart.
