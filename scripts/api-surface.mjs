#!/usr/bin/env node
/**
 * Public API surface dump and 1.0 freeze gate.
 *
 * The Java SDK's public surface (public classes, interfaces, enums, records,
 * their public members, and public constants) is locked at 1.0. This script
 * dumps that surface deterministically with the JDK's own javap and compares
 * it against the committed golden file, api-surface.txt at the repo root.
 *
 * Usage (from the repo root, after the modules are compiled):
 *   node scripts/api-surface.mjs --check   fail if the surface drifted (CI gate)
 *   node scripts/api-surface.mjs --write   regenerate the golden (make api-surface)
 *
 * javap is taken from PATH; set JAVAP=/path/to/javap to override when the
 * JDK is not on PATH.
 */

import { spawnSync } from 'node:child_process';
import { readdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

// Published thin-client modules only.
const MODULES = ['types', 'client'];
const GOLDEN = 'api-surface.txt';
const JAVAP = process.env.JAVAP || 'javap';

const GOLDEN_HEADER = `# EzClassify Java SDK public API surface, locked at 1.0.
# Dump of every public type and public member in the published modules
# (${MODULES.join(', ')}), produced by javap.
#
# Do not edit by hand. To accept a reviewed, deliberate API change run
#   make api-surface
# and commit the updated file together with the code change.
`;

/** Recursively collect binary class names under a classes root. */
function classNames(root, dir = '') {
  const names = [];
  for (const entry of readdirSync(join(root, dir), { withFileTypes: true })) {
    const rel = dir ? `${dir}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      names.push(...classNames(root, rel));
    } else if (entry.name.endsWith('.class')) {
      const binary = rel.slice(0, -'.class'.length).replaceAll('/', '.');
      // Anonymous and local classes (Foo$1, Foo$2Bar) are never public API.
      if (/\$\d/.test(binary)) continue;
      if (binary.endsWith('module-info') || binary.endsWith('package-info')) continue;
      names.push(binary);
    }
  }
  return names;
}

/** Run javap -public over a set of classes and return its stdout. */
function javap(classpath, names) {
  let out = '';
  // Chunk to keep argv comfortably small.
  for (let i = 0; i < names.length; i += 100) {
    const chunk = names.slice(i, i + 100);
    const res = spawnSync(JAVAP, ['-public', '-constants', '-classpath', classpath, ...chunk], {
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
    });
    if (res.error) {
      console.error(`ERROR: could not run '${JAVAP}': ${res.error.message}`);
      console.error('Set JAVAP=/path/to/javap if the JDK is not on PATH.');
      process.exit(2);
    }
    if (res.status !== 0) {
      console.error(`ERROR: javap failed on ${classpath}:`);
      console.error(res.stderr.trim());
      process.exit(2);
    }
    out += res.stdout;
  }
  return out;
}

/**
 * Parse javap output into { className: [headerLine, ...sortedMemberLines] }.
 * Non-public types are dropped; whitespace is normalized; member order is
 * sorted so the dump does not depend on source or classfile order.
 */
function parseBlocks(text, into) {
  let header = null;
  let members = [];
  for (const raw of text.split('\n')) {
    const line = raw.replace(/\s+/g, ' ').trim();
    if (line === '' || line.startsWith('Compiled from ')) continue;
    if (line.endsWith('{')) {
      header = line;
      members = [];
    } else if (line === '}') {
      if (header && /(^|\s)public\s/.test(` ${header}`)) {
        const name = header.replace(/\s*\{$/, '').split(/\s+/)
          .find((w) => w.includes('.')) || header;
        into.set(name, [header, ...members.sort()]);
      }
      header = null;
    } else if (header) {
      members.push(line);
    }
  }
}

/** Build the full surface dump as one deterministic string. */
function currentSurface() {
  const blocks = new Map();
  for (const module of MODULES) {
    const classes = join(module, 'target', 'classes');
    if (!existsSync(classes)) {
      console.error(`ERROR: ${classes} not found. Compile first:`);
      console.error('  mvn verify --batch-mode');
      process.exit(2);
    }
    const names = classNames(classes).sort();
    parseBlocks(javap(classes, names), blocks);
  }
  const keys = [...blocks.keys()].sort();
  let text = '';
  for (const key of keys) {
    const [header, ...members] = blocks.get(key);
    text += `${header}\n`;
    for (const m of members) text += `  ${m}\n`;
    text += '}\n';
  }
  return text;
}

/** Parse a surface dump back into { className: Set(memberLines) } for diffing. */
function toClassMap(text) {
  const map = new Map();
  let name = null;
  for (const line of text.split('\n')) {
    if (line.startsWith('#') || line.trim() === '') continue;
    if (line.endsWith('{')) {
      name = line.replace(/\s*\{$/, '').split(/\s+/).find((w) => w.includes('.')) || line;
      map.set(name, new Set([line]));
    } else if (line === '}') {
      name = null;
    } else if (name) {
      map.get(name).add(line.trim());
    }
  }
  return map;
}

function check(current) {
  if (!existsSync(GOLDEN)) {
    console.error(`ERROR: golden file ${GOLDEN} is missing. Regenerate with 'make api-surface'.`);
    process.exit(1);
  }
  const golden = readFileSync(GOLDEN, 'utf8')
    .split('\n').filter((l) => !l.startsWith('#')).join('\n').replace(/^\n+/, '');
  if (golden === current) {
    const classes = [...current.matchAll(/^\S.*\{$/gm)].length;
    console.log(`API surface matches ${GOLDEN} (${classes} public types across ${MODULES.join(', ')}).`);
    return;
  }

  const want = toClassMap(golden);
  const have = toClassMap(current);
  console.error('ERROR: the public API surface changed.');
  console.error('');
  for (const name of [...new Set([...want.keys(), ...have.keys()])].sort()) {
    const w = want.get(name);
    const h = have.get(name);
    if (!h) {
      console.error(`  removed type: ${name}`);
    } else if (!w) {
      console.error(`  added type:   ${name}`);
    } else {
      for (const line of [...w].sort()) {
        if (!h.has(line)) console.error(`  ${name}\n    - ${line}`);
      }
      for (const line of [...h].sort()) {
        if (!w.has(line)) console.error(`  ${name}\n    + ${line}`);
      }
    }
  }
  console.error('');
  console.error('This SDK\'s public API is a 1.0-locked contract. If this change was');
  console.error('reviewed and is intentional, regenerate the golden and commit it:');
  console.error('  make api-surface');
  console.error(`Otherwise revert the public-surface change. Golden: ${GOLDEN}`);
  process.exit(1);
}

const mode = process.argv[2];
if (mode !== '--check' && mode !== '--write') {
  console.error('Usage: node scripts/api-surface.mjs --check | --write');
  process.exit(2);
}
const surface = currentSurface();
if (mode === '--write') {
  writeFileSync(GOLDEN, GOLDEN_HEADER + surface);
  console.log(`Wrote ${GOLDEN}.`);
} else {
  check(surface);
}
