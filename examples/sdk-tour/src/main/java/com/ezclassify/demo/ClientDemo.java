package com.ezclassify.demo;

import com.ezclassify.rest.client.ContextScheme;
import com.ezclassify.rest.client.EzClassifyClient;
import com.ezclassify.rest.client.EzClassifyClientException;
import com.ezclassify.rest.client.EzClassifyContext;
import com.ezclassify.rest.client.FactsSuggestOptions;
import com.ezclassify.rest.client.PingResult;
import com.ezclassify.rest.contract.AggregateResult;
import com.ezclassify.rest.contract.GuideScanResult;
import com.ezclassify.rest.contract.ProcessResult;
import com.ezclassify.rest.contract.Suggestion;
import com.ezclassify.rest.contract.ValidateResult;
import java.util.List;

/**
 * Runnable demo for the EzClassify Java HTTP client.
 *
 * <p>This walks the reader's call (validate), the author's call (process, with its notices and
 * findings), a portion rollup (aggregate), picker options (suggest) and a guide scan against a
 * running EzClassify REST API, using only the public {@link EzClassifyClient} API, so it doubles as
 * a copy-paste quickstart for the {@code client} artifact.
 *
 * <p>By default it targets the Cedar Valley replay server, which serves recorded responses. Every
 * input below is one the replay server has recorded; it answers any other request with HTTP 501.
 * Start it, install the reactor artifacts locally, then run from the repo root:
 *
 * <pre>
 *   npx @ezclassify/conformance-server --scenario cedar-valley --port 4010
 *
 *   ./mvnw install -DskipTests --batch-mode --no-transfer-progress
 *   ./mvnw -pl examples/sdk-tour exec:java \
 *       -Dexec.mainClass=com.ezclassify.demo.ClientDemo \
 *       --batch-mode --no-transfer-progress
 * </pre>
 *
 * <p>To run against a real runtime instead, pass its base URL, Context id and version as arguments
 * (for example {@code -Dexec.args="https://runtime.example.com my-context 1.0.0"}) and substitute
 * markings that Context recognizes.
 */
public class ClientDemo {

  /** Base URL used when none is passed on the command line: the Cedar Valley replay server. */
  public static final String DEFAULT_BASE_URL = "http://127.0.0.1:4010";

  /** Context id used when none is passed on the command line. */
  public static final String DEFAULT_CONTEXT_ID = "cedar-valley";

  /** Context version used when none is passed on the command line. */
  public static final String DEFAULT_CONTEXT_VERSION = "1.0.0";

  private final String baseUrl;
  private final String contextId;
  private final String contextVersion;

  /**
   * Create a demo bound to one REST API server and one published Context version.
   *
   * @param baseUrl base URL of a running EzClassify REST API, e.g. {@code http://127.0.0.1:4010}
   * @param contextId Context id every operation is sent against, e.g. {@code cedar-valley}
   * @param contextVersion the Context version to select, e.g. {@code 1.0.0}
   * @throws IllegalArgumentException if any argument is null or blank
   */
  public ClientDemo(String baseUrl, String contextId, String contextVersion) {
    if (baseUrl == null || baseUrl.isBlank())
      throw new IllegalArgumentException("baseUrl must not be empty");
    if (contextId == null || contextId.isBlank())
      throw new IllegalArgumentException("contextId must not be empty");
    if (contextVersion == null || contextVersion.isBlank())
      throw new IllegalArgumentException("contextVersion must not be empty");
    this.baseUrl = baseUrl;
    this.contextId = contextId;
    this.contextVersion = contextVersion;
  }

  /** Prints a section header to stdout. */
  private static void section(String title) {
    System.out.println("\n=== " + title + " ===");
  }

  /**
   * Runs the demo workflow against the configured server.
   *
   * @throws EzClassifyClientException if the server is unreachable or an endpoint returns an error
   *     status
   */
  public void run() {
    System.out.println("EzClassify Java Client Demo");
    System.out.println("===========================");
    System.out.println("server: " + baseUrl + "  context: " + contextId + "@" + contextVersion);

    try (EzClassifyClient client = new EzClassifyClient(baseUrl)) {

      // 1. Ping the server and compare versions
      section("1. Ping");
      PingResult ping = client.ping();
      System.out.printf(
          "  server engine version: %s (sha %s, tag %s)%n",
          ping.engineVersion(), ping.engineSha(), ping.gitTag());
      System.out.printf("  client built against spec: %s%n", client.engineSpecVersion());
      System.out.printf("  available contexts: %s%n", client.listContexts());
      EzClassifyContext context = client.context(contextId, contextVersion);
      ContextScheme scheme = context.scheme();

      // 2. Validate: the reader's call. Code that displays or gates on a marking asks whether it
      //    stands (valid) and whether it was right as written (inputWasValid).
      section("2. Validate markings (reading)");
      String[] toValidate = {"CONFIDENTIAL", "INTERNAL", "UNRECOGNIZED"};
      for (String v : toValidate) {
        ValidateResult r = scheme.validate(v).result();
        if (r.errors().isEmpty()) {
          System.out.printf(
              "  validate(%s) -> valid=%b  inputWasValid=%b  rendered=%s%n",
              repr(v), r.valid(), r.inputWasValid(), r.rendered());
        } else {
          System.out.printf(
              "  validate(%s) -> %d error(s): %s%n",
              repr(v), r.errors().size(), r.errors().get(0).message());
        }
      }

      // 3. Process: the author's call. One request returns the verdicts, the fixes the engine
      //    applied or proposes, and the notices the marking carries.
      section("3. Process a marking (writing)");
      for (String input : new String[] {"CONFIDENTIAL", "UNRECOGNIZED"}) {
        ProcessResult draft = scheme.process(input).result();
        System.out.printf(
            "  process(%s) -> rendered=%s  valid=%b  inputWasValid=%b  notices=%d%n",
            repr(input),
            repr(draft.rendered()),
            draft.valid(),
            draft.inputWasValid(),
            draft.notices().size());
        draft.errors().forEach(e -> System.out.printf("    error: %s%n", e.message()));
        draft
            .notices()
            .forEach(n -> System.out.printf("    notice [%s]: %s%n", n.type().toWire(), n.text()));
      }

      // 4. Aggregate two portion markings into a banner
      section("4. Aggregate (portion rollup)");
      List<String> portions =
          List.of(
              "INTERNAL//OAKWOOD/PINECREST//STUDENT TRANSPORTATION SERVICES//DO NOT FORWARD",
              "CONFIDENTIAL//FINANCIAL INFORMATION//OAKWOOD/PINECREST//REDACT BEFORE RELEASE");
      AggregateResult agg = scheme.aggregate(portions).result();
      System.out.printf(
          "  aggregate(%d portions) -> %s  notices=%d%n",
          portions.size(), agg.rendered(), agg.notices().size());

      // 5. Suggest: the options a picker may offer for a marking with nothing chosen yet
      section("5. Suggest (picker options)");
      List<Suggestion> suggestions =
          scheme
              .suggest(List.of(), FactsSuggestOptions.builder().outputFormat("long").build())
              .result()
              .suggestions();
      System.out.printf("  suggest(no assertions) -> %d option(s)%n", suggestions.size());
      for (Suggestion s : suggestions.stream().limit(6).toList()) {
        System.out.printf("    - %s (%s)%n", s.label(), s.conceptId());
      }

      // 6. Scan free text against the guide. Matches are nominations for a person to review.
      section("6. Guide scan");
      String text =
          "Oakwood supplier bid. Share with Student Transportation Services and redact before release.";
      List<GuideScanResult> matches = context.guide().scan(text).result();
      System.out.printf("  scan(%s) -> %d rule match(es)%n", repr(text), matches.size());
      for (GuideScanResult m : matches) {
        System.out.printf(
            "    - %s matched %s%n", m.rule().name(), String.join(", ", m.matchedKeywords()));
      }
    }

    System.out.println("\nClient demo complete.");
  }

  /**
   * Entry point. Optional arguments: base URL (default {@value #DEFAULT_BASE_URL}), Context id
   * (default {@value #DEFAULT_CONTEXT_ID}) and Context version (default {@value
   * #DEFAULT_CONTEXT_VERSION}).
   *
   * @param args {@code [baseUrl] [contextId] [contextVersion]}
   */
  public static void main(String[] args) {
    String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE_URL;
    String contextId = args.length > 1 ? args[1] : DEFAULT_CONTEXT_ID;
    String contextVersion = args.length > 2 ? args[2] : DEFAULT_CONTEXT_VERSION;
    try {
      new ClientDemo(baseUrl, contextId, contextVersion).run();
    } catch (EzClassifyClientException e) {
      System.err.println("Client demo failed: " + e.getMessage());
      System.err.println(
          "Is the Cedar Valley replay server, or a REST API serving Context '"
              + contextId
              + "' version "
              + contextVersion
              + ", running at "
              + baseUrl
              + "?");
      System.exit(1);
    }
  }

  /** Returns a debug representation of a string (quotes, shows empty). */
  private static String repr(String s) {
    if (s == null) return "null";
    if (s.isEmpty()) return "\"\"";
    return "\"" + s + "\"";
  }
}
