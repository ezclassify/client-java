import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const script = path.join(path.dirname(fileURLToPath(import.meta.url)), 'gen-notices.mjs');

const GSON = '     (Apache-2.0) Gson (com.google.code.gson:gson:2.13.1 - https://github.com/google/gson)';
const SNAKEYAML =
  '     (Apache License, Version 2.0) SnakeYAML (org.yaml:snakeyaml:2.2 - https://bitbucket.org/snakeyaml/snakeyaml)';
const INTERNAL =
  '     (Apache License 2.0) EzClassify REST Client for Java (com.ezclassify.rest:client:1.0.0 - https://github.com/ezclassify/client-java)';

const BEGIN = '<!-- GENERATED-CLOSURE:BEGIN (scripts/gen-notices.mjs; do not edit by hand) -->';
const END = '<!-- GENERATED-CLOSURE:END -->';

/** Generated block exactly as the script would write it for gson + snakeyaml. */
const BLOCK = [
  BEGIN,
  '',
  '- `com.google.code.gson:gson:2.13.1` - Apache-2.0',
  '- `org.yaml:snakeyaml:2.2` - Apache License, Version 2.0',
  '',
  END,
].join('\n');

/** Curated sections naming both shipped components, as the real file does. */
const CURATED = [
  '',
  '## Gson',
  '',
  '- `com.google.code.gson:gson:2.13.1` - **Apache License 2.0**',
  '',
  '## SnakeYAML',
  '',
  '- `org.yaml:snakeyaml:2.2`: **Apache License 2.0**',
  '',
].join('\n');

function fixture({ closure = [INTERNAL, GSON, SNAKEYAML], notices = `# Notices\n\n${BLOCK}\n${CURATED}` } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'java-notices-'));
  const licenseDir = path.join(root, 'target/generated-sources/license');
  fs.mkdirSync(licenseDir, { recursive: true });
  fs.writeFileSync(
    path.join(licenseDir, 'THIRD-PARTY.txt'),
    `\nLists of ${closure.length} third-party dependencies.\n${closure.join('\n')}\n`,
  );
  fs.writeFileSync(path.join(root, 'THIRD-PARTY-NOTICES.md'), notices);
  return root;
}

function check(root) {
  return execFileSync(process.execPath, [script, '--check'], { cwd: root, encoding: 'utf8', stdio: 'pipe' });
}

test('accepts a notices file whose curated sections match the closure', () => {
  assert.match(check(fixture()), /all allowlisted, all documented/);
});

test('reports only third-party components, not reactor-internal ones', () => {
  assert.match(check(fixture()), /2 third-party components/);
});

test('treats nested EzClassify Maven groups as reactor-internal', () => {
  const nestedContract =
    '     (Apache License 2.0) EzClassify REST Contract for Java (com.ezclassify.rest:contract:1.0.0 - https://github.com/ezclassify/client-java)';
  assert.match(check(fixture({ closure: [INTERNAL, nestedContract, GSON, SNAKEYAML] })), /2 third-party components/);
});

// Curated prose is compared with the closure, not just the generated block, so
// a section cannot keep declaring a component the build does not redistribute.
test('rejects a curated section naming an artifact that left the closure', () => {
  const root = fixture({
    notices: `# Notices\n\n${BLOCK}\n${CURATED}\n## Example\n\n- \`com.example:gone:1.0.0\`\n`,
  });
  assert.throws(() => check(root), /named but not in the closure: com\.example:gone:1\.0\.0/);
});

test('rejects a closure entry that no curated section documents', () => {
  const root = fixture({
    notices: `# Notices\n\n${BLOCK}\n\n## Gson\n\n- \`com.google.code.gson:gson:2.13.1\` - **Apache License 2.0**\n`,
  });
  assert.throws(() => check(root), /not named by any curated section: org\.yaml:snakeyaml:2\.2/);
});

test('rejects a curated section pinned to a version the closure does not resolve', () => {
  const root = fixture({ notices: `# Notices\n\n${BLOCK}\n${CURATED.replace('2.13.1', '2.13.0')}` });
  assert.throws(() => check(root), /named but not in the closure: com\.google\.code\.gson:gson:2\.13\.0/);
});

// Prose may name something we deliberately do not ship. Written without a
// version, it is a statement about a non-dependency, not a redistribution claim.
test('accepts a version-less mention of an artifact that is deliberately not a dependency', () => {
  const root = fixture({
    notices: `# Notices\n\n${BLOCK}\n${CURATED}\nWe avoid \`com.example:avoided\` and \`com.example:avoided-enterprise\`.\n`,
  });
  assert.match(check(root), /all documented/);
});

test('ignores coordinates inside HTML comments, which are editor guidance', () => {
  const root = fixture({
    notices: `# Notices\n\n${BLOCK}\n<!-- e.g. group:artifact:version -->\n${CURATED}`,
  });
  assert.match(check(root), /all documented/);
});

test('rejects a dependency whose declared license is outside the allowlist', () => {
  const root = fixture({
    closure: [GSON, SNAKEYAML, '     (SSPL-1.0) Thing (com.example:thing:1.0 - https://example.com)'],
    notices: `# Notices\n\n${BLOCK}\n${CURATED}\n- \`com.example:thing:1.0\`\n`,
  });
  assert.throws(() => check(root), /no allowlisted license[\s\S]*com\.example:thing:1\.0/);
});

test('still rejects a stale generated block', () => {
  const root = fixture({
    notices: `# Notices\n\n${BEGIN}\n\n- \`com.google.code.gson:gson:2.13.1\` - Apache-2.0\n\n${END}\n${CURATED}`,
  });
  assert.throws(() => check(root), /stale against the current dependency closure/);
});

test('fails when the closure file has not been generated', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'java-notices-empty-'));
  fs.writeFileSync(path.join(root, 'THIRD-PARTY-NOTICES.md'), `# Notices\n\n${BLOCK}\n${CURATED}`);
  assert.throws(() => check(root), /THIRD-PARTY\.txt not found/);
});
