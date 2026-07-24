#!/usr/bin/env node

import { existsSync } from 'node:fs';
import { readdir, readFile, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';

import {
  hashDirectory,
  hashPatchQueue
} from './stapk-artifact-hashes.mjs';
import {
  isCapabilityVerificationAllowed,
  verifyCapabilityContract
} from './stapk-verify-capability-contract.mjs';
import { verifyUiCapabilityContract } from './stapk-verify-ui-capability-contract.mjs';

const REQUIRED_FILES = [
  'sillytavern-web/index.html',
  'sillytavern-web/lib.js',
  'sillytavern-web/stapk-capabilities.json',
  'sillytavern-web/stapk-ui-capabilities.json',
  'api-contract.json',
  'stapk-web-manifest.json'
];

const FORBIDDEN_BASENAMES = new Set([
  'node',
  'node.exe',
  'node.cmd',
  'npm',
  'npm.cmd',
  'npx',
  'npx.cmd',
  'server.js',
  'payload.tgz',
  'sillytavern.tar.gz',
  'runtime.zip',
  'runtime-android-arm64-node24.zip'
]);

const FORBIDDEN_SEGMENTS = new Set([
  'node_modules'
]);

export async function verifyNoNodeOutput({
  out,
  capabilityFile = path.resolve('transform/no-node/capabilities.json'),
  patchQueueDir = path.resolve('patches/sillytavern-no-node'),
  expectedPatchQueueSha256
}) {
  const absoluteOut = path.resolve(out);
  const errors = [];
  const warnings = [];

  if (!existsSync(absoluteOut)) {
    throw new Error(`Output directory does not exist: ${absoluteOut}`);
  }

  for (const requiredFile of REQUIRED_FILES) {
    const requiredPath = path.join(absoluteOut, ...requiredFile.split('/'));
    if (!existsSync(requiredPath)) {
      errors.push(`Missing required file: ${requiredFile}`);
    }
  }

  const entries = await listEntries(absoluteOut);
  for (const entry of entries) {
    const forbiddenReason = getForbiddenReason(entry.relativePath);
    if (forbiddenReason) {
      errors.push(`Forbidden runtime artifact: ${entry.relativePath} (${forbiddenReason})`);
    }
  }

  const browserLibraryPath = path.join(absoluteOut, 'sillytavern-web', 'lib.js');
  if (existsSync(browserLibraryPath)) {
    const browserLibrary = await readFile(browserLibraryPath, 'utf8');
    for (const specifier of findBareBrowserModuleSpecifiers(browserLibrary)) {
      errors.push(`Bare browser module specifier: ${specifier}`);
    }
  }

  const manifestPath = path.join(absoluteOut, 'stapk-web-manifest.json');
  if (existsSync(manifestPath)) {
    const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
    if (manifest.noRuntimeNode !== true) {
      errors.push('Manifest must set noRuntimeNode: true');
    }

    const manifestWebRootSha256 = manifest.hashes?.webRootSha256;
    if (!isSha256(manifestWebRootSha256)) {
      errors.push('Manifest hashes.webRootSha256 must be a lowercase SHA-256');
    } else {
      const webRoot = path.join(absoluteOut, 'sillytavern-web');
      if (existsSync(webRoot)) {
        const actualWebRootSha256 = await hashDirectory(webRoot);
        if (actualWebRootSha256 !== manifestWebRootSha256) {
          errors.push(
            `Web root SHA-256 mismatch: expected ${manifestWebRootSha256}, got ${actualWebRootSha256}`
          );
        }
      }
    }

    const manifestPatchQueueSha256 = manifest.hashes?.patchQueueSha256;
    if (!isSha256(manifestPatchQueueSha256)) {
      errors.push('Manifest hashes.patchQueueSha256 must be a lowercase SHA-256');
    } else {
      let expectedPatchHash = expectedPatchQueueSha256;
      if (expectedPatchHash === undefined) {
        expectedPatchHash = await hashPatchQueue(patchQueueDir);
      } else if (!isSha256(expectedPatchHash)) {
        errors.push('Expected patch queue SHA-256 must be a lowercase SHA-256');
      }

      if (isSha256(expectedPatchHash) && expectedPatchHash !== manifestPatchQueueSha256) {
        errors.push(
          `Patch queue SHA-256 mismatch: expected ${expectedPatchHash}, got ${manifestPatchQueueSha256}`
        );
      }
    }
  }

  const contractPath = path.join(absoluteOut, 'api-contract.json');
  if (existsSync(contractPath)) {
    const contract = JSON.parse(await readFile(contractPath, 'utf8'));
    if ((contract.summary?.needs_review ?? 0) > 0) {
      warnings.push(`API contract still has ${contract.summary.needs_review} endpoint(s) marked needs_review`);
    }
  }

  let uiCapabilityVerification = null;
  const webRoot = path.join(absoluteOut, 'sillytavern-web');
  const uiContractPath = path.join(webRoot, 'stapk-ui-capabilities.json');
  if (
    existsSync(path.join(webRoot, 'index.html'))
    && existsSync(uiContractPath)
    && existsSync(contractPath)
    && existsSync(path.resolve(capabilityFile))
  ) {
    uiCapabilityVerification = await verifyUiCapabilityContract({
      webRoot,
      uiContractFile: uiContractPath,
      apiContractFile: contractPath,
      capabilityFile
    });
    errors.push(...uiCapabilityVerification.errors.map((error) => `UI capability: ${error}`));
  }

  const result = {
    ok: errors.length === 0,
    errors,
    warnings,
    uiCapabilityVerification,
    scannedFiles: entries.filter((entry) => !entry.isDirectory).length,
    scannedDirectories: entries.filter((entry) => entry.isDirectory).length
  };

  if (!result.ok) {
    throw new Error(errors.join('\n'));
  }

  return result;
}

async function listEntries(root, relativeRoot = '') {
  const current = path.join(root, relativeRoot);
  const children = await readdir(current);
  const entries = [];

  for (const child of children) {
    const relativePath = toPosixPath(path.join(relativeRoot, child));
    const absolutePath = path.join(root, relativePath);
    const entryStat = await stat(absolutePath);
    const entry = {
      relativePath,
      isDirectory: entryStat.isDirectory()
    };
    entries.push(entry);

    if (entry.isDirectory) {
      entries.push(...await listEntries(root, relativePath));
    }
  }

  return entries.sort((left, right) => left.relativePath.localeCompare(right.relativePath));
}

function getForbiddenReason(relativePath) {
  const segments = relativePath.split('/').map((segment) => segment.toLowerCase());
  const basename = segments.at(-1);

  if (segments.some((segment) => FORBIDDEN_SEGMENTS.has(segment))) {
    return 'node_modules directory is not allowed';
  }

  if (FORBIDDEN_BASENAMES.has(basename)) {
    return 'runtime Node payload file is not allowed';
  }

  if (/^runtime-.*node.*\.zip(?:\.sha256)?$/i.test(basename)) {
    return 'runtime Node archive is not allowed';
  }

  return null;
}

function findBareBrowserModuleSpecifiers(source) {
  const specifiers = new Set();
  const patterns = [
    /^\s*import\s+(?:[^'"\n]*?\s+from\s+)?['"]([^'"]+)['"]/gm,
    /^\s*export\s+[^'"\n]*?\s+from\s+['"]([^'"]+)['"]/gm
  ];

  for (const pattern of patterns) {
    for (const match of source.matchAll(pattern)) {
      const specifier = match[1];
      if (
        !specifier.startsWith('.') &&
        !specifier.startsWith('/') &&
        !/^[a-z][a-z+.-]*:/i.test(specifier)
      ) {
        specifiers.add(specifier);
      }
    }
  }

  return [...specifiers].sort();
}

function isSha256(value) {
  return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
}

function toPosixPath(value) {
  return value.split(path.sep).join('/');
}

async function main() {
  const { values } = parseArgs({
    options: {
      out: { type: 'string' },
      report: { type: 'string' },
      capabilities: { type: 'string' },
      'allow-visible-needs-review': { type: 'boolean' }
    }
  });

  if (!values.out) {
    throw new Error('Missing required option: --out');
  }

  try {
    const capabilityFile = values.capabilities ?? 'transform/no-node/capabilities.json';
    const outputVerification = await verifyNoNodeOutput({
      out: values.out,
      capabilityFile
    });
    const [apiContract, capabilities] = await Promise.all([
      readJson(path.join(values.out, 'api-contract.json')),
      readJson(capabilityFile)
    ]);
    const capabilityVerification = verifyCapabilityContract({ apiContract, capabilities });
    const result = {
      ...outputVerification,
      ok: outputVerification.ok && capabilityVerification.ok,
      capabilityVerification
    };
    if (values.report) {
      await writeFile(path.resolve(values.report), `${JSON.stringify(result, null, 2)}\n`, 'utf8');
    }
    if (!isCapabilityVerificationAllowed(
      capabilityVerification,
      values['allow-visible-needs-review']
    )) {
      throw new Error(capabilityVerification.errors.join('\n'));
    }
    console.log(`Verified no-node transform output: ${path.resolve(values.out)}`);
  } catch (error) {
    if (values.report) {
      const result = {
        ok: false,
        errors: String(error instanceof Error ? error.message : error).split('\n').filter(Boolean)
      };
      await writeFile(path.resolve(values.report), `${JSON.stringify(result, null, 2)}\n`, 'utf8');
    }
    throw error;
  }
}

async function readJson(file) {
  return JSON.parse(await readFile(path.resolve(file), 'utf8'));
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
