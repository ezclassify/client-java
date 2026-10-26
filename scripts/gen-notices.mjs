#!/usr/bin/env node
/**
 * Third-party notices generator and license allowlist gate.
 *
 * Consumes the resolved production dependency closure produced by the
 * license-maven-plugin and rewrites the generated section of
 * THIRD-PARTY-NOTICES.md between the GENERATED-CLOSURE markers. Every
 * dependency must carry at least one allowlisted license; anything else
 * fails the run so a new license gets a deliberate review instead of
 * sliding in silently.
 *
 * It also reconciles the hand-written sections below the generated block
 * against that same closure, in both directions. The marker block is
 * generated, so the curated prose is the part that could otherwise name
 * anything and still pass. Naming a component here is a license claim, so the
 * set of components named has to be the set of components shipped.
 *
 * Usage (from the repo root):
 *   ./mvnw org.codehaus.mojo:license-maven-plugin:2.7.1:aggregate-add-third-party \
 *     -Dlicense.excludedScopes=test,provided --batch-mode --no-transfer-progress
 *   node scripts/gen-notices.mjs --check   fail if the committed file is stale (CI gate)
 *   node scripts/gen-notices.mjs --write   refresh THIRD-PARTY-NOTICES.md in place
 */

import { readFileSync, writeFileSync, existsSync } from 'node:fs';

const CLOSURE = 'target/generated-sources/license/THIRD-PARTY.txt';
const NOTICES = 'THIRD-PARTY-NOTICES.md';
const BEGIN = '<!-- GENERATED-CLOSURE:BEGIN (scripts/gen-notices.mjs; do not edit by hand) -->';
const END = '<!-- GENERATED-CLOSURE:END -->';

// Licenses approved for the production closure, as the license-maven-plugin
// names them (POM license names verbatim, so the same license can appear
// under several spellings: gson declares the SPDX id "Apache-2.0"). A
// dependency passes if ANY of its declared licenses is listed (dual
// licensing lets the consumer pick the permissive grant).
const ALLOWED = new Set([
  'MIT License',
  'Apache License, Version 2.0',
  'Apache-2.0',
  'Universal Permissive License, Version 1.0',
  'Unicode/ICU License',
]);

// Reactor-internal artifacts are first-party, not third-party.
const INTERNAL_GROUP_PREFIX = 'com.ezclassify';

const mode = process.argv[2];
if (mode !== '--check' && mode !== '--write') {
  console.error('Usage: node scripts/gen-notices.mjs --check|--write');
  process.exit(2);
}

if (!existsSync(CLOSURE)) {
  console.error(`ERROR: ${CLOSURE} not found.`);
  console.error('Run the license-maven-plugin aggregate goal first (see the header of this script).');
  process.exit(1);
}

// THIRD-PARTY.txt lines look like:
//   (Apache-2.0) Gson (com.google.code.gson:gson:2.13.1 - https://github.com/google/gson)
// A dual-licensed dependency carries one parenthesized group per license, which
// is why the licenses are parsed as a list rather than a single value:
//   (MIT License) (Apache-2.0) Example (com.example:thing:1.0 - https://example.com)
const deps = [];
for (const raw of readFileSync(CLOSURE, 'utf8').split('\n')) {
  const line = raw.trim();
  const coords = line.match(/\(([\w.-]+:[\w.-]+:[\w.-]+)(?: - \S+)?\)$/);
  if (!coords) continue;
  // Licenses are the parenthesized groups before the trailing "(coords - url)".
  const head = line.slice(0, line.lastIndexOf('('));
  const licenses = [...head.matchAll(/\(([^()]+)\)/g)].map((m) => m[1]).sort();
  const [group] = coords[1].split(':');
  if (group === INTERNAL_GROUP_PREFIX || group.startsWith(`${INTERNAL_GROUP_PREFIX}.`)) continue;
  deps.push({ coords: coords[1], licenses });
}
deps.sort((a, b) => a.coords.localeCompare(b.coords));

if (deps.length === 0) {
  console.error(`ERROR: parsed no third-party dependencies from ${CLOSURE}; format drift?`);
  process.exit(1);
}

const offenders = deps.filter((d) => !d.licenses.some((l) => ALLOWED.has(l)));
if (offenders.length > 0) {
  console.error('ERROR: dependencies with no allowlisted license:');
  for (const d of offenders) console.error(`  ${d.coords} - ${d.licenses.join('; ') || '(none declared)'}`);
  console.error('Review the license and, if approved, add it to ALLOWED in scripts/gen-notices.mjs.');
  process.exit(1);
}

const section = [
  BEGIN,
  '',
  ...deps.map((d) => `- \`${d.coords}\` - ${d.licenses.join('; ')}`),
  '',
  END,
].join('\n');

const current = readFileSync(NOTICES, 'utf8');
const begin = current.indexOf(BEGIN);
const end = current.indexOf(END);
if (begin === -1 || end === -1) {
  console.error(`ERROR: GENERATED-CLOSURE markers not found in ${NOTICES}.`);
  process.exit(1);
}
const next = current.slice(0, begin) + section + current.slice(end + END.length);

// Coordinates named by the curated sections, i.e. everything after the block
// this script owns. A full group:artifact:version is read as a claim that the
// SDK redistributes that exact artifact. To mention something deliberately NOT
// depended on (an excluded transitive, a meta-package avoided on license
// grounds) write it without a version, since there is no resolved version for
// something we do not ship, and this check leaves it alone.
// HTML comments are guidance for whoever edits this file, not text published to
// a reader, so they are not claims and are stripped before the scan. That also
// lets the note above the curated sections spell the coordinate shape out.
const curated = next.slice(next.indexOf(END) + END.length).replace(/<!--[\s\S]*?-->/g, '');
const named = new Set(
  [...curated.matchAll(/(?<![\w.:-])([A-Za-z][\w.-]*:[\w.-]+:[\w.-]+)(?![\w.:-])/g)].map((m) => m[1]),
);
const resolved = new Set(deps.map((d) => d.coords));

const phantom = [...named].filter((c) => !resolved.has(c)).sort();
const undocumented = [...resolved].filter((c) => !named.has(c)).sort();

if (phantom.length > 0 || undocumented.length > 0) {
  console.error(`ERROR: the curated sections of ${NOTICES} disagree with the dependency closure.`);
  for (const c of phantom) {
    console.error(`  named but not in the closure: ${c}`);
  }
  for (const c of undocumented) {
    console.error(`  in the closure but not named by any curated section: ${c}`);
  }
  if (phantom.length > 0) {
    console.error('A component named here is a redistribution claim. Delete the section for anything');
    console.error('no longer shipped, or drop the version if the mention is deliberately not a dependency.');
  }
  if (undocumented.length > 0) {
    console.error('Add a curated section giving each new component its license and source.');
  }
  if (mode === '--write') {
    writeFileSync(NOTICES, next);
    console.error(`Wrote the generated block anyway; the curated sections need a hand edit.`);
  }
  process.exit(1);
}

if (mode === '--write') {
  writeFileSync(NOTICES, next);
  console.log(`Refreshed ${NOTICES} (${deps.length} third-party components, all allowlisted).`);
} else if (next !== current) {
  console.error(`ERROR: ${NOTICES} is stale against the current dependency closure.`);
  console.error('Run the aggregate goal, then: node scripts/gen-notices.mjs --write, and commit the result.');
  process.exit(1);
} else {
  console.log(
    `${NOTICES} is current (${deps.length} third-party components, all allowlisted, all documented).`,
  );
}
