#!/usr/bin/env node
/**
 * Generates Java record classes from the frozen canonical JSON Schema and
 * OpenAPI bundle under vendor/spec. Build tooling comes from tools/package.json.
 *
 * Usage:
 *   node scripts/gen.mjs <schemas-dir> <output-dir> <java-package> <openapi.yaml>
 *   node scripts/gen.mjs --response-shapes <openapi.yaml> <output-dir> <java-package>
 *
 * Each schema file whose name matches a target type produces one .java file.
 * Each record also declares a JSON_FIELD_* constant per field, holding the
 * field's wire name, for code that reads or writes the JSON form directly.
 *
 * The --response-shapes form writes one package-private table instead: the
 * members every success response must carry, which the client checks before
 * it reads or caches a body. It is not a wire type, so it is generated into the
 * client module that reads it rather than into the contract package.
 *
 * Build-time only, no runtime dependencies added.
 */

import fs from 'fs';
import path from 'path';
import { createRequire } from 'module';

// Code generation dependencies belong to the repository's existing tools
// package, not to the published types artifact. Resolve YAML from that pinned
// toolchain even though this script lives under types/.
const toolsRequire = createRequire(new URL('../../tools/package.json', import.meta.url));
const { parseDocument } = toolsRequire('yaml');

if (process.argv[2] === '--response-shapes') {
  const [shapesOpenapi, shapesDir, shapesPackage] = process.argv.slice(3);
  if (!shapesOpenapi || !shapesDir || !shapesPackage) {
    console.error('Usage: node scripts/gen.mjs --response-shapes <openapi.yaml> <output-dir> <java-package>');
    process.exit(1);
  }
  const contract = parseDocument(fs.readFileSync(shapesOpenapi, 'utf8')).toJS({ maxAliasCount: 0 });
  fs.rmSync(shapesDir, { recursive: true, force: true });
  fs.mkdirSync(shapesDir, { recursive: true });
  const target = path.join(shapesDir, 'ResponseShapes.java');
  fs.writeFileSync(target, responseShapesSource(contract, shapesPackage), 'utf8');
  console.log(`[gen] generated ${target}`);
  process.exit(0);
}

/**
 * The Java source of the response shape table: for every operation, the shape its JSON success
 * body must have, and for every component schema a success body can reach, the members it
 * requires and the containers it holds. Derived from the `required` lists and nothing else; no
 * schema or member is named by hand.
 */
function responseShapesSource(contract, javaPackage) {
  const schemas = contract.components?.schemas;
  if (!schemas) throw new Error('[gen] OpenAPI components.schemas is required');
  const operations = [];
  const reachable = new Set();
  const visit = (schema) => {
    if (!schema || typeof schema !== 'object') return;
    if (schema.$ref) {
      const name = componentName(schemas, schema.$ref);
      if (reachable.has(name)) return;
      reachable.add(name);
      visit(schemas[name]);
      return;
    }
    for (const property of Object.values(schema.properties ?? {})) visit(property);
    visit(schema.items);
    if (typeof schema.additionalProperties === 'object') visit(schema.additionalProperties);
    for (const alternative of [...(schema.anyOf ?? []), ...(schema.oneOf ?? []), ...(schema.allOf ?? [])]) {
      visit(alternative);
    }
  };
  for (const [route, item] of Object.entries(contract.paths ?? {})) {
    for (const [method, operation] of Object.entries(item)) {
      if (!['get', 'post', 'put', 'patch', 'delete'].includes(method)) continue;
      const successes = Object.entries(operation.responses ?? {}).filter(([code]) => /^2\d\d$/.test(code));
      for (const [code, response] of successes) {
        const body = response.content?.['application/json']?.schema;
        if (!body) continue;
        const key = `${method.toUpperCase()} ${route}`;
        if (successes.length > 1) throw new Error(`[gen] ${key} declares more than one JSON success response (${code})`);
        const shape = responseShape(schemas, body);
        if (shape === null) throw new Error(`[gen] the success response of ${key} has no checkable shape`);
        operations.push([key, shape]);
        visit(body);
      }
    }
  }
  if (operations.length === 0) throw new Error('[gen] the OpenAPI document declares no JSON success response');
  const components = [...reachable].sort().flatMap((name) => {
    const shape = responseShape(schemas, schemas[name]);
    return shape === null ? [] : [[name, shape]];
  });
  const entries = (pairs) =>
    pairs.map(([key, shape]) => `    table.put(${JSON.stringify(key)}, ${shapeSource(shape)});`).join('\n');
  return `package ${javaPackage};

import static ${javaPackage}.ResponseShape.alternative;
import static ${javaPackage}.ResponseShape.any;
import static ${javaPackage}.ResponseShape.array;
import static ${javaPackage}.ResponseShape.map;
import static ${javaPackage}.ResponseShape.object;
import static ${javaPackage}.ResponseShape.ref;

import java.util.HashMap;
import java.util.Map;

/**
 * The shape of every success response the contract declares, generated from its {@code required}
 * lists by {@code types/scripts/gen.mjs}. {@link ResponseCheck} reads it. Do not edit.
 */
final class ResponseShapes {
  private ResponseShapes() {}

  /** Success body shape by operation, keyed {@code "METHOD /path/template"}. */
  static final Map<String, ResponseShape> OPERATIONS = operations();

  /** Shape by component schema name, for every component a success body can reach. */
  static final Map<String, ResponseShape> COMPONENTS = components();

  private static Map<String, ResponseShape> operations() {
    Map<String, ResponseShape> table = new HashMap<>();
${entries(operations)}
    return Map.copyOf(table);
  }

  private static Map<String, ResponseShape> components() {
    Map<String, ResponseShape> table = new HashMap<>();
${entries(components)}
    return Map.copyOf(table);
  }
}
`;
}

/** The component a `#/components/schemas/Name` reference names; a dangling reference stops generation. */
function componentName(schemas, ref) {
  const name = ref.split('/').at(-1);
  if (!schemas[name]) throw new Error(`[gen] OpenAPI reference to missing component: ${name}`);
  return name;
}

/**
 * The shape of a schema as far as response checking needs it: the container kind a value must
 * have and the members an object requires, down through references. Scalars and opaque payloads
 * have no shape (null); the check judges presence and containers, the readers judge values.
 */
function responseShape(schemas, schema) {
  if (!schema || typeof schema !== 'object') return null;
  if (schema.$ref) {
    const name = componentName(schemas, schema.$ref);
    return isScalarSchema(schemas, schemas[name], new Set()) ? null : { ref: name };
  }
  if (schema.allOf) {
    if (schema.allOf.length !== 1) throw new Error('[gen] allOf with more than one schema has no response shape');
    return responseShape(schemas, schema.allOf[0]);
  }
  const alternatives = schema.anyOf ?? schema.oneOf;
  if (alternatives) {
    const objects = [];
    let scalar = false;
    for (const alternative of alternatives) {
      if (alternative.type === 'null') continue;
      const shape = responseShape(schemas, alternative);
      if (shape === null) scalar = true;
      else objects.push({ shape, consts: constMembers(schemas, alternative) });
    }
    if (objects.length === 0) return null;
    // A nullable reference is the reference: null itself is judged by the parent's nullable list.
    if (objects.length === 1 && !scalar && objects[0].consts.length === 0) return objects[0].shape;
    return { any: objects, scalar };
  }
  const types = [].concat(schema.type ?? []);
  if (types.includes('array')) {
    // A null item would read as a missing one, so the table cannot carry nullable items yet.
    if (isNullable(schemas, schema.items)) throw new Error('[gen] nullable array items have no response shape');
    return { arr: responseShape(schemas, schema.items) };
  }
  if (types.includes('object') || schema.properties) {
    if (isScalarSchema(schemas, schema, new Set())) return null;
    const additional =
      typeof schema.additionalProperties === 'object' ? responseShape(schemas, schema.additionalProperties) : null;
    if (typeof schema.additionalProperties === 'object' && isNullable(schemas, schema.additionalProperties)) {
      throw new Error('[gen] nullable map values have no response shape');
    }
    if (additional !== null && !schema.properties) return { map: additional };
    if (additional !== null) {
      throw new Error('[gen] a schema with both properties and structured additionalProperties has no response shape');
    }
    const required = schema.required ?? [];
    const members = Object.entries(schema.properties ?? {})
      .map(([name, property]) => [name, responseShape(schemas, property)])
      .filter(([, shape]) => shape !== null);
    return {
      obj: {
        required,
        nullable: required.filter((name) => isNullable(schemas, schema.properties?.[name])),
        members,
      },
    };
  }
  return null;
}

/** Whether a schema carries no container: a string, number, boolean, enum or opaque payload. */
function isScalarSchema(schemas, schema, seen) {
  if (!schema || typeof schema !== 'object') return true;
  if (schema.$ref) {
    const name = componentName(schemas, schema.$ref);
    if (seen.has(name)) return true;
    seen.add(name);
    return isScalarSchema(schemas, schemas[name], seen);
  }
  const alternatives = schema.anyOf ?? schema.oneOf ?? schema.allOf;
  if (alternatives) return alternatives.every((alternative) => isScalarSchema(schemas, alternative, seen));
  const types = [].concat(schema.type ?? []);
  if (types.includes('array')) return false;
  if (types.includes('object') || schema.properties) {
    return (
      !schema.required?.length &&
      !Object.keys(schema.properties ?? {}).length &&
      (typeof schema.additionalProperties !== 'object' || isScalarSchema(schemas, schema.additionalProperties, seen))
    );
  }
  return true;
}

/** The string `const` members that pick out one alternative of a union, by property name. */
function constMembers(schemas, alternative) {
  const resolved = alternative.$ref ? schemas[componentName(schemas, alternative.$ref)] : alternative;
  return Object.entries(resolved?.properties ?? {})
    .filter(([, property]) => typeof property?.const === 'string')
    .map(([name, property]) => [name, property.const]);
}

/** Whether a schema admits JSON null as a value of its own. */
function isNullable(schemas, schema) {
  if (!schema) return false;
  if (schema.$ref) return isNullable(schemas, schemas[componentName(schemas, schema.$ref)]);
  if ([].concat(schema.type ?? []).includes('null')) return true;
  return (schema.anyOf ?? schema.oneOf ?? []).some((alternative) => isNullable(schemas, alternative));
}

/** The Java expression that builds one shape node. */
function shapeSource(shape) {
  const text = (value) => JSON.stringify(value);
  if (shape.ref) return `ref(${text(shape.ref)})`;
  if (shape.arr !== undefined) return `array(${shape.arr === null ? 'null' : shapeSource(shape.arr)})`;
  if (shape.map) return `map(${shapeSource(shape.map)})`;
  if (shape.any) {
    const alternatives = shape.any.map(
      ({ shape: alternative, consts }) =>
        `alternative(${shapeSource(alternative)}${consts.map(([name, value]) => `, ${text(name)}, ${text(value)}`).join('')})`,
    );
    return `any(${shape.scalar}, ${alternatives.join(', ')})`;
  }
  const { required, nullable, members } = shape.obj;
  const memberArgs = members.map(([name, member]) => `, ${text(name)}, ${shapeSource(member)}`).join('');
  return `object(${text(required.join(' '))}, ${text(nullable.join(' '))}${memberArgs})`;
}

const [schemasDir, outputDir, javaPackage, openapiPath] = process.argv.slice(2);

if (!schemasDir || !outputDir || !javaPackage || !openapiPath) {
  console.error('Usage: node scripts/gen.mjs <schemas-dir> <output-dir> <java-package> <openapi.yaml>');
  process.exit(1);
}

// Generated sources are an exact projection of the current contract. Remove
// stale files from an earlier target set so shrinking or renaming a generated
// type cannot leave a duplicate class that survives incremental Maven builds.
fs.rmSync(outputDir, { recursive: true, force: true });
fs.mkdirSync(outputDir, { recursive: true });

// Parse the vendored OpenAPI contract before emitting sources. JSON Schema remains
// the immediate source for the records below, while the OpenAPI component
// inventory guards the complete 1.0 graph.
// Keeping this in the generator makes a missing named codegen root fail at
// generate-sources rather than silently leaving a handwritten DTO behind.
const openapi = parseDocument(fs.readFileSync(openapiPath, 'utf8')).toJS({ maxAliasCount: 0 });
if (openapi?.components?.schemas == null) {
  throw new Error('[gen] OpenAPI components.schemas is required');
}
const openapiSchemas = Object.keys(openapi.components.schemas).sort();
const namedContextEnvelopes = openapiSchemas.filter((name) => /^Context[A-Z].*Result$/.test(name));
if (namedContextEnvelopes.length === 0) {
  throw new Error('[gen] expected named Context operation envelope roots in OpenAPI');
}

/** Convert kebab-case schema filename to PascalCase class name. */
function toPascalCase(kebab) {
  return kebab
    .replace('.schema.json', '')
    .split('-')
    .map(s => s.charAt(0).toUpperCase() + s.slice(1))
    .join('');
}

/** Convert camelCase field name to SCREAMING_SNAKE_CASE for the constant. */
function toConstantName(fieldName) {
  return 'JSON_FIELD_' + fieldName
    .replace(/([A-Z])/g, '_$1')
    .toUpperCase();
}

const JAVA_RESERVED_WORDS = new Set([
  'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const',
  'continue', 'default', 'do', 'double', 'else', 'enum', 'extends', 'final', 'finally', 'float',
  'for', 'goto', 'if', 'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'native',
  'new', 'package', 'private', 'protected', 'public', 'record', 'return', 'short', 'static',
  'strictfp', 'super', 'switch', 'synchronized', 'this', 'throw', 'throws', 'transient', 'try',
  'var', 'void', 'volatile', 'while', 'yield',
]);

/** Return a legal Java record-component name without changing its JSON field constant. */
function toJavaFieldName(fieldName) {
  return JAVA_RESERVED_WORDS.has(fieldName) ? `${fieldName}Value` : fieldName;
}

/** Column the generated Javadoc is wrapped at. */
const JAVADOC_WIDTH = 100;

/**
 * Render one markdown-flavored description paragraph as Javadoc-safe inline text.
 *
 * Schema descriptions use backtick code spans and sometimes `{@link ...}` fragments that are valid
 * in TypeScript docs but not here. Code spans become `{@code}` (or `<code>` when their braces do
 * not balance), links are rendered as code too, and everything else is escaped so no description
 * can open a tag, an inline tag, a block tag or end the comment. A code span's spaces become
 * non-breaking so wrapping never starts a line inside it; `wrapText` turns them back.
 */
function toJavadocInline(paragraph) {
  const flat = paragraph
    .replace(/\{@link\s+([^}]+?)\s*\}/g, (_, target) => `\`${target.trim()}\``)
    .replace(/\s+/g, ' ')
    .trim();
  const escapeText = (text) =>
    text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/@/g, '&#64;')
      .replace(/\{/g, '&#123;')
      .replace(/\}/g, '&#125;')
      .replace(/\*\//g, '*&#47;');
  const balanced = (code) => {
    let depth = 0;
    for (const ch of code) {
      if (ch === '{') depth++;
      if (ch === '}' && --depth < 0) return false;
    }
    return depth === 0;
  };
  const unbreakable = (text) => text.replace(/ /g, ' ');
  return flat
    .split(/(`[^`]+`)/)
    .map((part) => {
      if (!/^`[^`]+`$/.test(part)) return escapeText(part);
      const code = part.slice(1, -1);
      return balanced(code) && !code.includes('*/')
        ? unbreakable(`{@code ${code}}`)
        : unbreakable(`<code>${escapeText(code)}</code>`);
    })
    .join('');
}

/** First paragraph of a schema description, as Javadoc-safe inline text. */
function firstParagraph(description) {
  return toJavadocInline(String(description).trim().split(/\n\s*\n/)[0]);
}

/**
 * Normalize a schema description into the Javadoc summary: its first paragraph. Longer text stays
 * in the schema; the summary is what a reader needs to place the type.
 */
function toJavadocSummary(description, fallback) {
  return firstParagraph(description || fallback);
}

/** Break text into lines of at most `width` columns, restoring the spaces inside code spans. */
function wrapText(text, width) {
  const lines = [];
  let line = '';
  for (const word of text.split(' ')) {
    if (line && line.length + 1 + word.length > width) {
      lines.push(line);
      line = word;
    } else {
      line = line ? `${line} ${word}` : word;
    }
  }
  if (line) lines.push(line);
  return lines.map((l) => l.replace(/ /g, ' '));
}

/**
 * Emit a Javadoc comment: the summary, then one `@param` per record component.
 *
 * @param {string} indent                          leading whitespace of the declaration
 * @param {string} summary                         inline Javadoc text for the type
 * @param {{name: string, text: string}[]} params  record components and their inline Javadoc text
 */
function javadocComment(indent, summary, params = []) {
  const width = JAVADOC_WIDTH - indent.length - 3;
  if (!params.length && summary.length + indent.length + 8 <= JAVADOC_WIDTH) {
    return `${indent}/** ${summary.replace(/ /g, ' ')} */`;
  }
  const lines = wrapText(summary, width);
  if (params.length) {
    lines.push('');
    for (const { name, text } of params) {
      const lead = `@param ${name} `;
      wrapText(text, width - lead.length).forEach((l, i) => lines.push(i === 0 ? lead + l : `    ${l}`));
    }
  }
  const body = lines.map((l) => (l ? `${indent} * ${l}` : `${indent} *`));
  return [`${indent}/**`, ...body, `${indent} */`].join('\n');
}

/**
 * The description a schema gives a property, looking through a reference or a single-variant
 * wrapper to the type it names when the property carries none of its own.
 *
 * @param {object} property                            the property schema
 * @param {(ref: string) => object|undefined} lookup   resolves a $ref to its schema
 */
function propertyDescription(property, lookup, seen = new Set()) {
  if (!property || typeof property !== 'object') return undefined;
  if (property.description) return property.description;
  if (typeof property.$ref === 'string' && !seen.has(property.$ref)) {
    seen.add(property.$ref);
    return propertyDescription(lookup(property.$ref), lookup, seen);
  }
  const variants = property.allOf || property.oneOf || property.anyOf;
  if (Array.isArray(variants)) {
    const named = variants.filter((variant) => variant.type !== 'null');
    if (named.length === 1) return propertyDescription(named[0], lookup, seen);
  }
  return undefined;
}

/** Record components whose contract schema gives no description; reported when a run finishes. */
const undocumentedComponents = [];

/**
 * Build the `@param` list for a record: each component's description from the contract schema.
 *
 * A component the schema leaves undescribed gets a plain statement of its shape and is reported by
 * name when generation finishes, so the gap is fixed in the contract and not hidden here.
 *
 * @param {string} owner                                                       record name
 * @param {{name: string, wireName: string, javaType: string, description?: string}[]} fields
 */
function componentParams(owner, fields) {
  return fields.map((field) => {
    if (field.description) return { name: field.name, text: firstParagraph(field.description) };
    undocumentedComponents.push(`${owner}.${field.wireName}`);
    return { name: field.name, text: describeShape(field) };
  });
}

/** Plain statement of a component's shape, for a field whose schema has no description. */
function describeShape({ wireName, javaType }) {
  const code = `{@code ${wireName}}`;
  const list = /^java\.util\.List<(.+)>$/.exec(javaType);
  if (list) return `The ${code} entries, each of type {@code ${list[1]}}, in contract order.`;
  const map = /^java\.util\.Map<String, (.+)>$/.exec(javaType);
  if (map) return `The ${code} entries, each of type {@code ${map[1]}}, keyed by name.`;
  if (javaType === 'boolean' || javaType === 'Boolean') return `The ${code} flag.`;
  return `The ${code} value.`;
}

const localDefinitionsByName = new Map();

/** Resolve a $ref string to a Java type name. */
function resolveRef(ref, seen = new Set()) {
  const name = decodeURIComponent(ref.replace('#/definitions/', ''));
  // AccessRule is a file-local canonical union in category-access-result. The
  // generator promotes it to a typed contract enum below instead of widening it
  // to String.
  if (name === 'AccessRule') return 'AccessRule';
  if (seen.has(name)) return 'Object';
  const record = /^Record<string,([^>]+)>$/.exec(name);
  if (record) {
    const valueType = record[1] === 'string'
      ? 'String'
      : record[1] === 'unknown'
        ? 'Object'
        : resolveRef(`#/definitions/${encodeURIComponent(record[1])}`, seen);
    return `java.util.Map<String, ${boxed(valueType)}>`;
  }
  const definition = localDefinitionsByName.get(name);
  if (definition && !seen.has(name)) {
    const nextSeen = new Set(seen).add(name);
    if (definition.$ref) return resolveRef(definition.$ref, nextSeen);
    if (definition.type === 'object' && !definition.properties && definition.additionalProperties) {
      return `java.util.Map<String, ${boxed(mapType(definition.additionalProperties, true, nextSeen))}>`;
    }
  }
  // Recursive DSL values carry arbitrary JSON. An empty generated record would
  // discard their contents during decoding.
  const opaqueJsonTypes = ['GuideMatchSpec', 'RuleCondition',
    'RuleWhen', 'RuleWhenNode', 'MatchNode'];
  if (opaqueJsonTypes.includes(name)) return 'Object';
  // Every other definition names a type another owner declares: the reviewed
  // OpenAPI graph below (Fix, ExplanationTrace, TraceEntry, GuideBreakdownEntry
  // and the trace sub-records among them) or a reviewed handwritten seam. A
  // record that refers to one uses that owner's type by name.
  return name;
}

/**
 * Map a JSON Schema property descriptor to a Java type string.
 *
 * @param {object} propSchema   JSON Schema for this property
 * @param {boolean} required    Whether this field is required in the parent schema
 */
function mapType(propSchema, required, seen = new Set()) {
  if (!propSchema) return 'Object';

  if (propSchema.$ref) {
    return resolveRef(propSchema.$ref, seen);
  }

  switch (propSchema.type) {
    case 'string':
      return 'String';
    case 'boolean':
      // Use primitive for required fields, wrapper for optional
      return required ? 'boolean' : 'Boolean';
    case 'number':
      return required ? 'double' : 'Double';
    case 'integer':
      return required ? 'int' : 'Integer';
    case 'array': {
      // Public library surface uses List<T>, never raw arrays: immutable-friendly
      // (List.copyOf) and composes with the collections API. Element type follows
      // the same mapping, boxed where it would otherwise be a primitive.
      const itemType = propSchema.items ? boxed(mapType(propSchema.items, true, seen)) : 'Object';
      return `java.util.List<${itemType}>`;
    }
    case 'object':
      if (propSchema.additionalProperties) {
        return `java.util.Map<String, ${boxed(mapType(propSchema.additionalProperties, true, seen))}>`;
      }
      return 'Object';
    default:
      // enum fields are strings at runtime
      if (propSchema.enum) return 'String';
      return 'Object';
  }
}

/** Box a Java primitive type name so it can be used as a generic type argument. */
function boxed(type) {
  switch (type) {
    case 'boolean': return 'Boolean';
    case 'double': return 'Double';
    case 'int': return 'Integer';
    default: return type;
  }
}

/**
 * Carry every named field of an object union's variants in one record.
 *
 * Result schemas are open, so a variant may tolerate members it does not name;
 * the carrier keeps the ones the contract names, and the JSON reader ignores the
 * rest the same way it does for any other response record. Two string enums
 * declared for one field merge into one. A variant that is not an object with
 * named properties has no fields to carry, and any other field two variants
 * declare differently has no single Java type, so both stop generation.
 */
function objectUnionCarrier(schema) {
  const variants = schema.oneOf || schema.anyOf;
  if (!Array.isArray(variants) || !variants.length) return schema;
  if (variants.some((variant) => variant.type !== 'object' || !variant.properties)) {
    throw new Error('[gen] record union requires object variants with named properties');
  }
  const properties = {};
  for (const variant of variants) {
    for (const [name, property] of Object.entries(variant.properties)) {
      const prior = properties[name];
      if (!prior || JSON.stringify(prior) === JSON.stringify(property)) properties[name] = property;
      else if (prior.type === 'string' && property.type === 'string' && prior.enum && property.enum) {
        properties[name] = { ...prior, enum: [...new Set([...prior.enum, ...property.enum])] };
      } else throw new Error(`[gen] incompatible object-union property: ${name}`);
    }
  }
  return {
    ...schema,
    properties,
    required: (variants[0].required || []).filter((name) =>
      variants.every((variant) => (variant.required || []).includes(name))),
  };
}

/** Resolve a file-local `#/definitions/Name` reference to its schema. */
function lookupLocalDefinition(ref) {
  return localDefinitionsByName.get(decodeURIComponent(ref.replace('#/definitions/', '')));
}

/**
 * Generate a Java record for one JSON Schema.
 *
 * @param {string} className   Java class name
 * @param {object} schema      Parsed JSON Schema object
 * @returns {string}           Java source file contents
 */
function generateRecord(className, schema) {
  schema = objectUnionCarrier(schema);
  const props = schema.properties || {};
  const requiredSet = new Set(schema.required || []);

  const fields = [];      // { name, wireName, javaType, required, description }
  const constants = [];   // "public static final String JSON_FIELD_X = \"x\";"

  for (const [name, propSchema] of Object.entries(props)) {
    const req = requiredSet.has(name);
    const javaType = mapType(propSchema, req);
    fields.push({
      name: toJavaFieldName(name),
      wireName: name,
      javaType,
      required: req,
      description: propertyDescription(propSchema, lookupLocalDefinition),
    });
    constants.push(`    /** JSON field name for {@code ${name}}. */`);
    constants.push(`    public static final String ${toConstantName(name)} = "${name}";`);
  }

  const recordParams = fields
    .map(f => `    ${f.javaType} ${f.name}`)
    .join(',\n');

  const doc = javadocComment('', toJavadocSummary(schema.description, className).replace(/"/g, "'"), componentParams(className, fields));

  return [
    `package ${javaPackage};`,
    '',
    doc,
    `public record ${className}(`,
    recordParams,
    `) {`,
    '',
    ...constants,
    '}',
    '',
  ].join('\n');
}


// The engine result schemas the locked 1.0 surface publishes as records; a
// rename or removal of one fails generation below. Every other schema root
// under vendor/spec/json-schemas is generated beside them.
const TARGETS = [
  'assertion',
  'validation-error',
  'warning',
  'process-result',
  'aggregate-result',
  'suggestion',
  'diff-result',
  'access-rule-result',
  'access-decision',
  'evaluation-result',
  'guide-scan-result',
];

// Schema roots this pass skips because another owner declares the Java type:
// the reviewed OpenAPI graph below, which keeps the fields of every variant of
// a union and types its enums, or a reviewed handwritten seam. A root whose
// handwritten seam is retired moves to the OpenAPI graph, not to this pass.
const SCHEMA_ROOTS_OWNED_ELSEWHERE = new Set([
  'crosswalk-translation-result',
  'explanation-trace',
  'fix',
  'guide-breakdown-entry',
  'guide-scan-span',
  'suggest-result',
  'trace-entry',
]);
const schemaTargets = fs
  .readdirSync(schemasDir)
  .filter((name) => name.endsWith('.schema.json'))
  .map((name) => name.replace('.schema.json', ''))
  .filter((name) => !SCHEMA_ROOTS_OWNED_ELSEWHERE.has(name));
const targets = [...new Set([...TARGETS, ...schemaTargets])]
  .filter((name) => !SCHEMA_ROOTS_OWNED_ELSEWHERE.has(name))
  .sort();

const targetSchemas = new Map();
for (const target of targets) {
  const schemaPath = path.join(schemasDir, `${target}.schema.json`);
  if (!fs.existsSync(schemaPath)) continue;
  const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));
  targetSchemas.set(target, schema);
  for (const [name, definition] of Object.entries(schema.definitions || {})) {
    if (!localDefinitionsByName.has(name)) localDefinitionsByName.set(name, definition);
  }
}

// File-local `definitions` entries a generated record actually references.
// Most resolve to a type that already exists: a schema root of its own, an
// OpenAPI component, or a reviewed handwritten seam. A definition that exists
// only inside its parent file has no other owner, so the generator emits it
// here. Without this a new nested contract detail would compile to a dangling
// type name instead of a real Java record.
const referencedLocalDefinitions = new Map();

/** Record every file-local definition reachable from a schema's properties. */
function collectLocalDefinitions(schema) {
  const definitions = schema.definitions || {};
  const visit = (value) => {
    if (Array.isArray(value)) return value.forEach(visit);
    if (value == null || typeof value !== 'object') return;
    if (typeof value.$ref === 'string' && value.$ref.startsWith('#/definitions/')) {
      const name = decodeURIComponent(value.$ref.replace('#/definitions/', ''));
      if (resolveRef(value.$ref) === 'Object') return;
      if (definitions[name] && !referencedLocalDefinitions.has(name)) {
        referencedLocalDefinitions.set(name, definitions[name]);
        visit(definitions[name]);
      }
    }
    Object.values(value).forEach(visit);
  };
  visit(schema.properties || {});
}

let generated = 0;
const generatedClassNames = new Set();
for (const target of targets) {
  const schemaPath = path.join(schemasDir, `${target}.schema.json`);
  if (!fs.existsSync(schemaPath)) {
    throw new Error(
      `[gen] expected schema not found: ${schemaPath}. The 1.0 generated surface is locked, ` +
        `so a renamed or dropped engine schema must fail generate-sources rather than silently ` +
        `shrink the surface until javac or the api-surface golden trips downstream.`,
    );
  }
  const schema = targetSchemas.get(target);
  const className = toPascalCase(`${target}.schema.json`);
  const source = generateRecord(className, schema);
  const outPath = path.join(outputDir, `${className}.java`);
  fs.writeFileSync(outPath, source, 'utf8');
  generatedClassNames.add(className);
  collectLocalDefinitions(schema);
  console.log(`[gen] generated ${className}.java`);
  generated++;
}

/**
 * Convert a declared wire token to an idiomatic Java constant name: words split at punctuation and
 * at camelCase boundaries, so `no-field` becomes NO_FIELD and `schemeScan` becomes SCHEME_SCAN.
 */
function enumConstant(value) {
  const constant = value
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2')
    .replace(/[^A-Za-z0-9]+/g, '_')
    .replace(/^_|_$/g, '')
    .toUpperCase();
  return /^\d/.test(constant) ? `VALUE_${constant}` : constant;
}

/** Derive a legal nested Java type name from a property location. */
function nestedTypeName(location) {
  const segment = location.split('.').at(-1).replace(/\[\]$/, 'Item');
  return segment
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join('');
}

/**
 * Emit a contract enum as an expandable string enum: a final class extending the handwritten
 * ExpandableStringEnum base, in the shape of the Azure SDK's ExpandableStringEnum.
 *
 * The API contract is open-response and closed-request. A server may add a value to any enum, so
 * the class keeps the raw wire token instead of mapping it onto a fixed Java enum: fromString
 * decodes any token (a declared one to its canonical constant), toWire returns the token unchanged
 * so a value read from a response can be sent back, and the server rejects what a request may not
 * carry. fromWire stays strict for tokens a caller types in. The nested Value enum lists the
 * declared values plus UNKNOWN, for switch statements.
 *
 * @param {string} typeName       simple Java name of the class
 * @param {string} qualifiedName  name used in messages, e.g. "CrosswalkOutcome.Disposition"
 * @param {string[]} values       declared wire tokens
 * @param {string} description    schema description for the Javadoc summary
 * @param {boolean} nested        whether the class is a member of a generated record
 * @returns {string}              the class declaration, unindented
 */
// Names every generated contract enum declares itself: Value.UNKNOWN and two private indexes.
const RESERVED_ENUM_CONSTANTS = new Set(['UNKNOWN', 'BY_TOKEN', 'IN_ORDER']);

function enumDeclaration(typeName, qualifiedName, values, description, nested = false) {
  const tokensByConstant = new Map();
  const declared = values.map((value) => {
    const constant = enumConstant(value);
    if (RESERVED_ENUM_CONSTANTS.has(constant)) {
      throw new Error(
        `[gen] ${qualifiedName} declares the wire value "${value}", whose constant ${constant} ` +
          'collides with a member every contract enum declares',
      );
    }
    if (tokensByConstant.has(constant)) {
      throw new Error(
        `[gen] ${qualifiedName} declares the wire values "${tokensByConstant.get(constant)}" and ` +
          `"${value}", which both become the constant ${constant}`,
      );
    }
    tokensByConstant.set(constant, value);
    return { value, constant };
  });
  const valueMembers = declared.map(({ value, constant }) => `    /** The {@code ${value}} wire value. */\n    ${constant},`);
  valueMembers.push(
    '    /** A value this SDK version does not know: a newer server added it. */\n    UNKNOWN',
  );
  const constants = declared.map(
    ({ value, constant }) =>
      `  /** The {@code ${value}} wire value. */\n` +
      `  public static final ${typeName} ${constant} = new ${typeName}("${value}", Value.${constant});`,
  );
  const modifiers = nested ? 'public static final class' : 'public final class';
  return `/**
 * ${wrapText(toJavadocSummary(description, typeName), 96).join('\n * ')}
 *
 * <p>An expandable string enum: a newer server can add values, and one this SDK version does not
 * know keeps its wire token. See {@link ExpandableStringEnum}.
 */
${modifiers} ${typeName} extends ExpandableStringEnum<${typeName}.Value> {

  /** The values this SDK version knows, plus {@code UNKNOWN}, for use in a {@code switch}. */
  public enum Value {
${valueMembers.join('\n')}
  }

${constants.join('\n\n')}

  private static final java.util.Map<String, ${typeName}> BY_TOKEN =
      index(${declared.map(({ constant }) => constant).join(', ')});

  private static final java.util.List<${typeName}> IN_ORDER =
      java.util.List.copyOf(BY_TOKEN.values());

  private ${typeName}(String token, Value value) {
    super(token, value, value != Value.UNKNOWN);
  }

  /**
   * Decodes a wire token leniently, for a token read from a response.
   *
   * @param token the wire token
   * @return the canonical constant for a declared token, an instance carrying the token unchanged
   *     for any other, or {@code null} when {@code token} is {@code null}
   */
  public static ${typeName} fromString(String token) {
    if (token == null) return null;
    ${typeName} declared = BY_TOKEN.get(token);
    return declared != null ? declared : new ${typeName}(token, Value.UNKNOWN);
  }

  /**
   * Decodes a wire token strictly, for a token a caller supplies.
   *
   * @param token the wire token
   * @return the canonical constant, or {@code null} when {@code token} is {@code null}
   * @throws IllegalArgumentException when the token is not a declared value
   */
  public static ${typeName} fromWire(String token) {
    if (token == null) return null;
    ${typeName} declared = BY_TOKEN.get(token);
    if (declared == null) {
      throw new IllegalArgumentException("Unknown ${qualifiedName} wire value: " + token);
    }
    return declared;
  }

  /**
   * The declared values, in contract order.
   *
   * @return an immutable list of the declared constants; never includes an undeclared token
   */
  public static java.util.List<${typeName}> values() {
    return IN_ORDER;
  }
}`;
}

/** Emit a top-level wire enum source file. */
function generateEnum(className, values, description) {
  return `package ${javaPackage};\n\n${enumDeclaration(className, className, values, description)}\n`;
}

// Promote the file-local AccessRule definition to a real public type.
// Its five values are canonical and must never be widened to String/Object.
const categoryAccessSchema = JSON.parse(
  fs.readFileSync(path.join(schemasDir, 'category-access-result.schema.json'), 'utf8'),
);
const accessRuleSchema = categoryAccessSchema.definitions?.AccessRule;
if (!accessRuleSchema?.enum?.length) {
  throw new Error('[gen] category-access-result must define the AccessRule enum');
}
fs.writeFileSync(
  path.join(outputDir, 'AccessRule.java'),
  generateEnum('AccessRule', accessRuleSchema.enum, accessRuleSchema.description),
  'utf8',
);
generatedClassNames.add('AccessRule');
generated++;

// These are the reviewed Java HTTP roots. References are walked recursively so
// a contract change cannot silently introduce an absent component. Existing
// canonical Java DTOs are reused; only missing reachable records are emitted.
const OPENAPI_PUBLIC_ROOTS = [
  ...namedContextEnvelopes,
  'CtxAccessDescribeRequirementsRequestBody',
  'CtxDescribeResponseBody',
  'CtxDocumentAssessAccessRequestBody',
  'CtxDocumentAuditRequestBody',
  'ProcessingContext',
  'Reproducibility',
  'RenderPreservation',
  'CategoryView',
  'ConceptView',
  'FormatView',
  'LabelPresetView',
  'SchemeGrammar',
  'SchemeScope',
  'SchemeSummary',
];

// These top-level contract types are generated from the OpenAPI contract, so an
// enum's declared values always match the contract instead of an SDK-only
// vocabulary.
const GENERATED_OPENAPI_OWNERS = new Set([
  'AccessComparisonResult',
  'AccessOperandSource',
  'AccessOperand',
  'AccessRequirementsDescription',
  'AccessRuleActivation',
  'AccessRuleDescription',
  'AccessScenarioAssessment',
  'ArtifactReference',
  'AssertionReference',
  'AssertionSupport',
  'AssessmentLimitation',
  'Attribution',
  'ContentChangeRequirement',
  'ContentEvidenceSpan',
  'ContextResolution',
  'DecisionProposalOrigin',
  'DocumentAccessAssessment',
  'DocumentAuditReport',
  'DocumentGuideAssessment',
  'DocumentResponseCounts',
  'DocumentResponseProfile',
  'DocumentResponseProjection',
  'DocumentUnmarkedSpan',
  'EffectiveDocumentTarget',
  'Fix',
  'FixAction',
  'GuideCompilationStatus',
  'GuideCompiledDetail',
  'GuideCompiledResult',
  'GuideContentEvaluation',
  'GuideDeterminationInput',
  'GuideFactReference',
  'GuideFindingDetermination',
  'GuideNomination',
  'GuideScopeCoverage',
  'GuideUnitEvaluation',
  'ManualGuideSelection',
  'ManualGuideSelectionInput',
  'MarkingValidation',
  'Reproducibility',
  'RuleReference',
  'RuleSourceReference',
  'SourceReference',
  'SubjectContextRequirement',
  'TraceInference',
  'TraceRepair',
  'TraceValidationIssue',
  'WarningKind',
]);

const NAMED_FIELD_TYPES = new Map([
  ['RuleSourceReference.reproducibility', 'Reproducibility'],
  ['SourceReference.reproducibility', 'Reproducibility'],
]);

// These contract locations intentionally carry opaque JSON values. Everything
// else in the reviewed graph must have a concrete supported Java shape.
const OPAQUE_COMPONENTS = new Set(['MatchNode', 'RuleWhen', 'RuleWhenNode', 'Win']);
const OPAQUE_FIELDS = new Set([
  'CategoryView.defaultConcept',
  'CategoryView.sort',
  'CtxDocumentAssessAccessRequestBody.context',
  'CtxDocumentAssessAccessRequestBody.userAttributes',
  'RuleCondition.value',
]);

const reachableOpenapi = new Set();
function collectOpenapiRefs(name) {
  if (reachableOpenapi.has(name)) return;
  const schema = openapi.components.schemas[name];
  if (!schema) throw new Error(`[gen] missing OpenAPI component referenced by public root: ${name}`);
  reachableOpenapi.add(name);
  // An opaque field is carried as a plain value, so what it refers to is not part of the Java API.
  const properties = Object.fromEntries(
    Object.entries(schema.properties ?? {}).filter(([field]) => !OPAQUE_FIELDS.has(`${name}.${field}`)),
  );
  const visit = (value) => {
    if (Array.isArray(value)) return value.forEach(visit);
    if (value == null || typeof value !== 'object') return;
    if (value.$ref?.startsWith('#/components/schemas/')) {
      collectOpenapiRefs(value.$ref.split('/').at(-1));
    }
    Object.values(value).forEach(visit);
  };
  visit(schema.properties ? { ...schema, properties } : schema);
}
OPENAPI_PUBLIC_ROOTS.forEach(collectOpenapiRefs);

const handwrittenDir = new URL('../src/main/java/com/ezclassify/rest/contract/', import.meta.url);
const handwrittenClassNames = new Set(
  fs.readdirSync(handwrittenDir).filter((name) => name.endsWith('.java')).map((name) => name.slice(0, -5)),
);

/** Return every token from one closed string branch, or null for another shape. */
function closedStringValues(schema) {
  if (schema.$ref) {
    const name = schema.$ref.split('/').at(-1);
    const referenced = openapi.components.schemas[name];
    return referenced ? closedStringValues(referenced) : null;
  }
  if (schema.type !== 'string') return null;
  if (Array.isArray(schema.enum) && schema.enum.every((value) => typeof value === 'string')) {
    return schema.enum;
  }
  return typeof schema.const === 'string' ? [schema.const] : null;
}

/** Flatten an enum-ref plus string-const union into one closed string schema. */
function flattenClosedStringUnion(schema) {
  const variants = schema.oneOf || schema.anyOf;
  if (!variants) return null;
  const values = variants.map(closedStringValues);
  if (values.some((value) => value == null)) return null;
  return {
    ...schema,
    type: 'string',
    enum: [...new Set(values.flat())],
    oneOf: undefined,
    anyOf: undefined,
  };
}

/** Return the only non-null variant of an explicit OAS 3.1 nullable union. */
function nullableVariant(schema) {
  const variants = schema.oneOf || schema.anyOf;
  if (!Array.isArray(variants) || variants.length !== 2) return null;
  const nullBranches = variants.filter(
    (variant) => variant.type === 'null' && Object.keys(variant).length === 1,
  );
  if (nullBranches.length !== 1) return null;
  return variants.find((variant) => variant !== nullBranches[0]);
}

function openapiType(schema, location, required = false, nestedTypes = null) {
  if (NAMED_FIELD_TYPES.has(location)) return NAMED_FIELD_TYPES.get(location);
  if (schema.$ref) {
    const name = schema.$ref.split('/').at(-1);
    const referenced = openapi.components.schemas[name];
    // OpenAPI aliases do not have a distinct Java runtime identity. Bind the
    // field to the canonical referenced record instead of inventing a wrapper.
    if (referenced?.$ref) return openapiType(referenced, location, required, nestedTypes);
    if (referenced?.type === 'object' && !referenced.properties && referenced.additionalProperties) {
      const valueType = boxed(
        openapiType(referenced.additionalProperties, `${name}.*`, true, nestedTypes),
      );
      return `java.util.Map<String, ${valueType}>`;
    }
    return OPAQUE_COMPONENTS.has(name) ? 'Object' : name;
  }
  if (schema.oneOf || schema.anyOf) {
    if (OPAQUE_FIELDS.has(location)) return 'Object';
    const nullable = nullableVariant(schema);
    if (nullable) return openapiType(nullable, location, false, nestedTypes);
    const flattened = flattenClosedStringUnion(schema);
    if (flattened) return openapiType(flattened, location, required, nestedTypes);
    throw new Error(`[gen] unsupported public union at ${location}`);
  }
  if (schema.allOf) {
    if (schema.allOf.length !== 1) throw new Error(`[gen] unsupported public allOf at ${location}`);
    return openapiType(schema.allOf[0], location, required, nestedTypes);
  }
  if (Array.isArray(schema.type)) {
    if (OPAQUE_FIELDS.has(location)) return 'Object';
    if (schema.type.length === 2 && schema.type.includes('null')) {
      const nonNullType = schema.type.find((type) => type !== 'null');
      return openapiType({ ...schema, type: nonNullType }, location, false, nestedTypes);
    }
  }
  if (schema.enum) {
    if (!nestedTypes) throw new Error(`[gen] inline enum outside a record at ${location}`);
    const name = nestedTypeName(location);
    nestedTypes.set(name, { kind: 'enum', name, schema });
    return name;
  }
  if (schema.type === 'array') {
    return `java.util.List<${boxed(openapiType(schema.items || {}, `${location}[]`, true, nestedTypes))}>`;
  }
  if (schema.type === 'object') {
    if (schema.properties && !OPAQUE_FIELDS.has(location)) {
      if (!nestedTypes) throw new Error(`[gen] anonymous object outside a record at ${location}`);
      const name = nestedTypeName(location);
      nestedTypes.set(name, { kind: 'record', name, schema, location });
      return name;
    }
    return 'java.util.Map<String, Object>';
  }
  if (schema.type === 'string') return 'String';
  if (schema.const != null) return typeof schema.const === 'string' ? 'String' : 'Object';
  if (schema.type === 'integer') return required ? 'int' : 'Integer';
  if (schema.type === 'number') return required ? 'double' : 'Double';
  if (schema.type === 'boolean') return required ? 'boolean' : 'Boolean';
  if (!schema.type && !schema.properties) return 'Object';
  throw new Error(`[gen] unsupported OpenAPI shape at ${location}: ${JSON.stringify(schema)}`);
}

/** A property declaration without its annotations, for comparing two variants' declarations. */
function declarationShape(property) {
  const { description, example, examples, ...shape } = property;
  return JSON.stringify(shape);
}

/** The name of the enum component a declaration refers to, or null for any other declaration. */
function namedEnumComponent(declaration) {
  if (!declaration.$ref) return null;
  const name = declaration.$ref.split('/').at(-1);
  return Array.isArray(openapi.components.schemas[name]?.enum) ? name : null;
}

/**
 * Merge the variants' declarations of one union property into the declaration the carrier uses.
 *
 * Declarations that agree keep that declaration. When every variant names its own enum for the
 * field, the field's vocabulary is all of them, so the carrier declares one enum holding every
 * value; typing it as one variant's enum would decode the others' values as unknown tokens. Other
 * closed string sets, such as the literal that tags each variant, stay a String. Any other
 * disagreement has no Java type and stops generation.
 */
function mergedUnionProperty(location, declarations) {
  if (new Set(declarations.map(declarationShape)).size === 1) return declarations[0];
  const enums = declarations.map(namedEnumComponent);
  if (enums.every(Boolean)) {
    const names = [...new Set(enums)];
    return {
      type: 'string',
      enum: [...new Set(declarations.flatMap(closedStringValues))],
      description: `A value of ${names.map((name) => `{@link ${name}}`).join(' or ')}.`,
    };
  }
  if (declarations.every((declaration) => closedStringValues(declaration) != null)) {
    const description = mergedTagDescription(declarations);
    return description ? { type: 'string', description } : { type: 'string' };
  }
  throw new Error(`[gen] incompatible public union property at ${location}`);
}

/**
 * The description of a union field each variant tags with its own closed values: the variants'
 * shared text, or each variant's values followed by its own text. Undefined when no variant
 * describes the field.
 *
 * @param {object[]} declarations  the variants' declarations of the field, in variant order
 */
function mergedTagDescription(declarations) {
  const texts = [...new Set(declarations.map((declaration) => declaration.description).filter(Boolean))];
  if (texts.length <= 1) return texts[0];
  return declarations
    .filter((declaration) => declaration.description)
    .map((declaration) => {
      const values = closedStringValues(declaration).map((value) => `\`${value}\``).join(', ');
      return `${values}: ${declaration.description}`;
    })
    .join(' ');
}

/** Resolve an OpenAPI `#/components/schemas/Name` reference to its schema. */
function lookupOpenapiComponent(ref) {
  return openapi.components.schemas[ref.split('/').at(-1)];
}

function generateOpenapiRecord(className, schema) {
  if (schema.anyOf) {
    const variants = schema.anyOf.map((variant) => {
      if (!variant.$ref) return variant;
      const name = variant.$ref.split('/').at(-1);
      const resolved = openapi.components.schemas[name];
      if (!resolved) throw new Error(`[gen] missing public union variant ${name}`);
      return resolved;
    });
    if (!variants.every((variant) => variant.type === 'object' && variant.properties)) {
      throw new Error(`[gen] public component ${className} has an unsupported union`);
    }
    const declarations = new Map();
    for (const variant of variants) {
      for (const [name, property] of Object.entries(variant.properties)) {
        if (!declarations.has(name)) declarations.set(name, []);
        declarations.get(name).push(property);
      }
    }
    const properties = Object.fromEntries(
      [...declarations].map(([name, declared]) => [
        name,
        mergedUnionProperty(`${className}.${name}`, declared),
      ]),
    );
    const required = variants
      .map((variant) => new Set(variant.required || []))
      .reduce((left, right) => new Set([...left].filter((name) => right.has(name))));
    schema = { ...schema, type: 'object', properties, required: [...required], anyOf: undefined };
  }
  if (schema.type !== 'object' || !schema.properties) {
    throw new Error(`[gen] public component ${className} is not a supported object record`);
  }
  const nestedTypes = new Map();
  const required = new Set(schema.required || []);
  const fields = Object.entries(schema.properties).map(([field, value]) => ({
    name: toJavaFieldName(field),
    wireName: field,
    javaType: openapiType(value, `${className}.${field}`, required.has(field), nestedTypes),
    description: propertyDescription(value, lookupOpenapiComponent),
  }));
  const params = fields.map((field) => `    ${field.javaType} ${field.name}`).join(',\n');
  const classDoc = javadocComment('', toJavadocSummary(schema.description, className), componentParams(className, fields));
  const constants = fields.flatMap((field) => [
    `    /** JSON field name for {@code ${field.wireName}}. */`,
    `    public static final String ${toConstantName(field.wireName)} = "${field.wireName}";`,
  ]);
  const generatedNested = new Set();
  const nestedSources = [];
  function emitNested(type) {
    if (generatedNested.has(type.name)) return;
    generatedNested.add(type.name);
    if (type.kind === 'enum') {
      const declaration = enumDeclaration(
        type.name,
        `${className}.${type.name}`,
        type.schema.enum,
        type.schema.description,
        true,
      );
      nestedSources.push(
        declaration
          .split('\n')
          .map((line) => (line ? `  ${line}` : line))
          .join('\n'),
      );
      return;
    }
    const childRequired = new Set(type.schema.required || []);
    const childFields = Object.entries(type.schema.properties).map(([field, value]) => ({
      name: toJavaFieldName(field),
      wireName: field,
      description: propertyDescription(value, lookupOpenapiComponent),
      javaType: openapiType(
        value,
        `${type.location}.${field}`,
        childRequired.has(field),
        nestedTypes,
      ),
    }));
    const childParams = childFields
      .map((field) => `      ${field.javaType} ${field.name}`)
      .join(',\n');
    const nestedDoc = javadocComment(
      '  ',
      toJavadocSummary(type.schema.description, type.name),
      componentParams(`${className}.${type.name}`, childFields),
    );
    nestedSources.push(`${nestedDoc}\n  public record ${type.name}(\n${childParams}\n  ) {}`);
  }
  // Resolving one nested record can discover deeper nested records or enums.
  for (const type of nestedTypes.values()) emitNested(type);
  const body = [constants.join('\n'), ...nestedSources].filter(Boolean).join('\n\n');
  return `package ${javaPackage};\n\n${classDoc}\npublic record ${className}(\n${params}\n) {\n\n${body}\n}\n`;
}

for (const name of [...reachableOpenapi].sort()) {
  if (
    OPAQUE_COMPONENTS.has(name) ||
    generatedClassNames.has(name) ||
    (handwrittenClassNames.has(name) && !GENERATED_OPENAPI_OWNERS.has(name))
  ) {
    continue;
  }
  const schema = openapi.components.schemas[name];
  if (schema.$ref) continue;
  if (schema.type === 'object' && !schema.properties && schema.additionalProperties) continue;
  // A union of string constants is a closed string enum, however the contract spells it.
  const closedStrings = schema.enum ? schema : flattenClosedStringUnion(schema);
  const source = closedStrings
    ? generateEnum(name, closedStrings.enum, schema.description)
    : generateOpenapiRecord(name, schema);
  fs.writeFileSync(path.join(outputDir, `${name}.java`), source, 'utf8');
  generatedClassNames.add(name);
  console.log(`[gen] generated ${name}.java from reviewed OpenAPI graph`);
  generated++;
}

// Emitted last: a local definition only needs its own class when no schema
// root, OpenAPI component, or reviewed handwritten seam already owns the name.
for (const [name, schema] of [...referencedLocalDefinitions].sort(([a], [b]) => a.localeCompare(b))) {
  if (resolveRef(`#/definitions/${encodeURIComponent(name)}`).startsWith('java.util.Map<')) continue;
  if (generatedClassNames.has(name) || handwrittenClassNames.has(name)) continue;
  // An instantiated schema refers to the existing generic Java owner. Emitting
  // another record would duplicate that owner and create an illegal filename.
  if (name.includes('<')) {
    const owner = name.slice(0, name.indexOf('<'));
    if (generatedClassNames.has(owner) || handwrittenClassNames.has(owner)) continue;
    throw new Error(`[gen] unsupported file-local generic owner: ${name}`);
  }
  const source = schema.enum
    ? generateEnum(name, schema.enum, schema.description)
    : generateRecord(name, schema);
  fs.writeFileSync(path.join(outputDir, `${name}.java`), source, 'utf8');
  generatedClassNames.add(name);
  console.log(`[gen] generated ${name}.java from a file-local schema definition`);
  generated++;
}

console.log(`[gen] done: ${generated} classes written to ${outputDir}`);
if (undocumentedComponents.length) {
  const owners = [...new Set(undocumentedComponents.map((component) => component.split('.')[0]))];
  console.log(
    `[gen] ${undocumentedComponents.length} record components in ${owners.length} records have no ` +
      `description in the contract schemas and carry a shape-only @param. Records: ${owners.join(', ')}`,
  );
}
