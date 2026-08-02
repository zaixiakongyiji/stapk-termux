#!/usr/bin/env node

import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import { existsSync } from 'node:fs';
import { copyFile, mkdir, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';

const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const METADATA_ARTIFACTS = [
  ['api-contract.json', 'api-contract.json'],
  ['sillytavern-web/stapk-capabilities.json', 'stapk-capabilities.json'],
  ['stapk-web-manifest.json', 'stapk-web-manifest.json'],
  ['transform-report.json', 'transform-report.json']
];

export async function buildNoNodeApk({
  variant = 'debug',
  ref = 'release',
  repo,
  projectRoot = PROJECT_ROOT,
  platform = process.platform,
  runCommand
} = {}) {
  validateOptions({ variant, ref, repo });
  const root = path.resolve(projectRoot);
  const transformOut = path.join(root, 'build', 'no-node-payload');
  const androidAssets = path.join(root, 'mobile', 'app', 'src', 'main', 'assets');
  const mobileRoot = path.join(root, 'mobile');
  const gradleCommand = path.join(mobileRoot, platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  const commandRunner = runCommand ?? ((command) => runExternal({ ...command, platform }));
  const noNodeTestFiles = await listNoNodeTestFiles(root);
  const transformArgs = ['scripts/stapk-transform-no-node.mjs'];
  if (repo) transformArgs.push('--repo', repo);
  transformArgs.push(
    '--ref', ref,
    '--out', transformOut,
    '--android-assets', androidAssets,
    '--clean'
  );

  await commandRunner({
    command: process.execPath,
    args: transformArgs,
    cwd: root
  });
  await commandRunner({
    command: process.execPath,
    args: ['--test', '--test-concurrency=1', ...noNodeTestFiles],
    cwd: root
  });
  await commandRunner({
    command: process.execPath,
    args: [
      'scripts/stapk-verify-no-node-transform.mjs',
      '--out', transformOut,
      '--capabilities', path.join(root, 'transform', 'no-node', 'capabilities.json')
    ],
    cwd: root
  });
  await commandRunner({
    command: process.execPath,
    args: [
      'scripts/stapk-verify-capability-contract.mjs',
      '--contract', path.join(transformOut, 'api-contract.json'),
      '--capabilities', path.join(root, 'transform', 'no-node', 'capabilities.json')
    ],
    cwd: root
  });
  await commandRunner({ command: gradleCommand, args: ['--no-daemon', ':app:testDebugUnitTest'], cwd: mobileRoot });
  const assembleTask = variant === 'debug' ? ':app:assembleDebug' : ':app:assembleRelease';
  await commandRunner({ command: gradleCommand, args: ['--no-daemon', assembleTask], cwd: mobileRoot });

  const outputRoot = path.join(root, 'output');
  const stagingRoot = path.join(outputRoot, '.stapk-no-node-staging');
  await rm(stagingRoot, { recursive: true, force: true });
  await mkdir(stagingRoot, { recursive: true });

  try {
    const apkSource = path.join(
      mobileRoot,
      'app', 'build', 'outputs', 'apk', variant,
      `app-${variant}.apk`
    );
    const apkName = `stapk-mobile-${variant}.apk`;
    await copyRequiredFile(apkSource, path.join(stagingRoot, apkName));
    for (const [sourceName, targetName] of METADATA_ARTIFACTS) {
      await copyRequiredFile(path.join(transformOut, ...sourceName.split('/')), path.join(stagingRoot, targetName));
    }

    const digest = crypto.createHash('sha256')
      .update(await readFile(path.join(stagingRoot, apkName)))
      .digest('hex');
    const checksumName = `${apkName}.sha256`;
    await writeFile(path.join(stagingRoot, checksumName), `${digest}  ${apkName}\n`, 'utf8');

    const artifactNames = [apkName, checksumName, ...METADATA_ARTIFACTS.map(([, target]) => target)];
    await publishArtifacts({ outputRoot, stagingRoot, artifactNames });
    const artifacts = artifactNames.map((name) => path.join(outputRoot, name));
    console.log(`已生成 ${variant} APK 和 ${artifacts.length - 1} 个配套产物：${outputRoot}`);
    return { variant, ref, artifacts };
  } finally {
    await rm(stagingRoot, { recursive: true, force: true });
  }
}

async function listNoNodeTestFiles(root) {
  const entries = await readdir(path.join(root, 'test', 'no-node'), { withFileTypes: true });
  const files = entries
    .filter((entry) => entry.isFile() && entry.name.endsWith('.test.mjs'))
    .map((entry) => path.posix.join('test', 'no-node', entry.name))
    .sort();
  if (files.length === 0) throw new Error('未找到 no-node 测试文件');
  return files;
}

function validateOptions({ variant, ref, repo }) {
  if (!['debug', 'release'].includes(variant)) {
    throw new Error('variant 只接受 debug 或 release');
  }
  if (
    typeof ref !== 'string' ||
    !/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(ref) ||
    ref.includes('..') ||
    ref.includes('//')
  ) {
    throw new Error(`ref 非法：${ref}`);
  }
  if (
    repo !== undefined &&
    (typeof repo !== 'string' || !repo.trim() || /[\u0000-\u001f\u007f]/.test(repo))
  ) {
    throw new Error('repo 非法');
  }
}

async function copyRequiredFile(source, target) {
  if (!existsSync(source)) throw new Error(`缺少构建产物：${source}`);
  await copyFile(source, target);
}

async function publishArtifacts({ outputRoot, stagingRoot, artifactNames }) {
  await mkdir(outputRoot, { recursive: true });
  const backups = [];
  const published = [];
  try {
    for (const name of artifactNames) {
      const target = path.join(outputRoot, name);
      const backup = path.join(outputRoot, `.${name}.stapk-previous`);
      await rm(backup, { force: true });
      if (existsSync(target)) {
        await rename(target, backup);
        backups.push([target, backup]);
      }
      await rename(path.join(stagingRoot, name), target);
      published.push(target);
    }
    await Promise.all(backups.map(([, backup]) => rm(backup, { force: true })));
  } catch (error) {
    await Promise.all(published.map((target) => rm(target, { force: true })));
    for (const [target, backup] of backups.reverse()) {
      if (existsSync(backup)) await rename(backup, target);
    }
    throw error;
  }
}

function runExternal({ command, args, cwd, platform }) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      stdio: 'inherit',
      shell: platform === 'win32' && /\.(?:cmd|bat)$/i.test(command)
    });
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`命令失败 (${code ?? signal})：${command} ${args.join(' ')}`));
      }
    });
  });
}

async function main() {
  const { values } = parseArgs({
    options: {
      variant: { type: 'string', default: 'debug' },
      ref: { type: 'string', default: 'release' },
      repo: { type: 'string' }
    }
  });
  await buildNoNodeApk({ variant: values.variant, ref: values.ref, repo: values.repo });
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
