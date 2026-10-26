#!/usr/bin/env node

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const handwrittenDir = path.join(root, 'types/src/main/java/com/ezclassify/rest/contract');
const generatedRoot = path.join(root, 'types/target/generated-sources');
const generatedDir = path.join(generatedRoot, 'java/com/ezclassify/rest/contract');
const errors = [];

// The generator reads only the vendored contract in vendor/spec/, and tools/ stays
// a third-party-only toolchain. The licensed engine package would give it a
// second, unvendored contract source and a proprietary dependency in this
// Apache-2.0 repository.
const toolsPackage = JSON.parse(fs.readFileSync(path.join(root, 'tools/package.json'), 'utf8'));
if (
  toolsPackage.dependencies?.['@ezclassify/engine'] ||
  toolsPackage.devDependencies?.['@ezclassify/engine']
) {
  errors.push('@ezclassify/engine must not be installed into generator tooling.');
}

const approvedHandwritten = {
  'encoding boundaries': ['Utf8Offsets'],
  'Java-native projections': ['FilterResult'],
  'contract enum base': ['ExpandableStringEnum'],
  'public operation support': ['ContextOperationResult'],
};

const expectedHandwritten = Object.values(approvedHandwritten).flat().sort();
const actualHandwritten = fs
  .readdirSync(handwrittenDir)
  .filter((name) => name.endsWith('.java'))
  .map((name) => name.slice(0, -5))
  .sort();

// Handwritten contract types live in the one package directory. A subpackage
// would escape the allowlist above, which reads only that directory.
const handwrittenSubpackages = fs.readdirSync(handwrittenDir, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => e.name);
if (handwrittenSubpackages.length > 0) errors.push(`Handwritten contract types must stay in one package; found subpackages: ${handwrittenSubpackages.join(', ')}`);

const unexpected = actualHandwritten.filter((name) => !expectedHandwritten.includes(name));
const missing = expectedHandwritten.filter((name) => !actualHandwritten.includes(name));
if (unexpected.length > 0) {
  errors.push(`Unreviewed handwritten type owners: ${unexpected.join(', ')}`);
}
if (missing.length > 0) {
  errors.push(`Approved handwritten type owners are missing: ${missing.join(', ')}`);
}

const declarationPattern =
  /^\s*public\s+(?:(?:abstract|final)\s+)?(?:class|record|enum|interface)\s+([A-Za-z_$][\w$]*)/m;
for (const name of actualHandwritten) {
  const relative = `types/src/main/java/com/ezclassify/rest/contract/${name}.java`;
  const source = fs.readFileSync(path.join(root, relative), 'utf8');
  if (!source.includes('package com.ezclassify.rest.contract;')) {
    errors.push(`${relative} is outside the canonical types package.`);
  }
  const declaration = source.match(declarationPattern)?.[1];
  if (declaration !== name) {
    errors.push(`${relative} must declare exactly one matching public top-level owner.`);
  }
}

function javaFiles(directory) {
  if (!fs.existsSync(directory)) return [];
  const files = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...javaFiles(absolute));
    else if (entry.isFile() && entry.name.endsWith('.java')) files.push(absolute);
  }
  return files;
}

const allGeneratedFiles = javaFiles(generatedRoot);
const generatedFiles = javaFiles(generatedDir);
if (generatedFiles.length === 0) {
  errors.push('No generated type sources found; run Maven generate-sources before this guard.');
}
for (const file of allGeneratedFiles) {
  if (!file.startsWith(`${generatedDir}${path.sep}`)) {
    errors.push(`Generated Java output escaped its single package: ${path.relative(root, file)}`);
  }
}

// The client module generates exactly one source: the package-private response
// shape table. Anything else there, or a public type, would be a second owner of
// generated contract code.
const clientGeneratedDir = path.join(root, 'client/target/generated-sources');
const clientGenerated = javaFiles(clientGeneratedDir).map((file) => path.relative(clientGeneratedDir, file));
const expectedClientGenerated = [path.join('java', 'com', 'ezclassify', 'rest', 'client', 'ResponseShapes.java')];
if (JSON.stringify(clientGenerated) !== JSON.stringify(expectedClientGenerated)) {
  errors.push(`Client generated sources must be exactly ${expectedClientGenerated}; found ${clientGenerated.join(', ') || 'none'}`);
} else {
  const shapes = fs.readFileSync(path.join(clientGeneratedDir, expectedClientGenerated[0]), 'utf8');
  if (/^\s*public\s/m.test(shapes)) errors.push('The generated response shape table must not declare public members.');
}

const generatedOwners = new Map();
for (const file of generatedFiles) {
  const source = fs.readFileSync(file, 'utf8');
  const owner = source.match(declarationPattern)?.[1];
  const relative = path.relative(root, file);
  if (!source.includes('package com.ezclassify.rest.contract;')) {
    errors.push(`${relative} is outside the canonical types package.`);
  }
  if (!owner) {
    errors.push(`${relative} has no public top-level generated owner.`);
    continue;
  }
  const paths = generatedOwners.get(owner) ?? [];
  paths.push(relative);
  generatedOwners.set(owner, paths);
}

for (const [owner, paths] of generatedOwners) {
  if (paths.length > 1) {
    errors.push(`Generated type ${owner} has multiple owners: ${paths.join(', ')}`);
  }
  if (actualHandwritten.includes(owner)) {
    errors.push(`Type ${owner} has both generated and handwritten owners.`);
  }
}

if (errors.length > 0) {
  for (const error of errors) console.error(`ERROR: ${error}`);
  process.exit(1);
}

console.log(
  `Architecture guard passed: ${generatedOwners.size} generated owners and ` +
    `${actualHandwritten.length} approved handwritten owners are disjoint.`,
);
for (const [category, owners] of Object.entries(approvedHandwritten)) {
  console.log(`  ${category}: ${owners.length}`);
}
