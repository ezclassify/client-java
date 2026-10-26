import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const script = path.join(path.dirname(fileURLToPath(import.meta.url)), 'check-workflow-location-guards.mjs');

function fixture(overrides = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'java-workflow-policy-'));
  const workflows = path.join(root, '.github/workflows');
  fs.mkdirSync(workflows, { recursive: true });
  const defaults = {
    'ci.yml': 'jobs:\n  build:\n    runs-on: ubuntu-latest\n',
    'secret-scan.yml': 'jobs:\n  gitleaks:\n    runs-on: ubuntu-latest\n',
    'vendor-readonly.yml': 'jobs:\n  check-vendor-changes:\n    runs-on: ubuntu-latest\n',
    ...overrides,
  };
  for (const [name, source] of Object.entries(defaults)) {
    fs.writeFileSync(path.join(workflows, name), source);
  }
  return root;
}

function check(root) {
  return execFileSync(process.execPath, [script, root], { encoding: 'utf8', stdio: 'pipe' });
}

test('accepts portable checks', () => {
  assert.match(check(fixture()), /every workflow is portable/);
});

test('rejects an ordinary check guarded to the organization', () => {
  const root = fixture({
    'ci.yml': "jobs:\n  build:\n    if: ${{ github.repository_owner == 'ezclassify' }}\n",
  });
  assert.throws(() => check(root), /must run on forks and mirrors/);
});

test('covers a workflow added after this gate was written', () => {
  const root = fixture({
    'nightly.yml': "jobs:\n  nightly:\n    if: ${{ github.repository_owner == 'ezclassify' }}\n",
  });
  assert.throws(() => check(root), /nightly\.yml must run on forks and mirrors/);
});
