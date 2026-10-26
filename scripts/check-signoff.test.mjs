import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const script = path.join(path.dirname(fileURLToPath(import.meta.url)), 'check-signoff.sh');

function git(repo, ...args) {
  return execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8', stdio: 'pipe' });
}

/** A throwaway repository with one base commit and the given follow-on messages. */
function repoWith(messages) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'java-signoff-'));
  git(root, 'init', '--quiet', '--initial-branch=main');
  git(root, 'config', 'user.name', 'Test Maintainer');
  git(root, 'config', 'user.email', 'maintainer@example.com');
  git(root, 'config', 'commit.gpgsign', 'false');
  fs.writeFileSync(path.join(root, 'base.txt'), 'base\n');
  git(root, 'add', 'base.txt');
  git(root, 'commit', '--quiet', '-m', 'chore: base');
  const base = git(root, 'rev-parse', 'HEAD').trim();
  messages.forEach((message, index) => {
    fs.writeFileSync(path.join(root, `file-${index}.txt`), `${index}\n`);
    git(root, 'add', `file-${index}.txt`);
    git(root, 'commit', '--quiet', '-m', message);
  });
  return { root, base, head: git(root, 'rev-parse', 'HEAD').trim() };
}

/** Runs the gate and returns its exit status together with everything it printed. */
function check({ root, base, head }, args = [base, head]) {
  const run = spawnSync('bash', [script, ...args], { cwd: root, encoding: 'utf8' });
  return { status: run.status, output: `${run.stdout}${run.stderr}` };
}

const signoff = 'Signed-off-by: Test Maintainer <maintainer@example.com>';

test('accepts a range where every commit is signed off', () => {
  const result = check(repoWith([`feat: one\n\n${signoff}`, `fix: two\n\n${signoff}`]));
  assert.equal(result.status, 0);
  assert.match(result.output, /are signed off/);
});

test('accepts an empty range', () => {
  const result = check(repoWith([]));
  assert.equal(result.status, 0);
});

test('rejects a range containing an unsigned commit and names it', () => {
  const result = check(repoWith([`feat: one\n\n${signoff}`, 'fix: unsigned change']));
  assert.equal(result.status, 1);
  assert.match(result.output, /missing Signed-off-by/);
  assert.match(result.output, /fix: unsigned change/);
});

test('rejects a sign-off trailer without an address', () => {
  const result = check(repoWith(['feat: one\n\nSigned-off-by: Test Maintainer']));
  assert.equal(result.status, 1);
  assert.match(result.output, /missing Signed-off-by/);
});

test('reports usage when the range is not supplied', () => {
  const result = check(repoWith([]), []);
  assert.equal(result.status, 2);
  assert.match(result.output, /usage: check-signoff.sh/);
});
