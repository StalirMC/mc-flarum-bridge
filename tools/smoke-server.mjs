#!/usr/bin/env node
/**
 * Loads a built McBridge jar on a real Minecraft server and asserts that the
 * plugin actually enables.
 *
 * Every other check in this repository stops at "compiles" and "the jar contains
 * the right entries". Neither proves the server accepts the jar at runtime: a
 * missing class, a bad descriptor field or an API mismatch only shows up when a
 * real server loads it. This script closes that gap for Paper and Folia.
 *
 *   node tools/smoke-server.mjs --jar <path-to-plugin-jar>
 *   node tools/smoke-server.mjs --jar <jar> --project folia --version 1.21.8
 *
 * It downloads the server from the PaperMC v3 API, writes a throwaway server
 * directory, starts it headless, waits for the plugin to enable and the server
 * to finish starting, then stops it cleanly through the server console and
 * asserts a zero exit code.
 *
 * Notes
 *  - The v2 API (api.papermc.io) was retired and answers 410, so v3
 *    (fill.papermc.io) is used and it requires a User-Agent header.
 *  - Folia does not publish 1.21.1. Its 1.21.x line starts at 1.21.4; 1.21.8 is
 *    the STABLE one, so that is the default for the Folia run.
 *  - Only Paper and Folia are covered here. NeoForge needs its own installer
 *    step and is not part of this script.
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';

const USER_AGENT =
  'mc-flarum-bridge-smoke/1.0 (+https://github.com/StalirMC/mc-flarum-bridge)';
const API = 'https://fill.papermc.io/v3/projects';

// Folia has no 1.21.1 build; 1.21.8 is its stable 1.21.x line.
const DEFAULT_VERSIONS = { paper: '1.21.1', folia: '1.21.8' };

function parseArgs(argv) {
  const args = { project: 'paper', version: null, timeout: 300 };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--jar') args.jar = argv[++i];
    else if (a === '--project') args.project = argv[++i];
    else if (a === '--version') args.version = argv[++i];
    else if (a === '--workdir') args.workdir = argv[++i];
    else if (a === '--timeout') args.timeout = Number(argv[++i]);
    else if (a === '-h' || a === '--help') args.help = true;
    else throw new Error(`unknown argument: ${a}`);
  }
  args.version ??= DEFAULT_VERSIONS[args.project];
  return args;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function resolveDownload(project, version) {
  const url = `${API}/${project}/versions/${version}/builds/latest`;
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) {
    throw new Error(
      `${project} ${version} is not downloadable: HTTP ${res.status} from ${url}. ` +
        `Check the version exists (Folia has no 1.21.1 build).`,
    );
  }
  const build = await res.json();
  const server = build.downloads?.['server:default'];
  if (!server?.url) throw new Error(`${project} ${version}: no server download in the API response`);
  return { url: server.url, build: build.id, channel: build.channel };
}

async function download(url, target) {
  if (fs.existsSync(target) && fs.statSync(target).size > 1024) {
    console.log(`    reusing ${path.basename(target)} (${(fs.statSync(target).size / 1048576).toFixed(1)} MB)`);
    return;
  }
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) throw new Error(`download failed: HTTP ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  await fsp.writeFile(target, buf);
  console.log(`    downloaded ${path.basename(target)} (${(buf.length / 1048576).toFixed(1)} MB)`);
}

async function prepare(workdir, serverJar, pluginJar) {
  await fsp.mkdir(path.join(workdir, 'plugins'), { recursive: true });
  // Paperclip unpacks the vanilla server into cache/ on first run. Creating it
  // up front keeps the first start from failing on a machine where the JVM
  // cannot create directories itself.
  await fsp.mkdir(path.join(workdir, 'cache'), { recursive: true });
  await fsp.writeFile(path.join(workdir, 'eula.txt'), 'eula=true\n');
  // A flat world, no online mode and a tiny view distance keep the boot under a
  // minute; none of it affects whether the plugin loads.
  await fsp.writeFile(
    path.join(workdir, 'server.properties'),
    [
      'level-type=flat',
      'online-mode=false',
      'max-players=1',
      'view-distance=3',
      'simulation-distance=3',
      'spawn-protection=0',
      'difficulty=peaceful',
      'motd=mc-flarum-bridge smoke test',
      '',
    ].join('\n'),
  );
  await fsp.copyFile(pluginJar, path.join(workdir, 'plugins', path.basename(pluginJar)));
  await fsp.copyFile(serverJar, path.join(workdir, path.basename(serverJar)));
}

/** Waits until `test(log)` passes, or throws with the log tail. */
async function waitForLog(readLog, test, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const log = readLog();
    if (test(log)) return log;
    await sleep(1000);
  }
  throw new Error(`timed out after ${Math.round(timeoutMs / 1000)}s waiting for: ${label}`);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help || !args.jar) {
    console.log('usage: node tools/smoke-server.mjs --jar <plugin.jar> [--project paper|folia] [--version X] [--workdir DIR] [--timeout SECONDS] [--keep]');
    process.exit(args.help ? 0 : 1);
  }
  if (!fs.existsSync(args.jar)) throw new Error(`plugin jar not found: ${args.jar}`);

  const workdir = args.workdir ?? path.join(os.tmpdir(), `mcbridge-smoke-${args.project}`);
  await fsp.mkdir(workdir, { recursive: true });

  console.log(`== ${args.project} ${args.version} smoke test`);
  console.log(`   plugin jar : ${args.jar}`);
  console.log(`   work dir   : ${workdir}`);

  const dl = await resolveDownload(args.project, args.version);
  console.log(`   server     : ${args.project} ${args.version} build ${dl.build} (${dl.channel})`);

  const serverJar = path.join(workdir, `${args.project}-${args.version}.jar`);
  await download(dl.url, serverJar);
  await prepare(workdir, serverJar, args.jar);

  const logPath = path.join(workdir, 'server.log');
  const logFd = fs.openSync(logPath, 'w');

  console.log('   starting the server...');
  const child = spawn(
    process.env.JAVA_CMD || 'java',
    [`-Xms512M`, `-Xmx1400M`, '-jar', path.basename(serverJar), '--nogui'],
    // stdout/stderr go straight to the log file rather than through a pipe: it
    // avoids pipe-buffer deadlocks and keeps the file usable while it runs.
    { cwd: workdir, stdio: ['pipe', logFd, logFd] },
  );

  const readLog = () => (fs.existsSync(logPath) ? fs.readFileSync(logPath, 'utf8') : '');
  let exited = null;
  child.on('exit', (code) => { exited = code ?? 0; });

  try {
    const timeoutMs = args.timeout * 1000;

    await waitForLog(readLog, (l) => /Enabling McBridge v/.test(l), timeoutMs, 'the plugin to enable');
    console.log('   plugin enabled');

    await waitForLog(readLog, (l) => /Done \(/.test(l), timeoutMs, 'the server to finish starting');
    console.log('   server finished starting');

    // A jar the server cannot load shows up as one of these rather than as a
    // missing enable line, so name them explicitly in the failure.
    const log = readLog();
    const fatal = [
      /Could not load 'plugins\//,
      /UnsupportedClassVersionError/,
      /NoClassDefFoundError: cn\/stalir/,
      /ClassNotFoundException: cn\.stalir/,
      /NoSuchMethodError: cn\.stalir/,
      /Invalid plugin\.yml/,
      /Caused by: java\.lang\.NoSuchMethodError/,
    ].find((re) => re.test(log));
    if (fatal) throw new Error(`the server log contains a load failure matching ${fatal}`);

    const version = (args.jar.match(/(\d+\.\d+\.\d+)/) || [, ''])[1];
    if (version && !new RegExp(`Enabling McBridge v${version.replace(/\./g, '\\.')}`).test(log)) {
      throw new Error(`the server enabled a different version than ${version}`);
    }

    console.log('   stopping the server through the console...');
    child.stdin.write('stop\n');
    await waitForLog(readLog, (l) => /Stopping server/.test(l), 120_000, 'the server to stop');

    const deadline = Date.now() + 120_000;
    while (exited === null && Date.now() < deadline) await sleep(500);
    if (exited === null) throw new Error('the server process did not exit after "stop"');
    if (exited !== 0) throw new Error(`the server exited with code ${exited}`);

    console.log(`   clean shutdown (exit ${exited})`);
    console.log(`PASS ${args.project} ${args.version}: the plugin loaded, enabled and the server stopped cleanly`);
  } catch (error) {
    console.error(`\nFAIL ${args.project} ${args.version}: ${error.message}`);
    const tail = readLog().split('\n').slice(-40).join('\n');
    console.error('---- last 40 log lines ----\n' + tail);
    try { child.kill(); } catch { /* already gone */ }
    process.exitCode = 1;
  } finally {
    fs.closeSync(logFd);
  }
}

main().catch((error) => {
  console.error(`FAIL: ${error.message}`);
  process.exit(1);
});
