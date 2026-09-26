#!/usr/bin/env node
/**
 * Static consistency verification for the MC <-> Flarum bridge.
 *
 * This machine has no PHP or JDK toolchain, so `php -l` / `gradle build` cannot
 * run here. Instead this script verifies the invariants that actually break a
 * bridge integration:
 *
 *   1. every JSON file parses
 *   2. PHP files follow PSR-4 (namespace + class name vs. file path)
 *   3. every `use Stalir\McBridge\...` resolves to a real file
 *   4. every `::class` route/command/listener reference in extend.php exists
 *   5. Java packages/type names match their file paths
 *   6. every project import in the Java sources resolves
 *   7. plugin.yml main class, commands and permissions agree with the code
 *   8. every config path read by BridgeConfig exists in config.yml
 *   9. every message key used by the plugin exists in config.yml
 *  10. the PHP and Java HMAC canonical strings agree
 *  11. migration tables match the models, and every model is used
 *  12. every registered route is documented
 *
 * Usage: node tools/verify.mjs
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, dirname, relative, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const EXT = join(ROOT, 'flarum-extension');
const PLUGIN = join(ROOT, 'mc-plugin');

const errors = [];
const warnings = [];
let checkCount = 0;

function pass(label) {
  checkCount++;
  console.log(`  \u001b[32mPASS\u001b[0m ${label}`);
}

function fail(label, detail) {
  checkCount++;
  errors.push(`${label} — ${detail}`);
  console.log(`  \u001b[31mFAIL\u001b[0m ${label}\n       ${detail}`);
}

function warn(label, detail) {
  warnings.push(`${label} — ${detail}`);
  console.log(`  \u001b[33mWARN\u001b[0m ${label}\n       ${detail}`);
}

function section(title) {
  console.log(`\n\u001b[36m${title}\u001b[0m`);
}

function walk(dir, filter) {
  const out = [];
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      out.push(...walk(full, filter));
    } else if (filter(full)) {
      out.push(full);
    }
  }
  return out;
}

const read = (file) => readFileSync(file, 'utf8');
const rel = (file) => relative(ROOT, file).split('\\').join('/');

/**
 * Blank out PHP comments so brace counting is not thrown off by prose that
 * happens to contain a brace. Newlines are preserved to keep offsets usable.
 */
function stripPhpComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, ' '))
    // Do not treat the "//" of "https://" inside a string as a comment.
    .replace(/(^|[^:'"\\])\/\/[^\n]*/g, '$1');
}

/**
 * Every closure in a PHP file, with its parameter list, its `use (...)` clause
 * and its brace-matched body.
 *
 * Nested closures are returned too: a closure does NOT inherit the enclosing
 * scope, so a helper may read a variable it never captured. See the check in
 * section 13 for why that matters here.
 */
function phpClosures(source) {
  const cleaned = stripPhpComments(source);
  const out = [];
  const pattern = /function\s*\(([^)]*)\)\s*(?:use\s*\(([^)]*)\)\s*)?\{/g;
  let match;

  while ((match = pattern.exec(cleaned)) !== null) {
    const bodyStart = match.index + match[0].length;
    let depth = 1;
    let index = bodyStart;

    while (index < cleaned.length && depth > 0) {
      if (cleaned[index] === '{') depth++;
      else if (cleaned[index] === '}') depth--;
      index++;
    }

    out.push({
      params: match[1] ?? '',
      useClause: match[2] ?? '',
      body: cleaned.slice(bodyStart, Math.max(bodyStart, index - 1)),
    });

    // lastIndex stays just past the opening brace on purpose, so closures nested
    // inside this body are visited as well.
  }

  return out;
}

/**
 * A migration's `up` half only.
 *
 * The `down` closures drop columns, and `dropColumn('x')` is indistinguishable
 * from a column definition to the regexes below - which would make a rollback
 * look like it added the very column it removes.
 */
function upOnly(source) {
  const cleaned = stripPhpComments(source);
  const down = cleaned.indexOf("'down' =>");

  return down < 0 ? cleaned : cleaned.slice(0, down);
}

/**
 * Column names of every table a migration creates, keyed by table name.
 */
function createBlocks(source) {
  const cleaned = upOnly(source);
  const out = new Map();
  const pattern = /create\('([a-z_]+)'[^{]*\{/g;
  let match;

  while ((match = pattern.exec(cleaned)) !== null) {
    const bodyStart = match.index + match[0].length;
    let depth = 1;
    let index = bodyStart;

    while (index < cleaned.length && depth > 0) {
      if (cleaned[index] === '{') depth++;
      else if (cleaned[index] === '}') depth--;
      index++;
    }

    out.set(
      match[1],
      [...cleaned.slice(bodyStart, index - 1).matchAll(/\$table->\w+\('([a-z_]+)'/g)].map((m) => m[1])
    );
  }

  return out;
}

/**
 * Column names a migration adds to a table it does not create, keyed by table.
 *
 * A table is not defined by one migration alone: a later migration legitimately
 * adds columns with `$schema->table(...)`, and a model that fills those columns
 * is correct. Reading only `create(...)` blocks would report every such column as
 * missing from the migration.
 */
function alterBlocks(source) {
  const cleaned = upOnly(source);
  const out = new Map();
  const pattern = /->table\('([a-z_]+)'[^{]*\{/g;
  let match;

  while ((match = pattern.exec(cleaned)) !== null) {
    const bodyStart = match.index + match[0].length;
    let depth = 1;
    let index = bodyStart;

    while (index < cleaned.length && depth > 0) {
      if (cleaned[index] === '{') depth++;
      else if (cleaned[index] === '}') depth--;
      index++;
    }

    const columns = [...cleaned.slice(bodyStart, index - 1).matchAll(/\$table->(\w+)\('([a-z_]+)'/g)]
      .filter((column) => !column[1].startsWith('drop'))
      .map((column) => column[2]);

    if (columns.length > 0) {
      out.set(match[1], [...(out.get(match[1]) ?? []), ...columns]);
    }
  }

  return out;
}

// ---------------------------------------------------------------------------
// 0. Minimal YAML subset parser (maps, nested maps, string lists)
// ---------------------------------------------------------------------------

function unquote(value) {
  const trimmed = value.trim();
  if (
    (trimmed.startsWith('"') && trimmed.endsWith('"')) ||
    (trimmed.startsWith("'") && trimmed.endsWith("'"))
  ) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function parseYaml(text) {
  const items = [];

  for (const raw of text.split(/\r?\n/)) {
    const noComment = raw.replace(/(^|\s)#.*$/, '$1');
    if (!noComment.trim()) continue;
    items.push({
      indent: noComment.match(/^ */)[0].length,
      text: noComment.trim(),
    });
  }

  let pos = 0;

  function parseBlock(indent) {
    if (pos >= items.length || items[pos].indent < indent) return null;

    const isArray = items[pos].text.startsWith('- ');
    const node = isArray ? [] : {};

    while (pos < items.length && items[pos].indent === indent) {
      const current = items[pos];

      if (isArray) {
        if (!current.text.startsWith('- ')) break;
        const rest = current.text.slice(2).trim();
        pos++;
        node.push(rest === '' ? parseBlock(indent + 2) : unquote(rest));
        continue;
      }

      const match = current.text.match(/^([^:]+):\s*(.*)$/);
      if (!match) break;

      const key = match[1].trim();
      const value = match[2].trim();
      pos++;

      if (value === '') {
        const child = parseBlock(indent + 2);
        node[key] = child === null ? null : child;
      } else {
        node[key] = unquote(value);
      }
    }

    return node;
  }

  return parseBlock(items.length ? items[0].indent : 0) ?? {};
}

function lookupPath(object, path) {
  let node = object;
  for (const part of path.split('.')) {
    if (node === null || typeof node !== 'object' || !(part in node)) return undefined;
    node = node[part];
  }
  return node;
}

/** Flatten a parsed YAML tree into the set of leaf key paths. */
function flattenKeys(node, prefix = '') {
  const keys = new Set();

  if (node === null || typeof node !== 'object' || Array.isArray(node)) {
    if (prefix) keys.add(prefix);
    return keys;
  }

  for (const [key, value] of Object.entries(node)) {
    const path = prefix ? `${prefix}.${key}` : key;

    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      for (const child of flattenKeys(value, path)) keys.add(child);
    } else {
      keys.add(path);
    }
  }

  return keys;
}

// ---------------------------------------------------------------------------
// 1. JSON files
// ---------------------------------------------------------------------------

section('1. JSON files parse');

// npm packages may ship non-strict JSON, so dependencies are not validated.
// Build output is skipped: a Gradle build leaves generated JSON (Minecraft
// toolchain reports, IDE metadata) under */build/, and asserting on files this
// script did not write would turn an unrelated build into a false failure.
const jsonFiles = walk(ROOT, (file) => {
  const path = rel(file);

  return path.endsWith('.json')
    && !path.includes('node_modules')
    && !path.includes('/build/')
    && !path.includes('.gradle/');
});

for (const file of jsonFiles) {
  try {
    JSON.parse(read(file));
    pass(`${rel(file)}`);
  } catch (exception) {
    fail(`${rel(file)}`, exception.message);
  }
}

// ---------------------------------------------------------------------------
// 2/3. PHP PSR-4 and imports
// ---------------------------------------------------------------------------

section('2. PHP PSR-4 layout');

const phpFiles = walk(EXT, (file) => file.endsWith('.php'));
const phpSources = phpFiles.filter((file) => rel(file).includes('/src/'));
const definedClasses = new Map(); // FQCN -> file

for (const file of phpSources) {
  const source = read(file);
  const namespaceMatch = source.match(/^namespace\s+([A-Za-z0-9_\\]+);/m);
  const classMatch = source.match(/^(?:final\s+|abstract\s+)?(?:class|interface|trait|enum)\s+([A-Za-z0-9_]+)/m);

  if (!namespaceMatch) {
    fail(`PSR-4 ${rel(file)}`, 'missing namespace declaration');
    continue;
  }

  if (!classMatch) {
    fail(`PSR-4 ${rel(file)}`, 'no class/interface declared');
    continue;
  }

  const namespace = namespaceMatch[1];
  const className = classMatch[1];
  const expectedNamespace = 'Stalir\\McBridge\\' + dirname(rel(file).split('/src/')[1]).split('/').filter(Boolean).join('\\');
  const expectedNamespaceClean = expectedNamespace.endsWith('\\') ? expectedNamespace.slice(0, -1) : expectedNamespace;
  const expectedFile = `${className}.php`;

  if (basename(file) !== expectedFile) {
    fail(`PSR-4 ${rel(file)}`, `class ${className} must live in ${expectedFile}`);
  } else if (namespace !== expectedNamespaceClean) {
    fail(`PSR-4 ${rel(file)}`, `namespace ${namespace} should be ${expectedNamespaceClean}`);
  } else {
    pass(`${rel(file)} -> ${namespace}\\${className}`);
  }

  definedClasses.set(`${namespace}\\${className}`, file);
}

section('3. PHP imports resolve');

for (const file of phpFiles) {
  const source = read(file);
  const imports = [...source.matchAll(/^use\s+(Stalir\\McBridge\\[A-Za-z0-9_\\]+);/gm)].map((m) => m[1]);

  for (const imported of imports) {
    if (definedClasses.has(imported)) {
      pass(`${rel(file)} imports ${imported}`);
    } else {
      fail(`${rel(file)}`, `import ${imported} does not resolve to a class in src/`);
    }
  }
}

// The loop above proves only that every import points at something real. It
// cannot see the mirror-image mistake: a class USED without being imported. PHP
// then resolves the name against the current namespace, so `new McOutboxMessage()`
// inside Stalir\McBridge\Api\Controller looks for
// Stalir\McBridge\Api\Controller\McOutboxMessage, finds nothing, and throws
// "Class ... not found" at runtime - turning the whole request into a 500.
// 0.0.13 shipped exactly that in LinkController, which is why /bind failed with
// "无法读取绑定状态" while every static check stayed green.
//
// Only names matching a class this project defines are considered, so PHP
// built-ins and vendor classes can never trip it.
const shortNames = new Map(); // short class name -> [FQCN]

for (const fqcn of definedClasses.keys()) {
  const short = fqcn.split('\\').pop();
  if (!shortNames.has(short)) shortNames.set(short, []);
  shortNames.get(short).push(fqcn);
}

let missingImportIssues = 0;

for (const file of phpSources) {
  // Comments are stripped so a @var docblock naming a class does not count.
  const source = stripPhpComments(read(file));
  const namespace = (source.match(/^namespace\s+([A-Za-z0-9_\\]+);/m) ?? [])[1] ?? '';
  const ownClass = (source.match(/^(?:final\s+|abstract\s+)?(?:class|interface|trait|enum)\s+([A-Za-z0-9_]+)/m) ?? [])[1] ?? '';

  // Alias -> FQCN, honouring both `use A\B;` and `use A\B as C;`.
  const aliases = new Map();

  for (const match of source.matchAll(/^\s*use\s+([A-Za-z0-9_\\]+?)(?:\s+as\s+([A-Za-z0-9_]+))?\s*;/gm)) {
    aliases.set(match[2] ?? match[1].split('\\').pop(), match[1]);
  }

  const referenced = new Set();

  // Every form below resolves the name against the current namespace when it is
  // not imported, and every one of them is fatal at runtime. Fully qualified
  // references (\Foo\Bar) are excluded by the negative lookbehind, because those
  // need no import.
  for (const match of source.matchAll(/\bnew\s+([A-Z][A-Za-z0-9_]*)\s*\(/g)) referenced.add(match[1]);
  for (const match of source.matchAll(/(?<![\\\w])([A-Z][A-Za-z0-9_]*)::/g)) referenced.add(match[1]);
  for (const match of source.matchAll(/\bextends\s+([A-Z][A-Za-z0-9_]*)\b/g)) referenced.add(match[1]);
  for (const match of source.matchAll(/\binstanceof\s+([A-Z][A-Za-z0-9_]*)\b/g)) referenced.add(match[1]);
  for (const match of source.matchAll(/\bcatch\s*\(\s*([A-Z][A-Za-z0-9_]*)/g)) referenced.add(match[1]);
  // Type hints: "Foo $bar", "?Foo $bar", "protected Foo $bar".
  for (const match of source.matchAll(/(?<![\\\w])([A-Z][A-Za-z0-9_]*)\s+\$[A-Za-z_]/g)) referenced.add(match[1]);
  // Return types: "): Foo" / "): ?Foo".
  for (const match of source.matchAll(/\)\s*:\s*\??([A-Z][A-Za-z0-9_]*)/g)) referenced.add(match[1]);

  for (const match of source.matchAll(/\bimplements\s+([A-Za-z0-9_\\, ]+)/g)) {
    for (const part of match[1].split(',')) {
      const name = part.trim();
      if (/^[A-Z][A-Za-z0-9_]*$/.test(name)) referenced.add(name);
    }
  }

  for (const name of referenced) {
    if (aliases.has(name)) continue; // imported (possibly under an alias)
    if (name === ownClass) continue; // the file's own class
    if (!shortNames.has(name)) continue; // not a class of this project
    if (definedClasses.has(`${namespace}\\${name}`)) continue; // same namespace, no import needed

    fail(
      rel(file),
      `${name} is used but never imported; PHP resolves it to "${namespace}\\${name}", ` +
        `which does not exist. It is defined as ${shortNames.get(name).join(' or ')} - ` +
        'add the use statement.'
    );
    missingImportIssues++;
  }
}

if (missingImportIssues === 0 && phpSources.length > 0) {
  pass('every project class is imported, or in the same namespace, wherever it is used');
}

// ---------------------------------------------------------------------------
// 4. extend.php references
// ---------------------------------------------------------------------------

section('4. extend.php references');

{
  const file = join(EXT, 'extend.php');
  const source = read(file);
  const aliases = new Map();

  for (const match of source.matchAll(/^use\s+([A-Za-z0-9_\\]+)\\([A-Za-z0-9_]+);/gm)) {
    aliases.set(match[2], `${match[1]}\\${match[2]}`);
  }

  const references = [...source.matchAll(/([A-Za-z0-9_]+)::class/g)].map((m) => m[1]);

  if (references.length === 0) {
    fail('extend.php', 'no ::class references found');
  }

  for (const alias of new Set(references)) {
    const fqcn = aliases.get(alias);

    if (!fqcn) {
      fail('extend.php', `${alias}::class has no matching use statement`);
    } else if (!fqcn.startsWith('Stalir\\McBridge\\')) {
      // Flarum core / third-party classes are resolved by the framework.
      pass(`extend.php -> ${fqcn} (external)`);
    } else if (!definedClasses.has(fqcn)) {
      fail('extend.php', `${alias}::class -> ${fqcn} does not exist`);
    } else {
      pass(`extend.php -> ${fqcn}`);
    }
  }
}

// ---------------------------------------------------------------------------
// 4b. mc-bridge.* settings keys
// ---------------------------------------------------------------------------

section('4b. mc-bridge settings keys');

{
  // Every mc-bridge.* key the PHP code mentions has to be declared as a default
  // in extend.php. An undeclared key is not an error to Flarum - get() simply
  // returns the caller's fallback - which is exactly what makes a typo so
  // expensive: the feature it feeds goes quiet and nothing says why.
  //
  // Keys reach the settings repository in two shapes: as a string literal, and
  // through a class constant (BridgeMessages::SETTING_KEY, ReportDiscussion::
  // TAG_SETTING). Only calls on a settings repository count - extend.php is full
  // of other mc-bridge.* strings, because route names look exactly like settings
  // keys.
  const declared = new Set(
    [...read(join(EXT, 'extend.php')).matchAll(/->default\('([^']+)'/g)].map((m) => m[1])
  );

  // Pass 1: the constants that hold a settings key, wherever they are defined.
  const constants = new Map(); // CONST_NAME -> key

  for (const file of phpFiles) {
    for (const match of stripPhpComments(read(file)).matchAll(
      /\bconst\s+([A-Za-z0-9_]+)\s*=\s*'(mc-bridge\.[A-Za-z0-9_.]+)'/g
    )) {
      constants.set(match[1], match[2]);
    }
  }

  // Pass 2: every key actually handed to a settings repository.
  const mentioned = new Map(); // key -> file that uses it

  const note = (key, file) => {
    if (key.startsWith('mc-bridge.') && !mentioned.has(key)) mentioned.set(key, file);
  };

  for (const file of phpFiles) {
    const source = stripPhpComments(read(file));

    for (const match of source.matchAll(/settings->(?:get|set)\(\s*'([^']+)'/g)) {
      note(match[1], file);
    }

    for (const match of source.matchAll(/settings->(?:get|set)\(\s*[A-Za-z0-9_\\]*::([A-Za-z0-9_]+)/g)) {
      note(constants.get(match[1]) ?? '', file);
    }
  }

  if (declared.size === 0) {
    fail('extend.php', 'no mc-bridge.* defaults declared');
  }

  let undeclared = 0;

  for (const [key, file] of mentioned) {
    if (declared.has(key)) {
      pass(`${key} is declared in extend.php (used by ${basename(file)})`);
    } else {
      fail(
        basename(file),
        `uses "${key}", which extend.php does not declare via Extend\Settings()->default(); ` +
          'Flarum would silently hand back the local fallback instead of the stored value'
      );
      undeclared++;
    }
  }

  // The reverse direction: a declared key nothing reads is usually a leftover.
  for (const key of declared) {
    if (!mentioned.has(key)) {
      warn('settings', `${key} is declared in extend.php but no settings call reads it`);
    }
  }

  if (undeclared === 0 && mentioned.size > 0) {
    pass(`all ${mentioned.size} mc-bridge settings keys used in PHP are declared`);
  }
}

// ---------------------------------------------------------------------------
// 5/6. Java layout and imports
// ---------------------------------------------------------------------------

section('5. Java package layout');

const javaRoot = join(PLUGIN, 'src/main/java');

// The plugin is assembled from a platform independent core plus one module per
// platform family. Each root is checked on its own, and imports are only allowed
// in the direction the build supports - a reference from the shared core into a
// platform module would be resolved when the *other* platform loads it and would
// fail with NoClassDefFoundError.
//
// The NeoForge module is its own Gradle project (ModDevGradle owns the Minecraft
// dependency), so its sources sit under neoforge/src/main/java rather than the
// src/<module>/java layout the other two use. `prefix` is therefore the path
// fragment stripped before the package path is compared.
const JAVA_MODULES = [
  { name: 'main', dir: javaRoot, prefix: '/src/main/java/' },
  { name: 'paper', dir: join(PLUGIN, 'src/paper/java'), prefix: '/src/paper/java/' },
  { name: 'neoforge', dir: join(PLUGIN, 'neoforge/src/main/java'), prefix: '/neoforge/src/main/java/' },
];

const MODULE_DEPENDENCIES = {
  main: ['main'],
  paper: ['main', 'paper'],
  neoforge: ['main', 'neoforge'],
};

const MODULE_PREFIX = Object.fromEntries(JAVA_MODULES.map((entry) => [entry.name, entry.prefix]));

const javaFiles = [];
const javaModuleOf = new Map(); // file -> module name
const javaTypes = new Map(); // FQCN -> file

for (const module of JAVA_MODULES) {
  for (const file of walk(module.dir, (candidate) => candidate.endsWith('.java'))) {
    javaFiles.push(file);
    javaModuleOf.set(file, module.name);
  }
}

for (const file of javaFiles) {
  const module = javaModuleOf.get(file);
  const source = read(file);
  const packageMatch = source.match(/^package\s+([A-Za-z0-9_.]+);/m);
  const typeMatch = source.match(/^(?:public\s+)?(?:final\s+|abstract\s+)?(?:class|interface|enum|record)\s+([A-Za-z0-9_]+)/m);

  if (!packageMatch || !typeMatch) {
    fail(`Java ${rel(file)}`, 'missing package or type declaration');
    continue;
  }

  const expectedPath = packageMatch[1].split('.').join('/');
  const relativeDir = dirname(rel(file).split(MODULE_PREFIX[module])[1] ?? '');

  if (relativeDir !== expectedPath) {
    fail(`Java ${rel(file)}`, `package ${packageMatch[1]} does not match directory ${relativeDir}`);
  } else if (basename(file, '.java') !== typeMatch[1]) {
    fail(`Java ${rel(file)}`, `type ${typeMatch[1]} must live in ${typeMatch[1]}.java`);
  } else {
    pass(`${rel(file)} -> ${packageMatch[1]}.${typeMatch[1]}`);
  }

  javaTypes.set(`${packageMatch[1]}.${typeMatch[1]}`, file);
}

section('6. Java project imports resolve');

for (const file of javaFiles) {
  const module = javaModuleOf.get(file);
  const imports = [...read(file).matchAll(/^import\s+(cn\.stalir\.mcbridge[A-Za-z0-9_.]*);/gm)].map((m) => m[1]);

  for (const imported of imports) {
    const target = javaTypes.get(imported);

    if (!target) {
      fail(rel(file), `import ${imported} does not resolve to a class in the plugin sources`);
      continue;
    }

    const targetModule = javaModuleOf.get(target);

    if (MODULE_DEPENDENCIES[module].includes(targetModule)) {
      pass(`${rel(file)} imports ${imported}`);
    } else {
      fail(
        rel(file),
        `the ${module} module imports ${imported} from the ${targetModule} module, which does not exist on every ` +
          'platform the jar supports'
      );
    }
  }
}

// ---------------------------------------------------------------------------
// 7. plugin.yml agreement
// ---------------------------------------------------------------------------

section('7. plugin.yml agreement');

const pluginYml = parseYaml(read(join(PLUGIN, 'src/paper/resources/plugin.yml')));
const pluginSources = javaFiles.map(read).join('\n');

if (!pluginYml.main) {
  fail('plugin.yml', 'no main class declared');
} else if (!javaTypes.has(pluginYml.main)) {
  fail('plugin.yml', `main ${pluginYml.main} does not exist`);
} else if (javaModuleOf.get(javaTypes.get(pluginYml.main)) !== 'paper') {
  fail('plugin.yml', `main ${pluginYml.main} must live in the paper module (src/paper/java)`);
} else {
  pass(`plugin.yml main -> ${pluginYml.main} (paper module)`);
}

// Folia refuses to load a plugin that does not opt in, and Velocity reads its
// own descriptor, so both have to be present for the universal jar to work.
// The YAML subset reader used here returns scalars as strings.
if (String(pluginYml['folia-supported']) === 'true') {
  pass('plugin.yml declares folia-supported: true');
} else {
  fail('plugin.yml', 'folia-supported must be true, otherwise Folia refuses to load the plugin');
}

const declaredCommands = Object.keys(pluginYml.commands ?? {});
const requestedCommands = [...pluginSources.matchAll(/getCommand\("([a-z0-9_-]+)"\)/g)].map((m) => m[1]);

for (const command of new Set(requestedCommands)) {
  if (declaredCommands.includes(command)) {
    pass(`command /${command} declared`);
  } else {
    fail('plugin.yml', `getCommand("${command}") is used but /${command} is not declared`);
  }
}

const declaredPermissions = Object.keys(pluginYml.permissions ?? {});
const usedPermissions = [...pluginSources.matchAll(/hasPermission\("([a-z0-9._-]+)"\)/g)].map((m) => m[1]);

for (const permission of new Set(usedPermissions)) {
  if (declaredPermissions.includes(permission)) {
    pass(`permission ${permission} declared`);
  } else {
    fail('plugin.yml', `permission ${permission} is checked but not declared`);
  }
}

for (const permission of declaredPermissions) {
  if (!new Set(usedPermissions).has(permission)) {
    warn('plugin.yml', `permission ${permission} is declared but never checked in code`);
  }
}

// ---------------------------------------------------------------------------
// 8. config.yml keys used by BridgeConfig
// ---------------------------------------------------------------------------

section('8. config.yml keys read by BridgeConfig');

const configYml = parseYaml(read(join(PLUGIN, 'src/main/resources/config.yml')));
const bridgeConfigSource = read(join(javaRoot, 'cn/stalir/mcbridge/BridgeConfig.java'));

const readKeys = [
  ...bridgeConfigSource.matchAll(
    /config\.(?:getString|getInt|getBoolean|getStringList|getConfigurationSection|isConfigurationSection)\("([^"]+)"/g
  ),
].map((m) => m[1]);

for (const key of new Set(readKeys)) {
  if (lookupPath(configYml, key) === undefined) {
    fail('config.yml', `BridgeConfig reads "${key}" but it is not defined`);
  } else {
    pass(`config path ${key}`);
  }
}

// ---------------------------------------------------------------------------
// 9. plugin localisation (lang/*.yml)
// ---------------------------------------------------------------------------

section('9. plugin language files');

const langDir = join(PLUGIN, 'src/main/resources/lang');
const langFileList = existsSync(langDir) ? walk(langDir, (file) => file.endsWith('.yml')) : [];
const pluginLangs = new Map();

for (const file of langFileList) {
  pluginLangs.set(basename(file, '.yml'), parseYaml(read(file)));
}

if (pluginLangs.size === 0) {
  fail('lang', 'no language files found under mc-plugin/src/main/resources/lang');
} else {
  pass(`${pluginLangs.size} language file(s): ${[...pluginLangs.keys()].join(', ')}`);
}

const defaultLangTree = pluginLangs.get('zh_CN');

if (!defaultLangTree) {
  fail('lang', 'the default language file lang/zh_CN.yml is missing');
} else {
  pass('default language lang/zh_CN.yml present');
}

// config.yml must select a language that exists, and must not carry messages.
{
  const configured = configYml.language;

  if (typeof configured !== 'string' || configured === '') {
    fail('config.yml', 'the language key is missing');
  } else if (!pluginLangs.has(configured)) {
    fail('config.yml', `language "${configured}" has no lang/${configured}.yml`);
  } else {
    pass(`config.yml language = ${configured}`);
  }

  if (lookupPath(configYml, 'messages') !== undefined) {
    fail('config.yml', 'messages belong in lang/*.yml; config.yml must not define a messages section');
  } else {
    pass('config.yml carries no messages section');
  }
}

// The Java constant must agree with what is shipped.
{
  const source = read(join(javaRoot, 'cn/stalir/mcbridge/Messages.java'));
  const declared = source.match(/DEFAULT_LANGUAGE\s*=\s*"([^"]+)"/);

  if (!declared) {
    fail('Messages.java', 'DEFAULT_LANGUAGE is not declared');
  } else if (!pluginLangs.has(declared[1])) {
    fail('Messages.java', `DEFAULT_LANGUAGE "${declared[1]}" has no lang/${declared[1]}.yml`);
  } else {
    pass(`Messages.DEFAULT_LANGUAGE = ${declared[1]}`);
  }

  const fallback = source.match(/FALLBACK_LANGUAGE\s*=\s*"([^"]+)"/);

  if (!fallback) {
    fail('Messages.java', 'FALLBACK_LANGUAGE is not declared');
  } else if (!pluginLangs.has(fallback[1])) {
    fail('Messages.java', `FALLBACK_LANGUAGE "${fallback[1]}" has no lang/${fallback[1]}.yml`);
  } else {
    pass(`Messages.FALLBACK_LANGUAGE = ${fallback[1]}`);
  }
}

// Every key the Java code asks for must exist in the default language.
const usedKeys = new Set();

for (const file of javaFiles) {
  const source = read(file);

  // Note: the leading dot is optional so that bare calls such as
  // raw("prefix") inside Messages.java are picked up too, and \s* allows the
  // key to sit on the line after the opening parenthesis (common in this
  // codebase). Keys chosen inside an expression are not detectable and should
  // be passed as plain literals instead.
  for (const match of source.matchAll(/\b(?:prefixed|render|plain|string|raw)\(\s*"([A-Za-z0-9_.-]+)"/g)) {
    usedKeys.add(match[1]);
  }

  for (const match of source.matchAll(/logText\(\s*"([A-Za-z0-9_.-]+)"/g)) {
    usedKeys.add(match[1]);
  }
}

if (defaultLangTree) {
  if (usedKeys.size === 0) {
    fail('messages', 'no message keys referenced from the Java sources');
  }

  const missing = [...usedKeys].filter((key) => lookupPath(defaultLangTree, key) === undefined);

  if (missing.length > 0) {
    for (const key of missing) {
      fail('lang/zh_CN.yml', `key "${key}" is used in the code but missing from the default language`);
    }
  } else {
    pass(`all ${usedKeys.size} message keys used in the code resolve in zh_CN.yml`);
  }

  // Dead keys are not fatal, but they usually mean a feature was never wired up.
  const declared = flattenKeys(defaultLangTree);
  const unused = [...declared].filter((key) => !usedKeys.has(key));

  if (unused.length > 0) {
    warn('lang/zh_CN.yml', `${unused.length} key(s) are never referenced by the code: ${unused.join(', ')}`);
  } else {
    pass('every declared key is referenced by the code');
  }
}

// Every language must define exactly the same key set as the default, so a
// translation can never silently fall back mid-message.
if (defaultLangTree) {
  const reference = flattenKeys(defaultLangTree);

  for (const [language, tree] of pluginLangs) {
    if (language === 'zh_CN') continue;

    const keys = flattenKeys(tree);
    const absent = [...reference].filter((key) => !keys.has(key));
    const extra = [...keys].filter((key) => !reference.has(key));

    if (absent.length > 0 || extra.length > 0) {
      fail(
        `lang/${language}.yml`,
        `key set differs from zh_CN.yml - missing: [${absent.join(', ')}] extra: [${extra.join(', ')}]`
      );
    } else {
      pass(`lang/${language}.yml defines the same ${keys.size} keys as zh_CN.yml`);
    }
  }
}

// ---------------------------------------------------------------------------
// 10. HMAC canonical string agreement
// ---------------------------------------------------------------------------

section('10. PHP/Java canonical string agreement');

function componentOrder(source, startMarker, names) {
  const start = source.indexOf(startMarker);
  if (start < 0) return null;
  const body = source.slice(start, start + 1200);
  return names
    .map((name) => ({ name, index: body.toLowerCase().indexOf(name.toLowerCase()) }))
    .map((entry) => entry)
    .sort((a, b) => a.index - b.index)
    .map((entry) => entry.name);
}

const phpCrypto = read(join(EXT, 'src/Service/BridgeCrypto.php'));
const javaSignature = read(join(javaRoot, 'cn/stalir/mcbridge/Signature.java'));

const expectedOrder = ['timestamp', 'nonce', 'method', 'path', 'body'];
const phpOrder = componentOrder(phpCrypto, 'public static function canonicalString', expectedOrder);
const javaOrder = componentOrder(javaSignature, 'public static String canonicalString', expectedOrder);

if (JSON.stringify(phpOrder) !== JSON.stringify(expectedOrder)) {
  fail('canonical string (PHP)', `component order is ${JSON.stringify(phpOrder)}, expected ${JSON.stringify(expectedOrder)}`);
} else {
  pass(`PHP order ${phpOrder.join(' -> ')}`);
}

if (JSON.stringify(javaOrder) !== JSON.stringify(expectedOrder)) {
  fail('canonical string (Java)', `component order is ${JSON.stringify(javaOrder)}, expected ${JSON.stringify(expectedOrder)}`);
} else {
  pass(`Java order ${javaOrder.join(' -> ')}`);
}

if (!/hash_hmac\('sha256'/.test(phpCrypto)) {
  fail('HMAC (PHP)', "BridgeCrypto must use hash_hmac('sha256', ...)");
} else {
  pass('PHP uses hash_hmac sha256');
}

if (!/"HmacSHA256"/.test(javaSignature)) {
  fail('HMAC (Java)', 'Signature must use Mac "HmacSHA256"');
} else {
  pass('Java uses Mac HmacSHA256');
}

// Both sides must normalise the signed path identically: the canonical path
// starts at the bridge route prefix. Flarum strips its /api frontend prefix
// before the controller runs, so signing the raw request path never matches.
for (const [label, source] of [['PHP', phpCrypto], ['Java', javaSignature]]) {
  const hasMarker = /PATH_MARKER\s*=\s*['"]\/mc-bridge['"]/.test(source);
  const hasNormalize = /normalizePath\s*\(/.test(source);

  if (!hasMarker) {
    fail(`path normalisation (${label})`, 'must declare PATH_MARKER = \'/mc-bridge\' as the canonical path anchor');
  } else if (!hasNormalize) {
    fail(`path normalisation (${label})`, 'must implement normalizePath()');
  } else {
    pass(`path normalisation (${label}) anchors on /mc-bridge`);
  }
}

// The Java client must actually apply it before signing.
{
  const client = read(join(javaRoot, 'cn/stalir/mcbridge/HttpBridgeClient.java'));

  if (!/Signature\.normalizePath\(/.test(client)) {
    fail('HttpBridgeClient', 'must sign Signature.normalizePath(path), not the raw request path');
  } else {
    pass('HttpBridgeClient signs the normalised path');
  }
}

if (!phpCrypto.includes('hash_equals')) {
  fail('HMAC (PHP)', 'signature comparison must be constant-time (hash_equals)');
} else {
  pass('PHP compares signatures with hash_equals');
}

if (!javaSignature.includes('HexFormat')) {
  fail('HMAC (Java)', 'signature must be lowercase hex encoded');
} else {
  pass('Java encodes the signature as hex');
}

for (const [label, source] of [['PHP', phpCrypto], ['Java', javaSignature]]) {
  if (!/X-MC-Timestamp/.test(source) || !/X-MC-Nonce/.test(source) || !/X-MC-Signature/.test(source)) {
    fail(`headers (${label})`, 'the three X-MC-* headers must be defined');
  } else {
    pass(`header names (${label})`);
  }
}

// ---------------------------------------------------------------------------
// 11. Migration tables vs models
// ---------------------------------------------------------------------------

section('11. migration tables vs models');

// Every migration file is read, not just the first one. The incremental
// migration is the only thing that runs on an already-installed forum, so a
// defect there is invisible to a check that stops at the create migration.
const migrationFiles = walk(join(EXT, 'migrations'), (file) => file.endsWith('.php')).sort();
const migrationSource = migrationFiles.map((file) => read(file)).join('\n');
const migrationTables = [...migrationSource.matchAll(/create\('([a-z_]+)'/g)].map((m) => m[1]);

const modelTables = [];
for (const file of phpSources.filter((f) => rel(f).includes('/Model/'))) {
  const source = read(file);
  const table = source.match(/protected\s+\$table\s*=\s*'([a-z_]+)'/);
  if (!table) {
    fail(`model ${rel(file)}`, 'no $table property');
    continue;
  }
  modelTables.push({ table: table[1], file });
}

for (const { table, file } of modelTables) {
  if (migrationTables.includes(table)) {
    pass(`table ${table} created by migration (${basename(file)})`);
  } else {
    fail('migrations', `model ${basename(file)} uses table "${table}" which the migration does not create`);
  }
}

for (const table of migrationTables) {
  if (!modelTables.some((model) => model.table === table)) {
    warn('models', `table ${table} is created but has no model`);
  }
}

// A table created by more than one migration must be defined identically in all
// of them. A fresh install runs the create migration only; an existing install
// runs the incremental one only. If the two copies drift, those two populations
// end up with different schemas and only one of them matches what was tested.
const columnsByMigration = new Map(migrationFiles.map((file) => [file, createBlocks(read(file))]));
const tableOwners = new Map();

for (const [file, tables] of columnsByMigration) {
  for (const table of tables.keys()) {
    if (!tableOwners.has(table)) tableOwners.set(table, []);
    tableOwners.get(table).push(file);
  }
}

for (const [table, owners] of tableOwners) {
  if (owners.length < 2) continue;

  const reference = columnsByMigration.get(owners[0]).get(table);
  const drifted = owners.slice(1).filter((file) => {
    const other = columnsByMigration.get(file).get(table);
    return other.length !== reference.length || other.some((column, index) => column !== reference[index]);
  });

  if (drifted.length > 0) {
    fail(
      'migration drift',
      `table ${table} is created by ${owners.length} migrations but the columns differ in ` +
        `${drifted.map((file) => basename(file)).join(', ')} - fresh installs and upgraded ` +
        'installs would end up with different schemas'
    );
  } else {
    pass(`table ${table} is defined identically by every migration that creates it`);
  }
}

// Mass assignment: every column a model lists in $fillable must exist in the
// migration, otherwise fill() silently drops it. The controllers use
// firstOrNew()/fill(), so a missing entry is a runtime MassAssignmentException
// ("Add [x] to fillable property"), which is exactly what the live selftest
// surfaced.
const columnsByTable = new Map();

// Columns a later migration adds to a table it does not create count too: the
// model is right to fill them, and only reading create(...) blocks would report
// them as a defect.
const alteredColumns = new Map();

for (const file of migrationFiles) {
  for (const [table, columns] of alterBlocks(read(file))) {
    alteredColumns.set(table, [...(alteredColumns.get(table) ?? []), ...columns]);
  }
}

for (const table of migrationTables) {
  const start = migrationSource.indexOf(`create('${table}'`);
  if (start < 0) continue;
  const end = migrationSource.indexOf('hasTable', start + 1);
  const block = migrationSource.slice(start, end > start ? end : undefined);

  columnsByTable.set(
    table,
    [
      ...block.matchAll(/\$table->\w+\('([a-z_]+)'/g),
    ].map((m) => m[1]).concat(alteredColumns.get(table) ?? [])
  );
}

let fillableIssues = 0;

for (const { table, file } of modelTables) {
  const source = read(file);
  const match = source.match(/protected\s+\$fillable\s*=\s*\[([^\]]*)\]/);
  const declared = match ? [...match[1].matchAll(/'([a-z_]+)'/g)].map((m) => m[1]) : [];
  const columns = columnsByTable.get(table) ?? [];

  if (declared.length === 0) {
    fail(
      `model ${basename(file)}`,
      `declares no $fillable although the controllers use fill()/firstOrNew() on ${table}`
    );
    fillableIssues++;
    continue;
  }

  const unknown = declared.filter((column) => !columns.includes(column));

  if (unknown.length > 0) {
    fail(`model ${basename(file)}`, `fillable columns missing from the ${table} migration: ${unknown.join(', ')}`);
    fillableIssues++;
    continue;
  }

  pass(`model ${basename(file)}: ${declared.length} $fillable columns match the ${table} migration`);
}

if (fillableIssues === 0 && modelTables.length > 0) {
  pass('every model declares $fillable columns that exist in the migration');
}

// ---------------------------------------------------------------------------
// 12. Route coverage in the docs
// ---------------------------------------------------------------------------

section('12. documented routes');

const extendSource = read(join(EXT, 'extend.php'));
const routes = [...extendSource.matchAll(/->(get|post|delete)\('(\/mc-bridge\/[a-z/]+)'/g)].map((m) => ({
  method: m[1].toUpperCase(),
  path: `/api${m[2]}`,
}));

if (routes.length === 0) {
  fail('extend.php', 'no routes registered');
}

const apiDocs = read(join(ROOT, 'docs/API.md'));
const protocolDocs = read(join(ROOT, 'protocol/README.md'));

for (const route of routes) {
  const documented = apiDocs.includes(route.path);

  if (documented) {
    pass(`${route.method} ${route.path} documented`);
  } else {
    fail('docs/API.md', `${route.method} ${route.path} is registered but not documented`);
  }

  if (!protocolDocs.includes(route.path)) {
    warn('protocol/README.md', `${route.path} is not listed in the endpoint summary`);
  }
}

// ---------------------------------------------------------------------------
// 13. Flarum 2.x framework contracts (regression guards)
// ---------------------------------------------------------------------------

section('13. Flarum 2.x framework contracts');

{
  // Flarum 2.x migrations must return ['up' => fn(Builder $schema), 'down' => ...].
  // 1.x style ($this->schema) silently yields null and fatals at migrate time.
  // Every migration file is checked - see section 11 for why the first one is
  // not enough.
  const migration = migrationSource;

  for (const file of migrationFiles) {
    const source = read(file);
    const name = basename(file);
    let issues = 0;

    if (!/'up'\s*=>/.test(source) || !/'down'\s*=>/.test(source)) {
      fail(`migration ${name}`, "must return an array with 'up' and 'down' keys (Flarum 2.x)");
      issues++;
    }

    if (/\$this->schema/.test(source)) {
      fail(`migration ${name}`, 'uses $this->schema, which does not exist on Flarum 2.x migrations');
      issues++;
    }

    if (/extends\s+Migration\b/.test(source)) {
      fail(`migration ${name}`, 'must not extend Flarum\\Database\\Migration in Flarum 2.x');
      issues++;
    }

    if (!/function\s*\(\s*Builder\s+\$schema\s*\)/.test(source)) {
      warn(`migration ${name}`, 'expected the closures to receive an Illuminate\\Database\\Schema\\Builder');
    }

    // A PHP closure does NOT inherit the enclosing scope. A nested Blueprint
    // callback that reads $schema without `use ($schema)` compiles, passes every
    // other check here, and then dies at migrate time with
    //
    //     Call to a member function hasColumn() on null
    //
    // 0.0.12 shipped exactly that and broke `php flarum migrate` on every
    // existing install: the column checks sat inside the Blueprint callback
    // while `$schema` only existed in the outer closure.
    for (const closure of phpClosures(source)) {
      if (!/\$schema\b/.test(closure.body)) continue;
      if (/\$schema\b/.test(closure.params)) continue;
      if (/\$schema\b/.test(closure.useClause)) continue;

      fail(
        `migration ${name}`,
        'a nested closure reads $schema without capturing it; PHP closures do not inherit the ' +
          'enclosing scope, so it is null at runtime. Add "use ($schema)". Body starts: ' +
          `"${closure.body.replace(/\s+/g, ' ').trim().slice(0, 90)}"`
      );
      issues++;
    }

    if (issues === 0) {
      pass(`migration ${name} satisfies the Flarum 2.x migration contract`);
    }
  }

  // Flarum's AbstractModel sets $timestamps = false, so any model whose table has
  // timestamps() must opt back in explicitly.
  const tablesWithTimestamps = [...migration.matchAll(/create\('([a-z_]+)'/g)]
    .map((match) => match[1])
    .filter((table) => new RegExp(`create\\('${table}'[\\s\\S]*?timestamps\\(\\)`).test(migration));

  for (const { table, file } of modelTables) {
    if (!tablesWithTimestamps.includes(table)) continue;

    if (/\$timestamps\s*=\s*true/.test(read(file))) {
      pass(`model ${basename(file)} enables timestamps for ${table}`);
    } else {
      fail(
        `model ${basename(file)}`,
        `table ${table} has created_at/updated_at but the model does not set $timestamps = true ` +
        '(Flarum\\Database\\AbstractModel disables timestamps by default)'
      );
    }
  }

  // Flarum runs CheckCsrfToken across the api stack and exempts routes BY NAME
  // through the official Extend\Csrf extender. Inserting a middleware before
  // CheckCsrfToken does NOT work reliably: flarum.api.handler is a singleton
  // that builds the pipeline once, so an extender applied afterwards is
  // silently ignored - which showed up as 400 csrf_token_mismatch on the live
  // forum.
  const extendFile = read(join(EXT, 'extend.php'));
  const exempted = [...extendFile.matchAll(/exemptRoute\(\s*'([^']+)'\s*\)/g)].map((m) => m[1]);
  const definedRoutes = [...extendFile.matchAll(/->(?:get|post|delete)\(\s*'[^']*',\s*'([^']+)'/g)].map(
    (m) => m[1]
  );

  if (!/new\s+Extend\\Csrf\(\)/.test(extendFile)) {
    fail('CSRF', 'no Extend\\Csrf() extender; signed machine POSTs are rejected with 400 csrf_token_mismatch');
  } else {
    pass('extend.php registers Extend\\Csrf() exemptions');
  }

  const MACHINE_ROUTES = [
    'mc-bridge.outbox',
    'mc-bridge.announcements',
    'mc-bridge.bind.start',
    'mc-bridge.bind.status',
    'mc-bridge.broadcast',
    'mc-bridge.report',
  ];

  const SESSION_ROUTES = ['mc-bridge.link', 'mc-bridge.unlink'];

  for (const route of MACHINE_ROUTES) {
    if (!definedRoutes.includes(route)) {
      fail('CSRF', `route ${route} is not defined in extend.php`);
    } else if (exempted.includes(route)) {
      pass(`machine route ${route} is CSRF-exempt`);
    } else {
      fail(
        'CSRF',
        `machine route ${route} must be exempted with Extend\\Csrf()->exemptRoute(); ` +
        'without it every signed call gets 400 csrf_token_mismatch'
      );
    }
  }

  for (const route of SESSION_ROUTES) {
    if (!definedRoutes.includes(route)) {
      fail('CSRF', `route ${route} is not defined in extend.php`);
    } else if (exempted.includes(route)) {
      fail(
        'CSRF',
        `session route ${route} must NOT be CSRF-exempt: it acts on the logged-in user's account`
      );
    } else {
      pass(`session route ${route} keeps its CSRF protection`);
    }
  }

  // Broadcast is exempt so signed machine calls work, which means the session
  // path has to verify the token itself.
  {
    const broadcast = read(join(EXT, 'src/Api/Controller/BroadcastController.php'));
    const enforced = broadcast.includes('X-CSRF-Token') && broadcast.includes('hash_equals');

    if (exempted.includes('mc-bridge.broadcast') && !enforced) {
      fail(
        'BroadcastController',
        'mc-bridge.broadcast is CSRF-exempt, so its session path must verify X-CSRF-Token itself'
      );
    } else if (!exempted.includes('mc-bridge.broadcast')) {
      fail('CSRF', 'mc-bridge.broadcast must be exempt, otherwise the plugin cannot send broadcasts');
    } else {
      pass('BroadcastController re-enforces CSRF for the session path');
    }
  }

  if (existsSync(join(EXT, 'src/Http/Middleware/BridgeCsrfBypassMiddleware.php'))) {
    warn(
      'CSRF',
      'BridgeCsrfBypassMiddleware still exists although it is no longer registered; ' +
      'the middleware approach does not work here, so remove the dead code'
    );
  }

  // Flarum's AbstractCommand extends Symfony's Command, NOT Illuminate's. It
  // provides only info(), comment(), error(), hasOption() plus $this->input and
  // $this->output. Illuminate helpers such as option(), argument() or line()
  // therefore fatal at runtime ("Call to undefined method"), so every command
  // must go through AbstractBridgeCommand, which defines those three shims.
  const consoleDir = join(EXT, 'src/Console');
  const consoleFiles = walk(consoleDir, (f) => f.endsWith('.php'));
  const shimName = 'AbstractBridgeCommand.php';
  const shimFile = join(consoleDir, shimName);

  if (!existsSync(shimFile)) {
    fail('console', `${shimName} is missing; Flarum commands must not use the Illuminate console helpers directly`);
  } else {
    const shim = read(shimFile);

    for (const helper of ['option', 'argument', 'line']) {
      if (new RegExp(`function\\s+${helper}\\s*\\(`).test(shim)) {
        pass(`AbstractBridgeCommand provides ${helper}()`);
      } else {
        fail(shimName, `does not define the ${helper}() shim`);
      }
    }
  }

  // Helpers that exist on Illuminate\Console\Command but not on Flarum's.
  const ILLUMINATE_ONLY = [
    'table', 'ask', 'confirm', 'anticipate', 'choice', 'secret', 'newLine',
    'warn', 'alert', 'call', 'callSilent', 'askWithCompletion',
  ];

  for (const file of consoleFiles) {
    const name = basename(file);

    if (name === shimName) {
      continue;
    }

    const source = read(file);
    const isAbstract = /abstract\s+class/.test(source);

    if (/extends\s+Command\b/.test(source)) {
      fail(`console ${name}`, 'must not extend Symfony\\Component\\Console\\Command directly');
      continue;
    }

    if (!/extends\s+AbstractBridgeCommand\b/.test(source)) {
      fail(`console ${name}`, 'must extend AbstractBridgeCommand');
      continue;
    }

    if (isAbstract) {
      continue;
    }

    if (!/protected\s+function\s+fire\s*\(\s*\)/.test(source)) {
      fail(`console ${name}`, 'Flarum\\Console\\AbstractCommand requires a protected function fire(): int');
      continue;
    }

    const misused = ILLUMINATE_ONLY.filter((helper) => new RegExp(`\\$this->${helper}\\s*\\(`).test(source));

    if (misused.length > 0) {
      fail(
        `console ${name}`,
        `uses Illuminate-only helper(s) that do not exist on Flarum's AbstractCommand: ${misused.join(', ')}`
      );
      continue;
    }

    pass(`console ${name} extends AbstractBridgeCommand and implements fire()`);
  }

  // ------------------------------------------------------------------
  // Translations. Flarum does NOT discover an extension's locale files on
  // its own: extend.php has to register the directory, otherwise every
  // translated string silently renders as its raw key.
  // ------------------------------------------------------------------
  const localeDir = join(EXT, 'locale');
  const flarumLocales = existsSync(localeDir)
    ? walk(localeDir, (file) => file.endsWith('.yml')).map((file) => ({
        name: basename(file, '.yml'),
        tree: parseYaml(read(file)),
      }))
    : [];

  if (flarumLocales.length === 0) {
    fail('locales', 'flarum-extension/locale/ contains no .yml files');
  } else {
    pass(`${flarumLocales.length} Flarum locale file(s): ${flarumLocales.map((l) => l.name).join(', ')}`);
  }

  if (!/new\s+Extend\\Locales\(/.test(extendFile)) {
    fail(
      'locales',
      "extend.php must register the locale directory: new Extend\\Locales(__DIR__.'/locale'), " +
      'otherwise no translation is ever loaded'
    );
  } else {
    pass('extend.php registers the locale directory with Extend\\Locales');
  }

  const flarumDefault = flarumLocales.find((locale) => locale.name === 'zh-Hans');

  if (!flarumDefault) {
    fail('locales', 'the default locale locale/zh-Hans.yml is missing');
  } else {
    pass('default locale locale/zh-Hans.yml present');
  }

  {
    const source = read(join(EXT, 'src/Service/BridgeMessages.php'));
    const declared = source.match(/DEFAULT_LOCALE\s*=\s*'([^']+)'/);

    if (!declared) {
      fail('BridgeMessages.php', 'DEFAULT_LOCALE is not declared');
    } else if (!flarumLocales.some((locale) => locale.name === declared[1])) {
      fail('BridgeMessages.php', `DEFAULT_LOCALE "${declared[1]}" has no locale/${declared[1]}.yml`);
    } else {
      pass(`BridgeMessages.DEFAULT_LOCALE = ${declared[1]}`);
    }
  }

  if (flarumDefault) {
    const reference = flattenKeys(flarumDefault.tree);

    for (const locale of flarumLocales) {
      if (locale.name === flarumDefault.name) continue;

      const keys = flattenKeys(locale.tree);
      const absent = [...reference].filter((key) => !keys.has(key));
      const extra = [...keys].filter((key) => !reference.has(key));

      if (absent.length > 0 || extra.length > 0) {
        fail(
          `locale/${locale.name}.yml`,
          `key set differs from zh-Hans.yml - missing: [${absent.slice(0, 6).join(', ')}] extra: [${extra.slice(0, 6).join(', ')}]`
        );
      } else {
        pass(`locale/${locale.name}.yml defines the same ${keys.size} keys as zh-Hans.yml`);
      }
    }
  }

  // ------------------------------------------------------------------
  // ICU placeholders. LocaleManager::addTranslations registers every locale
  // file under the "messages+intl-icu" domain, so Flarum renders translations
  // with ICU MessageFormat and placeholders must be {name}. Symfony's %name%
  // style is silently NOT substituted - it surfaced as a literal
  // "security.secret: %secret%" in real command output.
  // ------------------------------------------------------------------
  for (const locale of flarumLocales) {
    const source = read(join(localeDir, `${locale.name}.yml`));

    // Comments are not translated, so they must not influence these checks.
    const body = source
      .split('\n')
      .filter((line) => !/^\s*#/.test(line))
      .join('\n');

    const percent = [...new Set([...body.matchAll(/%[a-z_]+%/g)].map((m) => m[0]))];

    if (percent.length > 0) {
      fail(
        `locale/${locale.name}.yml`,
        `uses Symfony-style placeholders (${percent.join(', ')}); ` +
        'Flarum renders translations with ICU MessageFormat, so write {name} instead'
      );
    } else {
      pass(`locale/${locale.name}.yml uses ICU {placeholder} syntax`);
    }

    const opened = (body.match(/\{/g) ?? []).length;
    const closed = (body.match(/\}/g) ?? []).length;
    const braces = [...body.matchAll(/\{[^}]*\}/g)].map((m) => m[0]);
    const malformed = [...new Set(braces.filter((brace) => !/^\{[a-z_]+\}$/.test(brace)))];

    if (opened !== closed) {
      fail(`locale/${locale.name}.yml`, `unbalanced braces: ${opened} "{" vs ${closed} "}" (ICU throws on this)`);
    } else if (malformed.length > 0) {
      fail(
        `locale/${locale.name}.yml`,
        `malformed placeholders, ICU expects {lower_snake_case}: ${malformed.join(', ')}`
      );
    } else {
      pass(`locale/${locale.name}.yml has ${braces.length} well-formed {placeholder}(s)`);
    }
  }

  // The call sites must pass bare placeholder names to match.
  {
    let offenders = 0;

    for (const file of walk(EXT, (f) => f.endsWith('.php'))) {
      const bad = [
        ...new Set([...read(file).matchAll(/'%[a-z_]+%'\s*=>/g)].map((m) => m[0].replace(/\s*=>$/, ''))),
      ];

      if (bad.length > 0) {
        fail(rel(file), `passes Symfony-style placeholder keys: ${bad.join(', ')}`);
        offenders++;
      }
    }

    if (offenders === 0) {
      pass('no PHP call site passes %name% placeholder keys');
    }
  }

  // ------------------------------------------------------------------
  // Nonce replay protection. The nonce guard calls cache->add(), which exists
  // on the concrete Illuminate\Cache\Repository but NOT on the
  // Illuminate\Contracts\Cache\Repository contract, so the contract must not be
  // injected where add() is used.
  // ------------------------------------------------------------------
  {
    const bridgeController = read(join(EXT, 'src/Api/Controller/AbstractBridgeController.php'));

    if (/add\(\s*\$cacheKey/.test(bridgeController) && /use Illuminate\\Contracts\\Cache\\Repository/.test(bridgeController)) {
      fail(
        'AbstractBridgeController',
        'injects Illuminate\\Contracts\\Cache\\Repository but calls add() on it; ' +
        'add() only exists on the concrete Illuminate\\Cache\\Repository'
      );
    } else {
      pass('nonce replay guard uses a repository that has add()');
    }
  }

  // ------------------------------------------------------------------
  // Installability. Flarum 2.x discovers extensions only through Composer's
  // installed.json, so the monorepo root has to expose the sub-directory via
  // extra.flarum-subextensions, and the ROOT autoload must cover the
  // sub-extension's namespace: Composer ignores a sub-package autoload section
  // and Flarum does not register namespaces itself.
  // ------------------------------------------------------------------
  const rootComposerFile = join(ROOT, 'composer.json');

  if (!existsSync(rootComposerFile)) {
    fail(
      'composer.json',
      'the repository root needs a composer.json with extra.flarum-subextensions, ' +
      'otherwise Composer installs the repo but Flarum never sees an extension'
    );
  } else {
    let rootComposer = null;

    try {
      rootComposer = JSON.parse(read(rootComposerFile));
    } catch (exception) {
      fail('composer.json', `invalid JSON: ${exception.message}`);
    }

    if (rootComposer) {
      if (rootComposer.type === 'flarum-extension') {
        fail('composer.json', 'the monorepo root must not be type flarum-extension; the extension lives in flarum-extension/');
      } else {
        pass(`root composer.json type = ${rootComposer.type}`);
      }

      const subextensions = rootComposer.extra?.['flarum-subextensions'];

      if (!Array.isArray(subextensions) || !subextensions.includes('flarum-extension')) {
        fail('composer.json', 'extra.flarum-subextensions must list "flarum-extension"');
      } else {
        pass('composer.json declares extra.flarum-subextensions = flarum-extension');

        const subPath = join(ROOT, subextensions[0]);

        if (!existsSync(join(subPath, 'composer.json')) || !existsSync(join(subPath, 'extend.php'))) {
          fail('composer.json', 'the declared sub-extension path must contain both composer.json and extend.php');
        } else {
          pass('sub-extension path contains composer.json and extend.php');
        }
      }

      let subNamespaces = [];

      try {
        const subComposer = JSON.parse(read(join(EXT, 'composer.json')));
        subNamespaces = Object.keys(subComposer.autoload?.['psr-4'] ?? {});
      } catch (exception) {
        fail('flarum-extension/composer.json', exception.message);
      }

      const rootNamespaces = Object.keys(rootComposer.autoload?.['psr-4'] ?? {});
      const unmapped = subNamespaces.filter((namespace) => !rootNamespaces.includes(namespace));

      if (unmapped.length > 0) {
        fail(
          'composer.json',
          `root autoload is missing PSR-4 mappings for: ${unmapped.join(', ')} ` +
          '(Composer does not process a sub-package autoload section)'
        );
      } else {
        pass(`root autoload covers ${subNamespaces.length} sub-extension namespace(s)`);
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 14. CI workflow sanity (catches path typos before the first push)
// ---------------------------------------------------------------------------

section('14. CI workflow sanity');

{
  const workflowFile = join(ROOT, '.github/workflows/ci.yml');

  if (!existsSync(workflowFile)) {
    fail('CI', '.github/workflows/ci.yml is missing');
  } else {
    const workflow = read(workflowFile);

    // Every working-directory must exist relative to the repository root.
    const workingDirs = [...workflow.matchAll(/working-directory:\s*(\S+)/g)].map((match) => match[1]);

    if (workingDirs.length === 0) {
      warn('CI', 'no working-directory declarations found');
    }

    for (const dir of new Set(workingDirs)) {
      if (existsSync(join(ROOT, dir))) {
        pass(`CI working-directory ${dir} exists`);
      } else {
        fail('CI', `working-directory ${dir} does not exist`);
      }
    }

    // Every node script invoked by the workflow must exist.
    const scripts = [...workflow.matchAll(/node\s+(tools\/[\w.-]+\.mjs)/g)].map((match) => match[1]);

    if (scripts.length === 0) {
      fail('CI', 'the workflow never runs a tools/*.mjs suite');
    }

    for (const script of new Set(scripts)) {
      if (existsSync(join(ROOT, script))) {
        pass(`CI runs ${script}`);
      } else {
        fail('CI', `the workflow runs ${script}, which does not exist`);
      }
    }

    // The three expected jobs must be present.
    for (const job of ['static-and-protocol', 'php', 'java']) {
      if (new RegExp(`^\\s{2}${job}:`, 'm').test(workflow)) {
        pass(`CI job ${job} declared`);
      } else {
        fail('CI', `job ${job} is not declared`);
      }
    }

    // The uploaded artifact must come from where Gradle actually writes jars.
    if (!/path:\s*mc-plugin\/build\/libs\/\*\.jar/.test(workflow)) {
      fail('CI', 'the artifact path should be mc-plugin/build/libs/*.jar (Gradle default output)');
    } else {
      pass('CI uploads mc-plugin/build/libs/*.jar');
    }

    // A missing PHP lint step would let syntax errors reach production.
    if (!/php -l/.test(workflow)) {
      fail('CI', 'the workflow does not run php -l');
    } else {
      pass('CI lints PHP');
    }

    // The build step must fail loudly when gradle cannot be found.
    if (!/command -v gradle/.test(workflow)) {
      warn('CI', 'no fallback when gradle is absent from PATH');
    } else {
      pass('CI has a Gradle bootstrap fallback');
    }
  }
}

// ---------------------------------------------------------------------------
// 15. Java compile hazards that only a real javac would otherwise catch
// ---------------------------------------------------------------------------

section('15. Java compile hazards');

{
  // Simple name -> fully qualified name, for the JDK / third-party types this
  // project uses. A file that mentions the simple name must import it.
  const KNOWN_TYPES = {
    Duration: 'java.time.Duration',
    Instant: 'java.time.Instant',
    UUID: 'java.util.UUID',
    List: 'java.util.List',
    ArrayList: 'java.util.ArrayList',
    Map: 'java.util.Map',
    LinkedHashMap: 'java.util.LinkedHashMap',
    Collections: 'java.util.Collections',
    Set: 'java.util.Set',
    AtomicLong: 'java.util.concurrent.atomic.AtomicLong',
    Pattern: 'java.util.regex.Pattern',
    PatternSyntaxException: 'java.util.regex.PatternSyntaxException',
    Logger: 'java.util.logging.Logger',
    Level: 'java.util.logging.Level',
    IOException: 'java.io.IOException',
    URI: 'java.net.URI',
    URLEncoder: 'java.net.URLEncoder',
    StandardCharsets: 'java.nio.charset.StandardCharsets',
    HexFormat: 'java.util.HexFormat',
    Mac: 'javax.crypto.Mac',
    SecretKeySpec: 'javax.crypto.spec.SecretKeySpec',
    GeneralSecurityException: 'java.security.GeneralSecurityException',
    Locale: 'java.util.Locale',
    FileConfiguration: 'org.bukkit.configuration.file.FileConfiguration',
    JavaPlugin: 'org.bukkit.plugin.java.JavaPlugin',
    BukkitTask: 'org.bukkit.scheduler.BukkitTask',
    Player: 'org.bukkit.entity.Player',
    Listener: 'org.bukkit.event.Listener',
    EventHandler: 'org.bukkit.event.EventHandler',
    EventPriority: 'org.bukkit.event.EventPriority',
    PlayerDeathEvent: 'org.bukkit.event.entity.PlayerDeathEvent',
    PlayerJoinEvent: 'org.bukkit.event.player.PlayerJoinEvent',
    PlayerQuitEvent: 'org.bukkit.event.player.PlayerQuitEvent',
    PlayerAdvancementDoneEvent: 'org.bukkit.event.player.PlayerAdvancementDoneEvent',
    Command: 'org.bukkit.command.Command',
    CommandSender: 'org.bukkit.command.CommandSender',
    CommandExecutor: 'org.bukkit.command.CommandExecutor',
    TabCompleter: 'org.bukkit.command.TabCompleter',
    Component: 'net.kyori.adventure.text.Component',
    LegacyComponentSerializer: 'net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer',
    PlainTextComponentSerializer: 'net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer',
    ConfigurationSection: 'org.bukkit.configuration.ConfigurationSection',
    YamlConfiguration: 'org.bukkit.configuration.file.YamlConfiguration',
    JsonObject: 'com.google.gson.JsonObject',
    JsonArray: 'com.google.gson.JsonArray',
    JsonElement: 'com.google.gson.JsonElement',
    JsonParser: 'com.google.gson.JsonParser',
    JsonSyntaxException: 'com.google.gson.JsonSyntaxException',
    Gson: 'com.google.gson.Gson',
    HttpClient: 'java.net.http.HttpClient',
    HttpRequest: 'java.net.http.HttpRequest',
    HttpResponse: 'java.net.http.HttpResponse',
  };

  // A few simple names mean different classes on different platforms, because
  // each module is compiled against a different API. The NeoForge module uses
  // Minecraft's own Component and SLF4J's Logger, for example, while the paper
  // module uses Adventure's.
  const MODULE_TYPE_OVERRIDES = {
    paper: {
      ScheduledTask: 'io.papermc.paper.threadedregions.scheduler.ScheduledTask',
      TimeUnit: 'java.util.concurrent.TimeUnit',
    },
    neoforge: {
      Component: 'net.minecraft.network.chat.Component',
      Logger: 'org.slf4j.Logger',
      MinecraftServer: 'net.minecraft.server.MinecraftServer',
      ServerPlayer: 'net.minecraft.server.level.ServerPlayer',
      CommandSourceStack: 'net.minecraft.commands.CommandSourceStack',
      Commands: 'net.minecraft.commands.Commands',
      CommandDispatcher: 'com.mojang.brigadier.CommandDispatcher',
      CommandContext: 'com.mojang.brigadier.context.CommandContext',
      CommandSyntaxException: 'com.mojang.brigadier.exceptions.CommandSyntaxException',
      StringArgumentType: 'com.mojang.brigadier.arguments.StringArgumentType',
      IEventBus: 'net.neoforged.bus.api.IEventBus',
      SubscribeEvent: 'net.neoforged.bus.api.SubscribeEvent',
      Mod: 'net.neoforged.fml.common.Mod',
      ModContainer: 'net.neoforged.fml.ModContainer',
      NeoForge: 'net.neoforged.neoforge.common.NeoForge',
      RegisterCommandsEvent: 'net.neoforged.neoforge.event.RegisterCommandsEvent',
      PlayerEvent: 'net.neoforged.neoforge.event.entity.player.PlayerEvent',
      ServerStartedEvent: 'net.neoforged.neoforge.event.server.ServerStartedEvent',
      ServerStoppingEvent: 'net.neoforged.neoforge.event.server.ServerStoppingEvent',
      FMLPaths: 'net.neoforged.fml.loading.FMLPaths',
      LogUtils: 'com.mojang.logging.LogUtils',
      Executors: 'java.util.concurrent.Executors',
      ScheduledExecutorService: 'java.util.concurrent.ScheduledExecutorService',
      ScheduledFuture: 'java.util.concurrent.ScheduledFuture',
      ThreadFactory: 'java.util.concurrent.ThreadFactory',
      TimeUnit: 'java.util.concurrent.TimeUnit',
      AtomicInteger: 'java.util.concurrent.atomic.AtomicInteger',
      InputStream: 'java.io.InputStream',
      Files: 'java.nio.file.Files',
      Path: 'java.nio.file.Path',
      Supplier: 'java.util.function.Supplier',
    },
  };

  let missingImports = 0;

  // Type names inside comments must not count, so strip both comment styles
  // first. (This can over-strip the tail of a line containing "//" inside a
  // string literal, which is acceptable: it only risks a missed warning.)
  const stripJavaComments = (source) => source
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ');

  for (const file of javaFiles) {
    const raw = read(file);
    const source = stripJavaComments(raw);
    const packageMatch = raw.match(/^package\s+([A-Za-z0-9_.]+);/m);
    const filePackage = packageMatch ? packageMatch[1] : '';
    const name = basename(file);
    const module = javaModuleOf.get(file);
    const types = { ...KNOWN_TYPES, ...(MODULE_TYPE_OVERRIDES[module] ?? {}) };

    for (const [simple, qualified] of Object.entries(types)) {
      // Word-boundary match on the simple name.
      if (!new RegExp(`\\b${simple}\\b`).test(source)) continue;

      // Types from the same package need no import.
      if (qualified.startsWith(filePackage + '.')) continue;

      const imported = new RegExp(`^import\\s+${qualified.replace(/\./g, '\\.')};`, 'm').test(raw);

      if (!imported) {
        fail(`Java ${name}`, `uses ${simple} but does not import ${qualified}`);
        missingImports++;
      }
    }
  }

  if (missingImports === 0) {
    pass(`no missing imports across ${javaFiles.length} Java files`);
  }

  // BukkitScheduler exposes both a Runnable and a Consumer<BukkitTask> overload.
  // An implicitly typed method reference is not pertinent to applicability, so
  // passing one directly is ambiguous; it must be cast to Runnable.
  let ambiguousCallSites = 0;

  for (const file of javaFiles) {
    const source = read(file);
    const name = basename(file);
    const callPattern = /runTask[A-Za-z]*\s*\(/g;
    let match;

    while ((match = callPattern.exec(source)) !== null) {
      const end = source.indexOf(';', match.index);
      const call = source.slice(match.index, end === -1 ? match.index + 400 : end);

      if (call.includes('::') && !call.includes('(Runnable)')) {
        fail(
          `Java ${name}`,
          `a method reference passed to a scheduler call must be cast to Runnable, otherwise the ` +
          `Runnable and Consumer<BukkitTask> overloads are ambiguous: ${call.replace(/\s+/g, ' ').slice(0, 90)}...`
        );
        ambiguousCallSites++;
      }
    }
  }

  if (ambiguousCallSites === 0) {
    pass('no ambiguous scheduler method-reference call sites');
  }
}

// ---------------------------------------------------------------------------
// 16. Flarum frontend bundle
// ---------------------------------------------------------------------------

section('16. Flarum frontend bundle');

{
  const JS_DIR = join(EXT, 'js');
  const DIST = join(JS_DIR, 'dist');

  // flarum-webpack-config resolves the entry points at the js root.
  for (const entry of ['forum.js', 'admin.js']) {
    if (existsSync(join(JS_DIR, entry))) {
      pass(`js/${entry} entry point exists`);
    } else {
      fail(`js/${entry}`, 'flarum-webpack-config looks for the entry points at the js root, not under src/');
    }
  }

  // Core loads route pages through dynamic import(), so they live in their own
  // chunk instead of the main bundle. A static import therefore evaluates to
  // undefined when the extension bundle is evaluated, and touching .prototype
  // on it throws while the app boots ("Cannot read properties of undefined").
  // Such modules must be passed to extend()/override() as a module path string,
  // which resolves them lazily through flarum.reg.onLoad().
  const LAZY_CORE_MODULES = [
    'flarum/forum/components/SettingsPage',
    'flarum/forum/components/PostsPage',
    'flarum/forum/components/NotificationsPage',
    'flarum/forum/components/PostStream',
    'flarum/forum/components/PostStreamScrubber',
    'flarum/forum/components/DiscussionsUserPage',
    'flarum/forum/components/UserSecurityPage',
  ];

  const stripJsComments = (source) =>
    source.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/[^\n]*$/gm, ' ');

  const frontendSources = walk(
    JS_DIR,
    (file) =>
      file.endsWith('.js') &&
      !rel(file).includes('/js/dist/') &&
      !rel(file).includes('node_modules')
  );

  let staticLazyImports = 0;

  for (const file of frontendSources) {
    const source = stripJsComments(read(file));

    for (const module of LAZY_CORE_MODULES) {
      const escaped = module.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
      const pattern = new RegExp(`^[ \\t]*import[^\\n;]*from[ \\t]*['"]${escaped}['"]`, 'm');

      if (pattern.test(source)) {
        fail(
          rel(file),
          `statically imports ${module}, but core only loads it as a lazy chunk; ` +
            "reference it as a path string, e.g. extend('" + module + "', ...)"
        );
        staticLazyImports++;
      }
    }
  }

  if (staticLazyImports === 0) {
    pass(`no static import of a lazily chunked core module across ${frontendSources.length} frontend sources`);
  }

  const forumEntry = join(JS_DIR, 'forum.js');

  if (existsSync(forumEntry)) {
    if (/extend\(\s*['"]flarum\/forum\/components\/SettingsPage['"]/.test(read(forumEntry))) {
      pass('js/forum.js extends the settings page through its module path');
    } else {
      fail(
        'js/forum.js',
        "the settings section must be registered as extend('flarum/forum/components/SettingsPage', 'settingsItems', ...)"
      );
    }
  }

  for (const bundle of ['forum.js', 'admin.js']) {
    const file = join(DIST, bundle);

    if (!existsSync(file)) {
      fail(`js/dist/${bundle}`, 'built bundle is missing; run npm run build inside flarum-extension/js');
      continue;
    }

    const built = read(file);

    if (built.length === 0) {
      fail(`js/dist/${bundle}`, 'built bundle is empty');
    } else if (built.includes('SettingsPage.prototype')) {
      fail(`js/dist/${bundle}`, 'accesses .prototype of the lazily chunked SettingsPage while evaluating the bundle');
    } else {
      pass(`js/dist/${bundle} built (${built.length} bytes, no eager SettingsPage.prototype access)`);
    }
  }

  // Flarum 2.x replaced `app.extensionData` with `app.registry` (AdminRegistry)
  // and defines extensionData nowhere, so the legacy entry point is undefined
  // and `.for(...)` throws while the admin app boots.
  let legacyAdminApis = 0;

  for (const file of frontendSources) {
    if (/app\.extensionData/.test(stripJsComments(read(file)))) {
      fail(rel(file), 'uses app.extensionData, which Flarum 2.x removed; use app.registry.for(...) instead');
      legacyAdminApis++;
    }
  }

  if (legacyAdminApis === 0) {
    pass('no use of the removed app.extensionData API');
  }

  const adminEntry = join(JS_DIR, 'admin.js');

  if (existsSync(adminEntry)) {
    if (/app\.registry\s*\.\s*for\(/.test(stripJsComments(read(adminEntry)))) {
      pass('js/admin.js registers its settings through app.registry.for(...)');
    } else {
      fail('js/admin.js', 'settings must be registered as app.registry.for(<extension id>).registerSetting(...)');
    }
  }

  const builtAdmin = join(DIST, 'admin.js');

  if (!existsSync(builtAdmin)) {
    // Already reported by the bundle loop above.
  } else if (read(builtAdmin).includes('extensionData')) {
    fail('js/dist/admin.js', 'the committed bundle still references the removed extensionData API; rebuild it');
  } else {
    pass('js/dist/admin.js has no reference to the removed extensionData API');
  }

  // Flarum 2.x's FieldSet renders its own <label class="FieldSet-label"> from the
  // `label` attribute and is not a <fieldset> element, so a `legend` child lands
  // inside FieldSet-items and the section title loses its styling.
  let legendChildren = 0;

  for (const file of frontendSources) {
    if (/m\(\s*['"`]legend/.test(stripJsComments(read(file)))) {
      fail(rel(file), 'passes a `legend` child to FieldSet; use the `label` attribute instead');
      legendChildren++;
    }
  }

  if (legendChildren === 0) {
    pass('no FieldSet legend children (Flarum 2.x expects the label attribute)');
  }

  // Every endpoint the frontend calls must be registered for the matching HTTP
  // method. A GET that nobody registered returns Flarum's 404 error document,
  // and a frontend that reads a missing field from it renders the "not linked"
  // state instead of failing loudly - which is exactly how a real binding stayed
  // invisible in the settings section.
  {
    const extendSource = read(join(EXT, 'extend.php'));
    const apiRoutes = new Set();

    for (const part of extendSource.split('(new Extend').slice(1)) {
      if (!part.startsWith("\\Routes('api')")) continue;

      const end = part.indexOf('(new Extend');
      const body = end === -1 ? part : part.slice(0, end);

      for (const match of body.matchAll(/->(get|post|delete|patch|put)\(\s*'([^']+)'/g)) {
        apiRoutes.add(`${match[1].toUpperCase()} ${match[2]}`);
      }
    }

    // A translation can only be used as an attribute value through extractText().
    // trans() hands back a vnode (or an array of parts), and String() on that
    // joins the parts with commas, so a two-part message such as
    // "Minecraft account: {name}" reached the DOM as "Minecraft account: ,name" -
    // which is what the badge tooltip displayed. extractText() joins with ''.
    let unwrappedAttributes = 0;

    for (const file of frontendSources) {
      const source = stripJsComments(read(file));

      for (const match of source.matchAll(/(?:'aria-label'|title|placeholder|label|alt)\s*:\s*([^\n]*)/g)) {
        const value = match[1].trim();

        // Check the start of the value rather than a lookahead: an anchored
        // lookahead can be satisfied at the whitespace before extractText().
        if (value.startsWith('extractText')) continue;
        if (!/(?:translator\.trans|this\.t)\(/.test(value)) continue;

        fail(
          rel(file),
          `a translation is passed straight into an attribute (${match[0].split(':')[0].trim()}); ` +
            "wrap it in extractText(), otherwise multi-part messages reach the DOM with commas"
        );
        unwrappedAttributes++;
      }
    }

    if (unwrappedAttributes === 0) {
      pass('every translation used as an attribute value goes through extractText()');
    }

    const calls = new Set();

    for (const file of frontendSources) {
      const source = stripJsComments(read(file));

      // fetch(`${...}/mc-bridge/x`) is a GET unless the init overrides it;
      // this.request('METHOD', '/mc-bridge/x') states the method.
      for (const match of source.matchAll(/fetch\(\s*`[^`]*?(\/mc-bridge\/[a-z/]+)`/g)) {
        calls.add(`GET ${match[1]}`);
      }

      for (const match of source.matchAll(/request\(\s*'([A-Z]+)'\s*,\s*'(\/mc-bridge\/[a-z/]+)'/g)) {
        calls.add(`${match[1]} ${match[2]}`);
      }
    }

    if (calls.size === 0) {
      fail('frontend endpoints', 'no /mc-bridge/* call could be detected in the frontend sources');
    } else {
      const unregistered = [...calls].filter((call) => !apiRoutes.has(call));

      if (unregistered.length > 0) {
        for (const call of unregistered) {
          fail('frontend endpoints', `the frontend calls ${call} but extend.php registers no such API route`);
        }
      } else {
        pass(`all ${calls.size} frontend endpoints are registered as API routes`);
      }
    }
  }

  // Every key the section asks the translator for must exist in every locale.
  const sectionSource = join(JS_DIR, 'src/forum/components/McBridgeSection.js');
  const usedKeys = new Set();

  if (existsSync(sectionSource)) {
    for (const match of read(sectionSource).matchAll(/this\.t\(\s*'([a-z0-9_]+)'/g)) {
      usedKeys.add(match[1]);
    }
  }

  if (usedKeys.size === 0) {
    warn('McBridgeSection.js', 'no this.t(...) keys found, so the frontend locale coverage check was skipped');
  } else {
    for (const file of walk(join(EXT, 'locale'), (candidate) => candidate.endsWith('.yml'))) {
      const keys = flattenKeys(parseYaml(read(file)));
      const absent = [...usedKeys].filter((key) => !keys.has(`stalirmc-mc-bridge.forum.settings.${key}`));

      if (absent.length > 0) {
        fail(rel(file), `missing forum.settings keys used by the settings section: [${absent.join(', ')}]`);
      } else {
        pass(`locale/${basename(file)} covers all ${usedKeys.size} forum.settings keys used by the frontend`);
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 17. The two artifacts: Paper/Folia plugin and NeoForge mod
// ---------------------------------------------------------------------------

section('17. Paper/Folia plugin and NeoForge mod');

{
  // --- version parity -----------------------------------------------------
  const properties = read(join(PLUGIN, 'gradle.properties'));
  const declaredVersion = (properties.match(/^version\s*=\s*(.+)$/m) ?? [])[1]?.trim();
  const versionSource = read(join(javaRoot, 'cn/stalir/mcbridge/Version.java'));
  const versionConstant = (versionSource.match(/VERSION\s*=\s*"([^"]+)"/) ?? [])[1];

  if (!declaredVersion) {
    fail('gradle.properties', 'version is not declared');
  } else if (declaredVersion !== versionConstant) {
    fail(
      'Version.java',
      `VERSION "${versionConstant}" does not match gradle.properties "${declaredVersion}"; plugin.yml, the mod ` +
        'descriptor and the HTTP User-Agent would advertise different versions'
    );
  } else {
    pass(`version ${declaredVersion} is declared in gradle.properties and Version.java`);
  }

  // Flarum shows the version of a flarum-subextension straight from that
  // sub-extension's own composer.json
  // (ExtensionManager::extensionFromJson -> Arr::get($package, 'version', '0.0')),
  // so without this field the admin page displays a hard-coded "0.0".
  const extensionManifest = JSON.parse(read(join(EXT, 'composer.json')));

  if (!declaredVersion) {
    // Already reported above.
  } else if (extensionManifest.version !== declaredVersion) {
    fail(
      'flarum-extension/composer.json',
      `version "${extensionManifest.version}" does not match gradle.properties "${declaredVersion}"; ` +
        "Flarum's admin page shows this field, and an empty one falls back to \"0.0\""
    );
  } else {
    pass(`flarum-extension/composer.json declares version ${declaredVersion}`);
  }

  // The author link on the admin page is built from authors[].homepage, then
  // their email, and otherwise an empty string - which the browser resolves
  // against the current page and lands back on /admin.
  const authors = Array.isArray(extensionManifest.authors) ? extensionManifest.authors : [];
  const linkedAuthors = authors.filter((author) => author?.homepage || author?.email);

  if (authors.length === 0) {
    fail('flarum-extension/composer.json', 'no author is declared');
  } else if (linkedAuthors.length !== authors.length) {
    fail(
      'flarum-extension/composer.json',
      'every author needs a homepage or email, otherwise the admin page renders a link that just reloads /admin'
    );
  } else {
    pass(`${authors.length} author(s) carry a homepage or email for the admin page link`);
  }

  const paperPluginYml = read(join(PLUGIN, 'src/paper/resources/plugin.yml'));

  if (/^version:\s*'\$\{version\}'$/m.test(paperPluginYml)) {
    pass('plugin.yml takes its version from the Gradle project version');
  } else {
    fail('plugin.yml', "version must stay '${version}' so it follows the build");
  }

  // --- build wiring -------------------------------------------------------
  const build = read(join(PLUGIN, 'build.gradle'));
  const modBuild = read(join(PLUGIN, 'neoforge/build.gradle'));
  const settings = read(join(PLUGIN, 'settings.gradle'));
  const propertiesFile = read(join(PLUGIN, 'gradle.properties'));

  const wiring = [
    ['the paper source set compiles src/paper/java', /src\/paper\/java/],
    ['paper-api is a compile-only dependency', /io\.papermc\.paper:paper-api/],
    ['Adventure is a paper-module dependency, not a core one', /paperCompileOnly "net\.kyori:adventure-api/],
    ['the jar merges the paper output', /from sourceSets\.paper\.output/],
    ['the jar contents are asserted before release', /tasks\.register\('verifyJar'\)/],
    ['the shared core targets Java 17 so it loads on the widest range of JVMs', /options\.release\s*=\s*17/],
    [
      'the paper module is raised to Java 21, because paper-api 1.21.1 itself requires it',
      /tasks\.named\('compilePaperJava'\)\s*\{[^}]*options\.release\s*=\s*21/,
    ],
    ['plugin.yml is filtered with the project version', /filesMatching\('plugin\.yml'\)/],
    [
      'the plugin.yml expansion is a declared input, so a version bump rebuilds it',
      /inputs\.property\('version'/,
    ],
  ];

  for (const [label, pattern] of wiring) {
    if (pattern.test(build)) {
      pass(label);
    } else {
      fail('build.gradle', `missing from the plugin wiring: ${label}`);
    }
  }

  // The NeoForge module is a project of its own, built by ModDevGradle, which
  // owns the Minecraft dependency. Keeping it separate is what guarantees
  // Minecraft never reaches the shared core's compile classpath.
  const modWiring = [
    ['the mod project applies ModDevGradle', /id\s+'net\.neoforged\.moddev'/],
    ['the mod project compiles the shared core sources too', /rootProject\.file\('src\/main\/java'\)/],
    ['the mod project carries the shipped config and languages', /rootProject\.file\('src\/main\/resources'\)/],
    ['the NeoForge version comes from gradle.properties', /version\s*=\s*"\$\{neoforgeVersion\}"/],
    ['the mod descriptor is expanded with the project version', /neoforge\.mods\.toml/],
    ['the mod jar contents are asserted before release', /tasks\.register\('verifyJar'\)/],
    ['the mod jar is named distinctly from the plugin jar', /archiveBaseName\s*=\s*'McBridge-neoforge'/],
  ];

  for (const [label, pattern] of modWiring) {
    if (pattern.test(modBuild)) {
      pass(label);
    } else {
      fail('neoforge/build.gradle', `missing from the mod wiring: ${label}`);
    }
  }

  if (/include\s+'neoforge'/.test(settings)) {
    pass('neoforge is a Gradle subproject of the plugin build');
  } else {
    fail('settings.gradle', "the build must include the 'neoforge' project");
  }

  if (/^neoforgeVersion\s*=\s*21\.1\./m.test(propertiesFile)) {
    pass('gradle.properties pins a NeoForge 21.1.x release, the line that targets Minecraft 1.21.1');
  } else {
    fail('gradle.properties', 'neoforgeVersion must be a 21.1.x release to match Minecraft 1.21.1');
  }

  // The Velocity module is gone. Only real configuration counts here - a comment
  // explaining the removal is not a leftover, but a source set, dependency or
  // property pointing at it would be.
  const staleVelocityPattern = /sourceSets\s*\.\s*velocity|velocityCompileOnly|velocityAnnotationProcessor|com\.velocitypowered|velocityApiVersion|include\s+'velocity'/;
  const staleVelocity = [
    ['build.gradle', build],
    ['settings.gradle', settings],
    ['gradle.properties', propertiesFile],
  ].filter(([, source]) => staleVelocityPattern.test(source));

  if (staleVelocity.length === 0) {
    pass('no Velocity source set, dependency or property survives in the build files');
  } else {
    for (const [name] of staleVelocity) {
      fail(name, 'still wires up Velocity; the module was removed and nothing should reference it');
    }
  }

  if (existsSync(join(PLUGIN, 'src/velocity'))) {
    fail('src/velocity', 'the Velocity source set was removed but the directory is still there');
  } else {
    pass('the Velocity source set is gone');
  }

  if (/dependsOn tasks\.named\('verifyJar'\)/.test(build)) {
    pass('build depends on verifyJar, so a broken universal jar cannot be published');
  } else {
    fail('build.gradle', 'the build task must depend on verifyJar');
  }

  // --- platform isolation -------------------------------------------------
  // The shared core must compile and load on a server that ships none of these:
  // Paper supplies Bukkit and Adventure, NeoForge supplies Minecraft and
  // NeoForge, and neither ships all of them.
  const FORBIDDEN_IN_MAIN = [
    ['org.bukkit', 'Bukkit'],
    ['io.papermc', 'Paper'],
    ['net.kyori', 'Adventure'],
    ['net.minecraft', 'Minecraft'],
    ['net.neoforged', 'NeoForge'],
  ];

  const mainSources = walk(javaRoot, (file) => file.endsWith('.java'));
  let mainLeaks = 0;

  for (const file of mainSources) {
    const source = read(file);

    for (const [needle, label] of FORBIDDEN_IN_MAIN) {
      if (new RegExp(`^import\\s+(static\\s+)?${needle.replace(/\./g, '\\.')}`, 'm').test(source)) {
        fail(rel(file), `the shared core must not import ${label} (${needle}): not every platform ships it`);
        mainLeaks++;
      }
    }
  }

  if (mainLeaks === 0) {
    pass(`the shared core stays platform neutral across ${mainSources.length} sources`);
  }

  // Each platform module may use its own API but not the other's. A Bukkit class
  // in the mod, or a Minecraft class in the plugin, would be resolved when the
  // wrong server loads it, and would fail there with NoClassDefFoundError.
  const paperSources = walk(join(PLUGIN, 'src/paper/java'), (file) => file.endsWith('.java'));
  let paperLeaks = 0;

  for (const file of paperSources) {
    const source = read(file);

    for (const [needle, label] of [['net.minecraft', 'Minecraft'], ['net.neoforged', 'NeoForge']]) {
      if (new RegExp(`^import\\s+(static\\s+)?${needle.replace(/\./g, '\\.')}`, 'm').test(source)) {
        fail(rel(file), `the Paper/Folia module must not import ${label} classes`);
        paperLeaks++;
      }
    }
  }

  if (paperLeaks === 0 && paperSources.length > 0) {
    pass(`the Paper/Folia module is free of Minecraft/NeoForge references (${paperSources.length} sources)`);
  }

  const modSources = walk(join(PLUGIN, 'neoforge/src/main/java'), (file) => file.endsWith('.java'));
  let modLeaks = 0;

  for (const file of modSources) {
    const source = read(file);

    for (const [needle, label] of [['org.bukkit', 'Bukkit'], ['io.papermc', 'Paper'], ['net.kyori', 'Adventure']]) {
      if (new RegExp(`^import\\s+(static\\s+)?${needle.replace(/\./g, '\\.')}`, 'm').test(source)) {
        fail(rel(file), `the NeoForge module must not import ${label} classes`);
        modLeaks++;
      }
    }
  }

  if (modLeaks === 0 && modSources.length > 0) {
    pass(`the NeoForge module is free of Bukkit/Paper/Adventure references (${modSources.length} sources)`);
  }

  // --- Folia support ------------------------------------------------------
  const platformPath = join(PLUGIN, 'src/paper/java/cn/stalir/mcbridge/paper/PaperPlatform.java');
  const foliaSignals = [
    ['detects a regionised server', /io\.papermc\.paper\.threadedregions\.RegionizedServer/],
    ['schedules through the Folia AsyncScheduler', /getAsyncScheduler\(\)/],
    ['schedules through the Folia GlobalRegionScheduler', /getGlobalRegionScheduler\(\)/],
    ['keeps the classic BukkitScheduler path for plain Paper', /getScheduler\(\)/],
  ];

  if (!existsSync(platformPath)) {
    fail('PaperPlatform.java', 'the Paper/Folia platform implementation is missing');
  } else {
    const platformSource = read(platformPath);

    for (const [label, pattern] of foliaSignals) {
      if (pattern.test(platformSource)) {
        pass(`PaperPlatform ${label}`);
      } else {
        fail('PaperPlatform.java', `missing Folia support: it must ${label}`);
      }
    }
  }

  // --- NeoForge descriptor -------------------------------------------------
  const modMain = join(PLUGIN, 'neoforge/src/main/java/cn/stalir/mcbridge/neoforge/McBridgeMod.java');
  const modTomlPath = join(PLUGIN, 'neoforge/src/main/resources/META-INF/neoforge.mods.toml');

  if (!existsSync(modMain)) {
    fail('neoforge module', 'McBridgeMod.java is missing');
  } else {
    const source = read(modMain);

    if (/@Mod\(/.test(source) && /MODID\s*=\s*"mc_bridge"/.test(source)) {
      pass('the NeoForge entry point is annotated with @Mod and declares the mod id');
    } else {
      fail('McBridgeMod.java', '@Mod must name the same mod id the descriptor declares');
    }

    for (const [label, pattern] of [
      ['starts the bridge once the server has started', /ServerStartedEvent/],
      ['stops it when the server begins stopping', /ServerStoppingEvent/],
      ['registers the commands', /RegisterCommandsEvent/],
      ['prompts unlinked players when they join', /PlayerLoggedInEvent/],
    ]) {
      if (pattern.test(source)) {
        pass(`McBridgeMod ${label}`);
      } else {
        fail('McBridgeMod.java', `the mod must: ${label}`);
      }
    }
  }

  // The descriptor is what makes the file a mod at all: without a parseable one
  // NeoForge ignores or rejects the whole jar.
  if (!existsSync(modTomlPath)) {
    fail('neoforge.mods.toml', 'the mod descriptor is missing; NeoForge would not load the jar at all');
  } else {
    const toml = read(modTomlPath);

    for (const [label, pattern] of [
      ['declares the javafml loader', /modLoader\s*=\s*"javafml"/],
      ['uses the FML 4 loader range NeoForge 21.1 ships', /loaderVersion\s*=\s*"\[4,\)"/],
      ['declares modId "mc_bridge"', /modId\s*=\s*"mc_bridge"/],
      ['requires NeoForge', /modId\s*=\s*"neoforge"[\s\S]{0,400}?type\s*=\s*"required"/],
      ['requires Minecraft', /modId\s*=\s*"minecraft"[\s\S]{0,400}?type\s*=\s*"required"/],
      ['is server side only, so a client does not need it', /side\s*=\s*"SERVER"/],
    ]) {
      if (pattern.test(toml)) {
        pass(`neoforge.mods.toml ${label}`);
      } else {
        fail('neoforge.mods.toml', `the mod descriptor must: ${label}`);
      }
    }
  }

  for (const required of ['McBridgePlugin.java', 'PaperPlatform.java']) {
    if (existsSync(join(PLUGIN, 'src/paper/java/cn/stalir/mcbridge/paper', required))) {
      pass(`paper module provides ${required}`);
    } else {
      fail('paper module', `${required} is missing`);
    }
  }

  // --- Java 17 compatibility ----------------------------------------------
  // The toolchain is JDK 21 with `options.release = 17` for the shared core, so a
  // Java 21-only API there would compile and then fail on an older JVM. The paper
  // and neoforge modules target 21 on purpose (paper-api and Minecraft 1.21.1
  // both require it) and are therefore exempt.
  const JAVA_21_ONLY = [
    [/\.getFirst\(\)/, 'List#getFirst (Java 21)'],
    [/\.getLast\(\)/, 'List#getLast (Java 21)'],
    [/\.reversed\(\)/, 'List#reversed (Java 21)'],
    [/Thread\.ofVirtual/, 'Thread#ofVirtual (Java 21)'],
    [/Thread\.startVirtualThread/, 'Thread#startVirtualThread (Java 21)'],
    [/Math\.clamp\(/, 'Math#clamp (Java 21)'],
    [/SequencedCollection|SequencedMap|SequencedSet/, 'sequenced collections (Java 21)'],
    [/ScopedValue|StructuredTaskScope/, 'ScopedValue / StructuredTaskScope (Java 21 preview)'],
  ];

  const JAVA_21_MODULES = ['paper', 'neoforge'];
  const java17Files = javaFiles.filter((file) => !JAVA_21_MODULES.includes(javaModuleOf.get(file)));
  let java21Usage = 0;

  for (const file of java17Files) {
    const source = read(file).replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^\s*\/\/[^\n]*$/gm, ' ');

    for (const [pattern, label] of JAVA_21_ONLY) {
      if (pattern.test(source)) {
        fail(rel(file), `uses ${label}, which does not exist on Java 17; this module targets Java 17`);
        java21Usage++;
      }
    }
  }

  if (java21Usage === 0) {
    pass(`no Java 21-only API in the ${java17Files.length} Java 17 sources (paper and neoforge may use them)`);
  }

  // --- release automation --------------------------------------------------
  const release = join(ROOT, '.github/workflows/release.yml');

  if (!existsSync(release)) {
    fail('.github/workflows/release.yml', 'the tag-driven release workflow is missing');
  } else {
    const source = read(release);

    const releaseChecks = [
      ['is triggered by v* tags', /tags:\s*\['v\*'\]/],
      ['may create releases', /contents:\s*write/],
      ['checks the tag against gradle.properties', /does not match version/],
      ['uploads the built jar', /dist\/\*\.jar/],
    ];

    for (const [label, pattern] of releaseChecks) {
      if (pattern.test(source)) {
        pass(`release workflow ${label}`);
      } else {
        fail('release.yml', `the release workflow must ${label}`);
      }
    }
  }

  const ci = read(join(ROOT, '.github/workflows/ci.yml'));

  for (const [label, pattern] of [
    ['asserts folia-supported in the packaged plugin jar', /folia-supported: true/],
    ['asserts the Velocity descriptors are gone from the plugin jar', /still contains Velocity artifacts/],
    ['asserts the NeoForge descriptor in the packaged mod jar', /neoforge\.mods\.toml/],
    ['asserts the mod jar targets Minecraft 1.21.1', /1\\.21\\.1/],
  ]) {
    if (pattern.test(ci)) {
      pass(`CI ${label}`);
    } else {
      fail('ci.yml', `CI must ${label}`);
    }
  }
}

// ---------------------------------------------------------------------------
// 18. Forum frontend <-> PHP contracts
// ---------------------------------------------------------------------------

section('18. Forum frontend <-> PHP contracts');

{
  // The profile section and the badge next to a post author read attributes that
  // Api\UserResourceFields has to declare. A typo on either side renders nothing
  // at all, silently, which is exactly the failure mode that is hardest to
  // notice in a browser.
  const fieldsPath = join(EXT, 'src/Api/UserResourceFields.php');
  const declaredAttributes = new Set();

  if (!existsSync(fieldsPath)) {
    fail('UserResourceFields.php', 'the user resource fields are missing');
  } else {
    for (const match of read(fieldsPath).matchAll(/Schema\\[A-Za-z]+::make\(\s*'([A-Za-z0-9_]+)'/g)) {
      declaredAttributes.add(match[1]);
    }
  }

  const frontendFiles = walk(join(EXT, 'js/src'), (file) => file.endsWith('.js'));
  const forumEntry = join(EXT, 'js/forum.js');

  if (existsSync(forumEntry)) {
    frontendFiles.push(forumEntry);
  }

  const readAttributes = new Set();
  const readTranslations = new Set();

  for (const file of frontendFiles) {
    const source = read(file);

    for (const match of source.matchAll(/\.attribute\(\s*'(mcBridge[A-Za-z0-9_]*)'/g)) {
      readAttributes.add(match[1]);
    }

    // Translation keys are written as plain literals so they can be checked:
    // a template literal would hide the key from every static tool.
    for (const match of source.matchAll(/translator\.trans\(\s*'stalirmc-mc-bridge\.([A-Za-z0-9_.]+)'/g)) {
      readTranslations.add(match[1]);
    }
  }

  if (declaredAttributes.size === 0) {
    fail('UserResourceFields.php', 'no user resource field is declared');
  } else if (readAttributes.size === 0) {
    fail('frontend', 'no mcBridge* user attribute is read, so the profile section and the badge cannot work');
  } else {
    const undeclared = [...readAttributes].filter((name) => !declaredAttributes.has(name));

    if (undeclared.length > 0) {
      for (const name of undeclared) {
        fail('frontend attributes', `the frontend reads ${name} but UserResourceFields does not declare it`);
      }
    } else {
      pass(`all ${readAttributes.size} user attributes read by the frontend are declared in UserResourceFields`);
    }
  }

  const localeDir = join(EXT, 'locale');

  if (readTranslations.size === 0) {
    fail('frontend translations', 'no stalirmc-mc-bridge.* translation key was found in the frontend sources');
  } else {
    for (const file of walk(localeDir, (candidate) => candidate.endsWith('.yml'))) {
      const keys = flattenKeys(parseYaml(read(file)));
      const absent = [...readTranslations].filter((key) => !keys.has(`stalirmc-mc-bridge.${key}`));

      if (absent.length > 0) {
        fail(rel(file), `the frontend asks for keys that are missing here: [${absent.join(', ')}]`);
      } else {
        pass(`locale/${basename(file)} covers all ${readTranslations.size} keys the frontend asks for`);
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 19. Flarum API surface actually available on the audited release
// ---------------------------------------------------------------------------

section('19. Flarum API surface of the audited release');

{
  // The extension targets 2.0.0-rc.8, which is what the deployment runs. A local
  // framework checkout can be *newer* than that release, and APIs added after it
  // compile nowhere and fail only at runtime - which is how
  // $actor->isRegistered() took the whole forum down:
  //
  //   BadMethodCallException: Call to undefined method Flarum\User\Guest::isRegistered()
  //
  // Two things make that specific call a trap: the helper does not exist in
  // rc.8 at all, and Flarum's Guest *extends* User, so an instanceof check is not
  // a substitute either. The portable test is the actor id, since a guest
  // carries 0 (Flarum\User\Guest) and a real account a positive one.
  const phpFiles = walk(EXT, (file) => file.endsWith('.php'));
  let rc8Incompatible = 0;

  for (const file of phpFiles) {
    const source = read(file);

    if (/->isRegistered\s*\(/.test(source)) {
      fail(
        rel(file),
        'isRegistered() does not exist on Flarum 2.0.0-rc.8 (and Guest does not define it either); ' +
          'test the actor id instead - guests carry 0'
      );
      rc8Incompatible++;
    }
  }

  if (rc8Incompatible === 0) {
    pass(`no call to an API that the audited release does not provide, across ${phpFiles.length} PHP files`);
  }

  // The audited release has to stay inside what composer.json allows, otherwise
  // the checks above describe a version the deployment cannot install.
  const manifest = JSON.parse(read(join(EXT, 'composer.json')));
  const constraint = manifest.require?.['flarum/core'] ?? '';
  const audited = '2.0.0-rc.8';

  if (constraint === '') {
    fail('composer.json', 'require.flarum/core is missing');
  } else if (!/^\^?2\./.test(constraint)) {
    fail('composer.json', `flarum/core is constrained to "${constraint}", which does not cover the audited ${audited}`);
  } else {
    pass(`flarum/core ${constraint} covers the audited release ${audited}`);
  }
}

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

console.log(`\n${'─'.repeat(64)}`);
console.log(`checks run: ${checkCount}`);
console.log(`errors:     ${errors.length}`);
console.log(`warnings:   ${warnings.length}`);

if (errors.length > 0) {
  console.log('\n\u001b[31mFAILED\u001b[0m');
  for (const error of errors) console.log(`  • ${error}`);
  process.exit(1);
}

console.log('\n\u001b[32mALL CHECKS PASSED\u001b[0m');
