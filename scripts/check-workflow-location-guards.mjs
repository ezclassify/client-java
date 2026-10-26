#!/usr/bin/env node

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const root = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const workflows = path.join(root, '.github/workflows');
// Every workflow in the directory is an ordinary check that must also run on forks and mirrors.
// Reading the whole directory means a workflow added later is covered by this policy without
// anyone remembering to list it here.
const locationGuard = /github\.repository(?:_owner)?\s*==/;
const errors = [];

const present = fs
  .readdirSync(workflows)
  .filter((name) => name.endsWith('.yml') || name.endsWith('.yaml'))
  .sort();

for (const name of present) {
  const source = fs.readFileSync(path.join(workflows, name), 'utf8');
  if (locationGuard.test(source)) {
    errors.push(`${name} must run on forks and mirrors; remove its repository location guard.`);
  }
}

if (errors.length > 0) {
  for (const error of errors) console.error(`ERROR: ${error}`);
  process.exit(1);
}

console.log('Workflow location guards follow policy: every workflow is portable.');
