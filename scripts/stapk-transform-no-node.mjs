#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { cp, mkdir, readdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';

import {
  hashDirectory,
  inspectPatchQueue
} from './stapk-artifact-hashes.mjs';
import { scanWebContract } from './stapk-scan-web-contract.mjs';
import { verifyNoNodeOutput } from './stapk-verify-no-node-transform.mjs';
import { validateUiCapabilityContract } from './stapk-verify-ui-capability-contract.mjs';

export { hashDirectory } from './stapk-artifact-hashes.mjs';

const DEFAULT_REPO = 'https://github.com/SillyTavern/SillyTavern.git';
const BUILD_DIR = path.resolve('build/stapk-no-node');
const UPSTREAM_DIR = path.join(BUILD_DIR, 'upstream');
const PATCHED_DIR = path.join(BUILD_DIR, 'patched');
const PATCH_QUEUE_DIR = path.resolve('patches/sillytavern-no-node');
const WEB_SUPPORT_DIR = path.resolve('transform/no-node/web');
const WEB_OUT_DIR_NAME = 'sillytavern-web';
const API_CONTRACT_NAME = 'api-contract.json';
const MANIFEST_NAME = 'stapk-web-manifest.json';
const REPORT_NAME = 'transform-report.json';
const CAPABILITY_RUNTIME_NAME = 'stapk-capabilities.json';
const UI_CAPABILITY_SOURCE = path.resolve('transform/no-node/ui-capabilities.json');
const UI_CAPABILITY_RUNTIME_NAME = 'stapk-ui-capabilities.json';
const ANDROID_METADATA_NAMES = [API_CONTRACT_NAME, MANIFEST_NAME, REPORT_NAME];
const TRANSPARENT_PNG_BASE64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgQIAff6XWQAAAABJRU5ErkJggg==';
const KNOWN_CAPABILITY_IDS = new Set([
  'core.settings',
  'core.personas',
  'core.characters',
  'core.groups',
  'core.chats',
  'core.world_info',
  'core.backgrounds',
  'core.files',
  'core.tokenizers',
  'native.extensions',
  'core.data_management',
  'remote.embeddings',
  'remote.image',
  'remote.tts',
  'remote.stt',
  'remote.caption',
  'remote.translation',
  'excluded.extensions',
  'excluded.local_models',
  'excluded.multiuser',
]);

export async function transformNoNode({ repo = DEFAULT_REPO, ref = 'release', out, clean = false }) {
  const absoluteOut = path.resolve(out);

  if (clean) {
    await rm(BUILD_DIR, { recursive: true, force: true });
    await rm(absoluteOut, { recursive: true, force: true });
  }

  await mkdir(UPSTREAM_DIR, { recursive: true });
  await mkdir(absoluteOut, { recursive: true });

  await fetchUpstream({ repo, ref, upstreamDir: UPSTREAM_DIR });
  const commit = git(['rev-parse', 'HEAD'], UPSTREAM_DIR);
  const upstreamPackage = await readJsonIfExists(path.join(UPSTREAM_DIR, 'package.json'));

  await preparePatchedTree({ upstreamDir: UPSTREAM_DIR, patchedDir: PATCHED_DIR });
  const patchQueue = await applyPatchQueue({ patchedDir: PATCHED_DIR, patchQueueDir: PATCH_QUEUE_DIR });
  await bundleFrontendLibraries({ patchedDir: PATCHED_DIR });

  const sourceWebRoot = findSourceWebRoot(PATCHED_DIR);
  const outWebRoot = path.join(absoluteOut, WEB_OUT_DIR_NAME);
  await copyNoNodeWebAssets({ sourceWebRoot, outWebRoot });
  await copyNoNodeWebSupportAssets({ outWebRoot });
  await copyCapabilityRuntime({
    capabilityFile: path.resolve('transform/no-node/capabilities.json'),
    outWebRoot
  });
  await copyUiCapabilityContract({
    uiCapabilityFile: UI_CAPABILITY_SOURCE,
    capabilityFile: path.resolve('transform/no-node/capabilities.json'),
    outWebRoot
  });
  await copyDefaultPresets({ patchedDir: PATCHED_DIR, outWebRoot });

  const apiContract = await scanWebContract({
    webRoot: outWebRoot,
    allowlistFile: path.resolve('transform/no-node/mvp-api-allowlist.json'),
    capabilityFile: path.resolve('transform/no-node/capabilities.json'),
    upstream: {
      ref,
      commit,
      version: upstreamPackage?.version
    }
  });
  await writeJson(path.join(absoluteOut, API_CONTRACT_NAME), apiContract);

  const webRootSha256 = await hashDirectory(outWebRoot);
  const manifest = {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    upstream: {
      repo: sanitizeSourceRepository(repo),
      ref,
      commit,
      ...(upstreamPackage?.version ? { version: upstreamPackage.version } : {})
    },
    output: {
      webRoot: WEB_OUT_DIR_NAME,
      apiContract: API_CONTRACT_NAME
    },
    hashes: {
      webRootSha256,
      patchQueueSha256: patchQueue.sha256
    },
    noRuntimeNode: true
  };
  await writeJson(path.join(absoluteOut, MANIFEST_NAME), manifest);

  const verification = await verifyNoNodeOutput({ out: absoluteOut });
  const report = buildNoNodeTransformReport({
    generatedAt: new Date().toISOString(),
    output: absoluteOut,
    upstream: manifest.upstream,
    apiSummary: apiContract.summary,
    verification,
    patchNames: patchQueue.names
  });
  await writeJson(path.join(absoluteOut, REPORT_NAME), report);

  return report;
}

export async function copyNoNodeWebAssets({ sourceWebRoot, outWebRoot }) {
  const absoluteSource = path.resolve(sourceWebRoot);
  const absoluteOut = path.resolve(outWebRoot);

  if (!existsSync(absoluteSource)) {
    throw new Error(`Source Web root does not exist: ${absoluteSource}`);
  }

  await rm(absoluteOut, { recursive: true, force: true });
  await cp(absoluteSource, absoluteOut, {
    recursive: true,
    filter: (src) => shouldCopyWebAsset({ src, root: absoluteSource })
  });
}

export async function copyNoNodeWebSupportAssets({
  outWebRoot,
  supportDir = WEB_SUPPORT_DIR
}) {
  const absoluteSupportDir = path.resolve(supportDir);
  if (!existsSync(absoluteSupportDir)) {
    throw new Error(`Web support directory does not exist: ${absoluteSupportDir}`);
  }
  await mkdir(path.join(path.resolve(outWebRoot), 'scripts'), { recursive: true });
  await cp(absoluteSupportDir, path.join(path.resolve(outWebRoot), 'scripts'), {
    recursive: true,
    force: true
  });

  const absoluteOutWebRoot = path.resolve(outWebRoot);
  await mkdir(path.join(absoluteOutWebRoot, 'css'), { recursive: true });
  await mkdir(path.join(absoluteOutWebRoot, 'backgrounds'), { recursive: true });
  await writeFile(path.join(absoluteOutWebRoot, 'css', 'user.css'), '', 'utf8');
  await writeFile(
    path.join(absoluteOutWebRoot, 'backgrounds', '__transparent.png'),
    Buffer.from(TRANSPARENT_PNG_BASE64, 'base64')
  );
}

export function buildCapabilityRuntime({ capabilities }) {
  const runtime = {};
  for (const capability of Array.isArray(capabilities) ? capabilities : []) {
    if (!capability || typeof capability.id !== 'string') continue;
    const available = capability.kind === 'core'
      || (capability.kind === 'external_optional' && capability.runtimeAvailable === true);
    runtime[capability.id] = KNOWN_CAPABILITY_IDS.has(capability.id) && available;
  }
  return runtime;
}

export async function copyCapabilityRuntime({ capabilityFile, outWebRoot }) {
  const contract = JSON.parse(await readFile(path.resolve(capabilityFile), 'utf8'));
  const capabilities = buildCapabilityRuntime(contract);
  await writeJson(path.join(path.resolve(outWebRoot), CAPABILITY_RUNTIME_NAME), {
    schemaVersion: 1,
    capabilities
  });
}

export async function copyUiCapabilityContract({
  uiCapabilityFile,
  capabilityFile,
  outWebRoot
}) {
  const [uiContract, capabilities] = await Promise.all([
    readJsonIfExists(path.resolve(uiCapabilityFile)),
    readJsonIfExists(path.resolve(capabilityFile))
  ]);
  if (!uiContract) {
    throw new Error(`UI capability contract does not exist: ${path.resolve(uiCapabilityFile)}`);
  }
  if (!capabilities) {
    throw new Error(`Capability contract does not exist: ${path.resolve(capabilityFile)}`);
  }

  const errors = validateUiCapabilityContract({ uiContract, capabilities });
  if (errors.length > 0) {
    throw new Error(`Invalid UI capability contract:\n${errors.join('\n')}`);
  }
  await writeJson(path.join(path.resolve(outWebRoot), UI_CAPABILITY_RUNTIME_NAME), uiContract);
}

export async function copyDefaultPresets({ patchedDir, outWebRoot }) {
  const source = path.join(path.resolve(patchedDir), 'default', 'content', 'presets', 'openai');
  if (!existsSync(source)) return;
  await cp(source, path.join(path.resolve(outWebRoot), 'defaults', 'presets', 'openai'), { recursive: true });
}

export function buildNoNodeTransformReport({
  generatedAt,
  output,
  upstream,
  apiSummary,
  verification,
  patchNames
}) {
  return {
    ok: true,
    generatedAt,
    output: toLogicalOutputName(output),
    upstream: {
      ...upstream,
      ...(upstream?.repo ? { repo: sanitizeSourceRepository(upstream.repo) } : {})
    },
    patches: [...patchNames],
    apiSummary,
    verification
  };
}

function toLogicalOutputName(output) {
  return String(output).replaceAll('\\', '/').split('/').filter(Boolean).at(-1) ?? '.';
}

function sanitizeSourceRepository(repo) {
  const value = String(repo);
  if (/^file:\/\//i.test(value) || /^[A-Za-z]:[\\/]/.test(value) || path.isAbsolute(value)) {
    return 'local-cache';
  }
  return value;
}

export async function bundleFrontendLibraries({ patchedDir }) {
  const absolutePatchedDir = path.resolve(patchedDir);
  runNpm(['ci', '--ignore-scripts', '--no-audit', '--no-fund'], absolutePatchedDir);
  runExternal(process.execPath, ['docker/build-lib.js'], absolutePatchedDir);

  const webpackRoot = path.join(absolutePatchedDir, 'dist', '_webpack');
  const bundles = (await listFiles(webpackRoot)).filter((file) =>
    path.basename(file) === 'lib.js' && path.basename(path.dirname(file)) === 'output'
  );
  if (bundles.length !== 1) {
    throw new Error(`Expected one generated Webpack lib.js, found ${bundles.length}`);
  }

  await cp(bundles[0], path.join(absolutePatchedDir, 'public', 'lib.js'));
}

export async function syncNoNodeAndroidAssets({ transformOut, androidAssetsDir }) {
  const absoluteOut = path.resolve(transformOut);
  const absoluteAssets = path.resolve(androidAssetsDir);
  await verifyNoNodeOutput({ out: absoluteOut });
  const buildManifest = JSON.parse(await readFile(
    path.join(absoluteOut, MANIFEST_NAME),
    'utf8'
  ));
  const expectedPatchQueueSha256 = buildManifest.hashes.patchQueueSha256;

  const assetsParent = path.dirname(absoluteAssets);
  const assetsName = path.basename(absoluteAssets);
  const stagedAssets = path.join(assetsParent, `.${assetsName}.stapk-installing`);
  const previousAssets = path.join(assetsParent, `.${assetsName}.stapk-previous`);
  await mkdir(assetsParent, { recursive: true });
  await rm(stagedAssets, { recursive: true, force: true });
  await mkdir(stagedAssets, { recursive: true });
  await cp(
    path.join(absoluteOut, WEB_OUT_DIR_NAME),
    path.join(stagedAssets, WEB_OUT_DIR_NAME),
    { recursive: true }
  );

  for (const metadataName of ANDROID_METADATA_NAMES) {
    await cp(path.join(absoluteOut, metadataName), path.join(stagedAssets, metadataName));
  }

  await verifyNoNodeOutput({
    out: stagedAssets,
    expectedPatchQueueSha256
  });
  await rm(previousAssets, { recursive: true, force: true });
  if (existsSync(absoluteAssets)) {
    await rename(absoluteAssets, previousAssets);
  }

  try {
    await rename(stagedAssets, absoluteAssets);
    await verifyNoNodeOutput({
      out: absoluteAssets,
      expectedPatchQueueSha256
    });
  } catch (error) {
    await rm(absoluteAssets, { recursive: true, force: true });
    if (existsSync(previousAssets)) {
      await rename(previousAssets, absoluteAssets);
    }
    throw error;
  }

  await rm(previousAssets, { recursive: true, force: true });
}

export function findSourceWebRoot(patchedDir) {
  const candidates = [
    path.join(patchedDir, 'public'),
    path.join(patchedDir, 'dist'),
    path.join(patchedDir, 'web')
  ];

  for (const candidate of candidates) {
    if (existsSync(path.join(candidate, 'index.html'))) {
      return candidate;
    }
  }

  throw new Error(`Cannot find built Web root under ${patchedDir}; expected public/index.html`);
}

async function fetchUpstream({ repo, ref, upstreamDir }) {
  if (!existsSync(path.join(upstreamDir, '.git'))) {
    git(['init'], upstreamDir);
    git(['remote', 'add', 'origin', repo], upstreamDir);
  } else {
    git(['remote', 'set-url', 'origin', repo], upstreamDir);
  }

  git(['fetch', '--depth=1', 'origin', ref], upstreamDir);
  git(['checkout', '--detach', 'FETCH_HEAD'], upstreamDir);
}

async function preparePatchedTree({ upstreamDir, patchedDir }) {
  await rm(patchedDir, { recursive: true, force: true });
  await cp(upstreamDir, patchedDir, {
    recursive: true,
    filter: (src) => !isGitMetadataPath(src, upstreamDir)
  });
  git(['init'], patchedDir);
  git(['config', 'user.name', 'stapk'], patchedDir);
  git(['config', 'user.email', 'stapk@localhost'], patchedDir);
  git(['add', '.'], patchedDir);
  git(['commit', '-m', 'baseline'], patchedDir);
}

export async function applyPatchQueue({ patchedDir, patchQueueDir }) {
  const patchQueue = await inspectPatchQueue(patchQueueDir);

  for (const patchName of patchQueue.names) {
    const patchPath = path.join(patchQueueDir, patchName);
    git(['apply', '--3way', patchPath], patchedDir);
  }

  return patchQueue;
}

async function listFiles(root) {
  const result = [];
  const entries = await readdir(root);

  for (const entry of entries) {
    const absolutePath = path.join(root, entry);
    const entryStat = await stat(absolutePath);
    if (entryStat.isDirectory()) {
      result.push(...await listFiles(absolutePath));
    } else if (entryStat.isFile()) {
      result.push(absolutePath);
    }
  }

  return result.sort();
}

function shouldCopyWebAsset({ src, root }) {
  const relative = path.relative(root, src);
  if (!relative) {
    return true;
  }

  const segments = toPosixPath(relative).split('/').map((segment) => segment.toLowerCase());
  if (segments.includes('.git') || segments.includes('node_modules')) {
    return false;
  }

  const normalizedRelative = segments.join('/');
  if (
    normalizedRelative === 'scripts/extensions/tts/kokoro.js'
    || normalizedRelative === 'scripts/extensions/tts/lib/kokoro.web.js'
  ) {
    return false;
  }

  const basename = segments.at(-1);
  return ![
    'server.js',
    'jsconfig.json',
    'payload.tgz',
    'sillytavern.tar.gz',
    'runtime-android-arm64-node24.zip'
  ].includes(basename);
}

function isGitMetadataPath(src, root) {
  const relative = path.relative(root, src);
  return toPosixPath(relative).split('/').includes('.git');
}

async function readJsonIfExists(file) {
  if (!existsSync(file)) {
    return null;
  }
  return JSON.parse(await readFile(file, 'utf8'));
}

async function writeJson(file, value) {
  await mkdir(path.dirname(file), { recursive: true });
  await writeFile(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function git(args, cwd) {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe']
  }).trim();
}

function runNpm(args, cwd) {
  if (process.platform === 'win32') {
    runExternal(process.env.ComSpec ?? 'cmd.exe', ['/d', '/s', '/c', 'npm', ...args], cwd);
    return;
  }
  runExternal('npm', args, cwd);
}

function runExternal(command, args, cwd) {
  execFileSync(command, args, {
    cwd,
    stdio: 'inherit'
  });
}

function toPosixPath(value) {
  return value.split(path.sep).join('/');
}

async function main() {
  const { values } = parseArgs({
    options: {
      repo: { type: 'string' },
      ref: { type: 'string' },
      out: { type: 'string' },
      clean: { type: 'boolean' },
      'android-assets': { type: 'string' }
    }
  });

  const transformOut = path.resolve(values.out ?? 'build/no-node-payload');
  const report = await transformNoNode({
    repo: values.repo ?? DEFAULT_REPO,
    ref: values.ref ?? 'release',
    out: transformOut,
    clean: values.clean ?? false
  });

  if (values['android-assets']) {
    await syncNoNodeAndroidAssets({
      transformOut,
      androidAssetsDir: values['android-assets']
    });
    console.log(`Synced Android assets: ${path.resolve(values['android-assets'])}`);
  }

  console.log(`Generated no-node transform output: ${transformOut}`);
  console.log(`Upstream commit: ${report.upstream.commit}`);
  console.log(`API summary: ${JSON.stringify(report.apiSummary)}`);
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
