package com.ezclassify.buildsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the wiring of the vendor/ gate itself.
 *
 * <p>The invariant: {@code vendor-readonly.yml} fires on both {@code push} and {@code
 * pull_request}, watches an identical path set on both triggers, and fails with a message that
 * tells a blocked contributor what to do. A gate that only runs on one trigger has a blind spot on
 * the other. These assertions run in the ordinary {@code mvn test} suite so a regression is loud
 * locally, without waiting on a CI runner. They live in this build-support module rather than in a
 * published module's test sources: the subject is repository policy, not SDK behavior, and the YAML
 * parser they need has no business in the HTTP client's test closure.
 */
class VendorReadonlyWorkflowTest {
  /**
   * A {@code git log -1} carrying a {@code vendor/} pathspec, on one line or across one shell line
   * continuation. That form walks: it reports the nearest ancestor touching the pathspec rather
   * than the commit named on the command line. Deliberately does not match the bare {@code git log
   * -1 --format=...} lookups the gate uses for author and subject, which carry no pathspec.
   */
  private static final Pattern WALKING_LOG_OVER_VENDOR_PATHS =
      Pattern.compile("git log\\s+-1\\b[^\\n]*?(?:\\\\\\s*\\n\\s*)?--\\s+'vendor/");

  private static final Path WORKFLOW =
      repositoryRoot().resolve(".github/workflows/vendor-readonly.yml");

  /**
   * Walk up from the module directory to the repository root. Maven sets {@code user.dir} to the
   * module basedir, and this module's depth under the root is a build-layout detail the test should
   * not encode.
   */
  private static Path repositoryRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
      if (Files.isDirectory(candidate.resolve(".github/workflows"))) return candidate;
    }
    throw new IllegalStateException("no repository root above " + dir);
  }

  private static String workflow() throws Exception {
    return Files.readString(WORKFLOW);
  }

  @Test
  void firesOnPushAndNotOnlyOnPullRequest() throws Exception {
    String source = workflow();
    assertTrue(
        Pattern.compile("^ {2}pull_request:$", Pattern.MULTILINE).matcher(source).find(),
        "vendor-readonly.yml lost its pull_request trigger");
    assertTrue(
        Pattern.compile("^ {2}push:$", Pattern.MULTILINE).matcher(source).find(),
        "vendor-readonly.yml lost its push trigger; a vendor/ commit landed by direct push would"
            + " reach the branch unchecked");
  }

  @Test
  void watchesTheSamePathsOnBothTriggers() throws Exception {
    List<List<String>> pathBlocks = new ArrayList<>();
    Matcher blocks =
        Pattern.compile("^ {4}paths:\\n((?: {6}- .*\\n)+)", Pattern.MULTILINE).matcher(workflow());
    while (blocks.find()) {
      List<String> globs = new ArrayList<>();
      for (String line : blocks.group(1).split("\n")) {
        globs.add(line.trim().substring(2).replace("'", ""));
      }
      pathBlocks.add(globs);
    }
    assertEquals(2, pathBlocks.size(), "expected one paths block per trigger");
    assertEquals(
        pathBlocks.get(0),
        pathBlocks.get(1),
        "the push and pull_request triggers watch different paths, so one of them has a blind spot");
  }

  @Test
  void resolvesAnUnusablePushBaseByAncestryRatherThanStringInequality() throws Exception {
    // `--is-ancestor` appears twice in this workflow. Only the negated one guards the
    // default-branch fallback, so assert that specific expression rather than the mere presence of
    // the flag: a `!=` SHA comparison passes whenever the pushed tip is already an ancestor of the
    // default branch, `origin/<default>..HEAD` then resolves EMPTY, and the gate reports a clean
    // pass having inspected nothing.
    assertTrue(
        workflow().contains("! git merge-base --is-ancestor \"$HEAD_SHA\" \"$FALLBACK\""),
        "the default-branch fallback must reject an empty range by ancestry, not by SHA"
            + " inequality");
  }

  @Test
  void readsTheSingleCommitFallbackWithDiffTreeRatherThanAWalkingLog() throws Exception {
    // `git log -1 <sha> -- <pathspec>` walks to the nearest ancestor that touched the pathspec, so
    // with an unresolvable range it would report and pass a commit that was not in the push at all.
    // `git diff-tree` asks only what THIS commit changed.
    String source = workflow();
    assertTrue(
        source.contains("git diff-tree --no-commit-id --name-only -r"),
        "the single-commit fallback must read the tip with diff-tree");
    assertTrue(
        !WALKING_LOG_OVER_VENDOR_PATHS.matcher(source).find(),
        "the gate reads vendor/ paths through `git log -1`, which walks past the commit it claims"
            + " to be inspecting and can judge a commit that was never in the push");
  }

  @Test
  void refusesRatherThanPassesWhenThePushSetIsUnreadable() throws Exception {
    // The workflow only runs at all because the push touched a watched path. If the commit set it
    // can read then contains nothing touching vendor/, the set it read is not the set that was
    // pushed, and exiting 0 there would report green having inspected nothing.
    String source = workflow();
    assertTrue(
        source.contains("Refusing to pass."),
        "an unreadable push commit set must fail loudly rather than exit 0");
    assertTrue(
        Pattern.compile(
                "\\[ \"\\$EVENT_NAME\" != \"pull_request\" \\] && \\[ \"\\$RANGE_MODE\" ="
                    + " \"single\" \\]")
            .matcher(source)
            .find(),
        "the refusal must be conditioned on the push path with an unbounded range; without that"
            + " guard it either never fires or blocks legitimate pull-request re-runs");
  }

  @Test
  void doesNotRefuseTheSpecBotsBranchCreationPush() throws Exception {
    // The spec bot does `git checkout -b chore/spec-update-<sha>` then `git push -u origin HEAD`,
    // and GitHub reports before=000... for that. An unconditional refusal of branch creation
    // would block the sanctioned actor, so the all-zeros case has to fall through to the
    // default-branch fallback rather than exit 1.
    String source = workflow();
    assertTrue(
        source.contains("branch creation: this push has no pre-push commit"),
        "branch creation must be handled as a narrowing, not an error");
    // Stronger than pinning a phrase that is already absent: the range resolver must never refuse
    // anything at all. Deciding what is allowed belongs to the enforcement step, which has the
    // commit set in hand; a refusal in the resolver can only be a blanket one, and the blanket
    // case that matters is the spec bot's branch-creation push.
    int resolver = source.indexOf("- name: Resolve the commit range");
    int enforcer = source.indexOf("- name: Enforce bot identity");
    assertTrue(resolver >= 0 && enforcer > resolver, "the two gate steps are no longer in order");
    assertTrue(
        !source.substring(resolver, enforcer).contains("exit 1"),
        "the range-resolution step refuses a push outright; it cannot know yet whether the push is"
            + " legitimate, and the spec bot's branch-creation push is the one it would block");
  }

  @Test
  void refusalNamesTheSpecBotAndTheContractsSource() throws Exception {
    // A blocked contributor needs to know who writes the mirror and where the spec comes from.
    String source = workflow();
    assertTrue(source.contains("the EzClassify spec bot"), "the refusal names the writer");
    assertTrue(source.contains("ezclassify/contracts"), "the refusal names the spec source");
  }

  @Test
  void isNotDisarmed() throws Exception {
    String source = workflow();
    assertTrue(
        !source.contains("continue-on-error"), "a gate with continue-on-error is not a gate");
    assertTrue(!source.contains("if: always()"), "if: always() would let the gate pass on failure");
  }

  /**
   * Runs the gate's own shell against real git history.
   *
   * <p>Every other test in this class reads the workflow as text. Text assertions cannot prove what
   * the gate compares: a workflow can log "every vendor/ commit authored by
   * ezclassify-spec-bot[bot]" while comparing only the author name, or log "Identity gate: PR
   * authored by ..." as though that settled who wrote the commits. So these cases extract the
   * literal {@code run:} bodies, build real repositories, and read the exit status.
   *
   * <p>The refusal cases and the admission cases are both load-bearing. A gate that refused
   * everything would satisfy every "must refuse" assertion here while being useless, and a gate
   * people must switch off is a dead gate; the {@code genuine...} cases keep the refusals honest.
   */
  @Nested
  class GateBehavior {
    private static final String BOT_NAME = "ezclassify-spec-bot[bot]";
    private static final String BOT_EMAIL = "ezclassify-spec-bot[bot]@users.noreply.github.com";
    private static final String SPEC_SUBJECT = "chore(spec): vendor spec bundle from contracts@0";
    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";

    // A stand-in maintainer for the human-author path. Only the fact that the identity is not the
    // spec bot matters to the gate, so the fixture does not name a real person.
    private static final String HUMAN_NAME = "Alex Maintainer";
    private static final String HUMAN_EMAIL = "alex.maintainer@example.com";
    private static final String HUMAN_LOGIN = "alex-maintainer";

    /** The run body of the step whose name starts with the given prefix. */
    @SuppressWarnings("unchecked")
    private String runBody(String namePrefix) throws Exception {
      Map<String, Object> doc = new Yaml().load(Files.readString(WORKFLOW, StandardCharsets.UTF_8));
      Map<String, Object> jobs = (Map<String, Object>) doc.get("jobs");
      Map<String, Object> job = (Map<String, Object>) jobs.get("check-vendor-changes");
      for (Object raw : (List<Object>) job.get("steps")) {
        Map<String, Object> step = (Map<String, Object>) raw;
        Object name = step.get("name");
        if (name != null && name.toString().startsWith(namePrefix) && step.get("run") != null) {
          String body = step.get("run").toString();
          // A ${{ }} expression left inside a run body would evaluate to empty
          // under this harness, and a "forged email is refused" case could then
          // pass because the comparison degenerated rather than because the gate
          // works. The workflow passes GitHub context through `env:` instead;
          // hold it to that.
          assertTrue(
              !body.contains("${{"),
              "step '"
                  + namePrefix
                  + "' inlines a GitHub expression in its run body; this"
                  + " harness cannot evaluate one, so the case would prove nothing");
          return body;
        }
      }
      throw new IllegalStateException("no run step named " + namePrefix);
    }

    /** The literal (non-expression) env of a step: the gate's own constants. */
    @SuppressWarnings("unchecked")
    private Map<String, String> literalEnv(String namePrefix) throws Exception {
      Map<String, Object> doc = new Yaml().load(Files.readString(WORKFLOW, StandardCharsets.UTF_8));
      Map<String, Object> jobs = (Map<String, Object>) doc.get("jobs");
      Map<String, Object> job = (Map<String, Object>) jobs.get("check-vendor-changes");
      Map<String, String> out = new LinkedHashMap<>();
      for (Object raw : (List<Object>) job.get("steps")) {
        Map<String, Object> step = (Map<String, Object>) raw;
        Object name = step.get("name");
        if (name == null || !name.toString().startsWith(namePrefix)) {
          continue;
        }
        Map<String, Object> env = (Map<String, Object>) step.get("env");
        if (env == null) {
          continue;
        }
        for (Map.Entry<String, Object> e : env.entrySet()) {
          String v = String.valueOf(e.getValue());
          // Only the workflow's own constants. The identity the gate enforces is
          // therefore the one in the file, never one this test supplies.
          if (!v.contains("${{")) {
            out.put(e.getKey(), v);
          }
        }
      }
      return out;
    }

    private String sh(Path cwd, String... cmd) throws Exception {
      ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
      pb.redirectErrorStream(true);
      Process p = pb.start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int rc = p.waitFor();
      if (rc != 0) {
        throw new IOException("command failed (" + rc + "): " + String.join(" ", cmd) + "\n" + out);
      }
      return out.trim();
    }

    /**
     * A repository with one base commit that does not touch vendor/, an origin/main ref pointing at
     * it, and one vendor/-touching commit on top authored as asked.
     */
    private Path repoWithVendorCommit(Path dir, String author, String email, String subject)
        throws Exception {
      sh(dir, "git", "init", "-q", "-b", "main", ".");
      sh(dir, "git", "config", "commit.gpgsign", "false");
      Files.writeString(dir.resolve("README.md"), "base\n");
      sh(dir, "git", "add", "README.md");
      sh(
          dir,
          "git",
          "-c",
          "user.name=Base",
          "-c",
          "user.email=base@example.com",
          "commit",
          "-q",
          "-m",
          "chore: base");
      // origin/main without a network: the branch-creation path resolves its
      // fallback range against this ref.
      sh(dir, "git", "update-ref", "refs/remotes/origin/main", sh(dir, "git", "rev-parse", "HEAD"));

      Files.createDirectories(dir.resolve("vendor"));
      Files.writeString(dir.resolve("vendor/SPEC.md"), "spec " + subject + "\n");
      sh(dir, "git", "add", "vendor/SPEC.md");
      sh(
          dir,
          "git",
          "-c",
          "user.name=" + author,
          "-c",
          "user.email=" + email,
          "commit",
          "-q",
          "-m",
          subject);
      return dir;
    }

    /** Exit status of the whole gate, running both steps the way Actions chains them. */
    private int runGate(Path repo, Map<String, String> scenario) throws Exception {
      Path work = Files.createTempDirectory("gate");
      Path range = work.resolve("range.sh");
      Path enforce = work.resolve("enforce.sh");
      Files.writeString(range, runBody("Resolve the commit range"));
      Files.writeString(enforce, runBody("Enforce bot identity"));
      Path ghOutput = work.resolve("gh_output");
      Files.writeString(ghOutput, "");

      Map<String, String> env = new LinkedHashMap<>();
      for (String k :
          List.of(
              "EVENT_NAME",
              "PR_BASE_SHA",
              "PR_HEAD_SHA",
              "PUSH_BEFORE_SHA",
              "PUSH_AFTER_SHA",
              "PUSH_FORCED",
              "DEFAULT_BRANCH",
              "PR_AUTHOR",
              "PR_LABELS")) {
        env.put(k, "");
      }
      env.putAll(scenario);

      int rc = bash(repo, range, env, ghOutput);
      if (rc != 0) {
        return rc;
      }
      Map<String, String> outputs = new LinkedHashMap<>();
      for (String line : Files.readString(ghOutput).split("\n")) {
        int eq = line.indexOf('=');
        if (eq > 0) {
          outputs.put(line.substring(0, eq), line.substring(eq + 1));
        }
      }
      // The enforcement step carries `if: steps.range.outputs.mode != 'deleted'`.
      if ("deleted".equals(outputs.get("mode"))) {
        return 0;
      }
      env.put("RANGE_MODE", outputs.getOrDefault("mode", ""));
      env.put("BASE_SHA", outputs.getOrDefault("base", ""));
      env.put("HEAD_SHA", outputs.getOrDefault("head", ""));
      env.putAll(literalEnv("Enforce bot identity"));
      return bash(repo, enforce, env, ghOutput);
    }

    private int bash(Path repo, Path script, Map<String, String> env, Path ghOutput)
        throws Exception {
      ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
      pb.directory(repo.toFile());
      pb.environment().putAll(env);
      pb.environment().put("GITHUB_OUTPUT", ghOutput.toString());
      pb.redirectErrorStream(true);
      Process p = pb.start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      int rc = p.waitFor();
      System.out.println("--- " + script.getFileName() + " (exit " + rc + ") ---");
      System.out.println(out);
      return rc;
    }

    private Map<String, String> push(Path repo, String before) throws Exception {
      Map<String, String> m = new LinkedHashMap<>();
      m.put("EVENT_NAME", "push");
      m.put("PUSH_BEFORE_SHA", before);
      m.put("PUSH_AFTER_SHA", sh(repo, "git", "rev-parse", "HEAD"));
      m.put("DEFAULT_BRANCH", "main");
      return m;
    }

    private Map<String, String> pullRequest(Path repo, String prAuthor) throws Exception {
      Map<String, String> m = new LinkedHashMap<>();
      m.put("EVENT_NAME", "pull_request");
      m.put("PR_BASE_SHA", sh(repo, "git", "rev-parse", "HEAD^"));
      m.put("PR_HEAD_SHA", sh(repo, "git", "rev-parse", "HEAD"));
      m.put("PR_AUTHOR", prAuthor);
      m.put("DEFAULT_BRANCH", "main");
      return m;
    }

    // ---------- Forgeable identity ----------

    @Test
    void refusesACommitCarryingTheBotNameUnderAnotherEmailOnPush(@TempDir Path dir)
        throws Exception {
      // Git author name is free text chosen by whoever makes the commit, so a
      // name-only comparison is not an identity check at all. This commit is
      // shaped to satisfy every other gate: bot name, `chore(spec):` subject.
      // Only the email betrays it.
      Path repo = repoWithVendorCommit(dir, BOT_NAME, "mallory@evil.example", SPEC_SUBJECT);
      assertNotEquals(
          0,
          runGate(repo, push(repo, sh(repo, "git", "rev-parse", "HEAD^"))),
          "a commit authored '"
              + BOT_NAME
              + " <mallory@evil.example>' was admitted; the identity"
              + " gate is comparing the author name only, which anyone can set");
    }

    @Test
    void refusesACommitCarryingTheBotNameUnderAnotherEmailInAPullRequest(@TempDir Path dir)
        throws Exception {
      Path repo = repoWithVendorCommit(dir, BOT_NAME, "mallory@evil.example", SPEC_SUBJECT);
      assertNotEquals(
          0,
          runGate(repo, pullRequest(repo, BOT_NAME)),
          "forged author email admitted on the pull_request path");
    }

    // ---------- The PR path must re-check each commit ----------

    @Test
    void refusesAHumanAuthoredCommitInsideABotOpenedPullRequest(@TempDir Path dir)
        throws Exception {
      // Who opened the PR and who wrote the commits are different questions.
      // A gate that answered only the first would let a PR opened by the spec
      // bot carry any commit at all.
      Path repo = repoWithVendorCommit(dir, "Mallory Human", "mallory@evil.example", SPEC_SUBJECT);
      assertNotEquals(
          0,
          runGate(repo, pullRequest(repo, BOT_NAME)),
          "a human-authored vendor/ commit rode in on a bot-opened PR; the pull_request path is"
              + " trusting the PR author instead of re-reading the commits");
    }

    // ---------- The gate must still admit the sanctioned actor ----------

    @Test
    void admitsAGenuineBotCommitOnPush(@TempDir Path dir) throws Exception {
      Path repo = repoWithVendorCommit(dir, BOT_NAME, BOT_EMAIL, SPEC_SUBJECT);
      assertEquals(
          0,
          runGate(repo, push(repo, sh(repo, "git", "rev-parse", "HEAD^"))),
          "the gate refused a genuine spec-bot commit; a gate that blocks the sanctioned actor"
              + " gets switched off");
    }

    @Test
    void admitsAGenuineBotCommitOnABranchCreationPush(@TempDir Path dir) throws Exception {
      // The spec bot creates chore/spec-update-<sha> and pushes it, and GitHub
      // reports before=000... for that.
      Path repo = repoWithVendorCommit(dir, BOT_NAME, BOT_EMAIL, SPEC_SUBJECT);
      assertEquals(
          0,
          runGate(repo, push(repo, ZERO_SHA)),
          "the gate refused the branch-creation push that the spec bot makes");
    }

    @Test
    void admitsAGenuineBotCommitInABotOpenedPullRequest(@TempDir Path dir) throws Exception {
      Path repo = repoWithVendorCommit(dir, BOT_NAME, BOT_EMAIL, SPEC_SUBJECT);
      assertEquals(
          0,
          runGate(repo, pullRequest(repo, BOT_NAME)),
          "the gate refused a genuine" + " spec-bot commit on a bot-opened PR");
    }

    // ---------- The maintainer override, and its limit ----------

    @Test
    void admitsAHumanPullRequestCarryingTheOverrideLabel(@TempDir Path dir) throws Exception {
      Path repo = repoWithVendorCommit(dir, HUMAN_NAME, HUMAN_EMAIL, SPEC_SUBJECT);
      Map<String, String> pr = pullRequest(repo, HUMAN_LOGIN);
      pr.put("PR_LABELS", "spec-mirror-override");
      assertEquals(
          0,
          runGate(repo, pr),
          "the documented maintainer override no longer works; without a usable override the"
              + " only way to land an emergency vendor/ edit is to disable this workflow");
    }

    @Test
    void doesNotLetTheOverrideLabelBypassThePushPath(@TempDir Path dir) throws Exception {
      // The refusal text tells operators "there is no push-path bypass". A label
      // is a pull-request object, and PR_LABELS is empty on a push only because
      // of what the github.event.pull_request context evaluates to there.
      // Resting the claim on that would make it true by accident of the
      // platform rather than by this gate.
      Path repo = repoWithVendorCommit(dir, BOT_NAME, "mallory@evil.example", SPEC_SUBJECT);
      Map<String, String> p = push(repo, sh(repo, "git", "rev-parse", "HEAD^"));
      p.put("PR_LABELS", "spec-mirror-override");
      assertNotEquals(
          0,
          runGate(repo, p),
          "the override label bypassed the identity gate on a push, which the gate's own refusal"
              + " message says is impossible");
    }
  }
}
