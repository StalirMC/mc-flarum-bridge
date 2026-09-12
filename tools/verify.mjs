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

const jsonFiles = walk(ROOT, (file) => file.endsWith('.json'));

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
// 5/6. Java layout and imports
// ---------------------------------------------------------------------------

section('5. Java package layout');

const javaRoot = join(PLUGIN, 'src/main/java');
const javaFiles = walk(javaRoot, (file) => file.endsWith('.java'));
const javaTypes = new Map(); // FQCN -> file

for (const file of javaFiles) {
  const source = read(file);
  const packageMatch = source.match(/^package\s+([A-Za-z0-9_.]+);/m);
  const typeMatch = source.match(/^(?:public\s+)?(?:final\s+|abstract\s+)?(?:class|interface|enum|record)\s+([A-Za-z0-9_]+)/m);

  if (!packageMatch || !typeMatch) {
    fail(`Java ${rel(file)}`, 'missing package or type declaration');
    continue;
  }

  const expectedPath = packageMatch[1].split('.').join('/');
  const relativeDir = dirname(rel(file).split('/src/main/java/')[1]);

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
  const imports = [...read(file).matchAll(/^import\s+(cn\.stalir\.mcbridge[A-Za-z0-9_.]*);/gm)].map((m) => m[1]);

  for (const imported of imports) {
    if (javaTypes.has(imported)) {
      pass(`${rel(file)} imports ${imported}`);
    } else {
      fail(`${rel(file)}`, `import ${imported} does not resolve to a class under src/main/java`);
    }
  }
}

// ---------------------------------------------------------------------------
// 7. plugin.yml agreement
// ---------------------------------------------------------------------------

section('7. plugin.yml agreement');

const pluginYml = parseYaml(read(join(PLUGIN, 'src/main/resources/plugin.yml')));
const pluginSources = javaFiles.map(read).join('\n');

if (!pluginYml.main) {
  fail('plugin.yml', 'no main class declared');
} else if (!javaTypes.has(pluginYml.main)) {
  fail('plugin.yml', `main ${pluginYml.main} does not exist`);
} else {
  pass(`plugin.yml main -> ${pluginYml.main}`);
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

const migrationSource = read(
  walk(join(EXT, 'migrations'), (file) => file.endsWith('.php'))[0] ?? join(EXT, 'migrations', 'missing')
);
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
  const migrationFile = walk(join(EXT, 'migrations'), (file) => file.endsWith('.php'))[0];
  const migration = read(migrationFile);

  if (!/'up'\s*=>/.test(migration) || !/'down'\s*=>/.test(migration)) {
    fail('migration contract', "the migration must return an array with 'up' and 'down' keys (Flarum 2.x)");
  } else {
    pass("migration returns ['up' => ..., 'down' => ...]");
  }

  if (/\$this->schema/.test(migration)) {
    fail('migration contract', 'uses $this->schema, which does not exist on Flarum 2.x migrations');
  } else {
    pass('migration avoids $this->schema');
  }

  if (/extends\s+Migration\b/.test(migration)) {
    fail('migration contract', 'must not extend Flarum\\Database\\Migration in Flarum 2.x');
  } else {
    pass('migration does not extend the 1.x Migration base class');
  }

  if (!/function\s*\(\s*Builder\s+\$schema\s*\)/.test(migration)) {
    warn('migration contract', 'expected the closures to receive an Illuminate\\Database\\Schema\\Builder');
  } else {
    pass('migration closures receive the schema Builder');
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
    'mc-bridge.heartbeat',
    'mc-bridge.events',
    'mc-bridge.outbox',
    'mc-bridge.announcements',
    'mc-bridge.bind.start',
    'mc-bridge.bind.status',
    'mc-bridge.broadcast',
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

    for (const [simple, qualified] of Object.entries(KNOWN_TYPES)) {
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
