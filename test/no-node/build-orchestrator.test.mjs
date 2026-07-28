import assert from 'node:assert/strict';
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { buildNoNodeApk } from '../../scripts/stapk-build-no-node-apk.mjs';

const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

async function withTempProject(fn) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'stapk-build-orchestrator-'));
  try {
    const testRoot = path.join(root, 'test', 'no-node');
    await mkdir(testRoot, { recursive: true });
    await Promise.all([
      writeFile(path.join(testRoot, 'alpha.test.mjs'), ''),
      writeFile(path.join(testRoot, 'zeta.test.mjs'), ''),
      writeFile(path.join(testRoot, 'helper.mjs'), '')
    ]);
    await fn(root);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

async function createTransformArtifacts(root) {
  const transformOut = path.join(root, 'build', 'no-node-payload');
  await mkdir(path.join(transformOut, 'sillytavern-web'), { recursive: true });
  await Promise.all([
    writeFile(path.join(transformOut, 'api-contract.json'), '{"contract":true}\n'),
    writeFile(path.join(transformOut, 'sillytavern-web', 'stapk-capabilities.json'), '{"capabilities":{}}\n'),
    writeFile(path.join(transformOut, 'stapk-web-manifest.json'), '{"manifest":true}\n'),
    writeFile(path.join(transformOut, 'transform-report.json'), '{"ok":true}\n')
  ]);
}

test('build orchestrator runs strict gates in order and publishes six debug artifacts', async () => {
  await withTempProject(async (root) => {
    const calls = [];
    const nodeExecutable = path.basename(process.execPath);
    const runCommand = async ({ command, args, cwd }) => {
      calls.push([path.basename(command), ...args].join(' '));
      if (args.includes('scripts/stapk-transform-no-node.mjs')) {
        await createTransformArtifacts(root);
      }
      if (args.includes(':app:assembleDebug')) {
        const apk = path.join(root, 'mobile', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
        await mkdir(path.dirname(apk), { recursive: true });
        await writeFile(apk, 'debug apk');
      }
      assert.ok(cwd.startsWith(root));
    };

    const result = await buildNoNodeApk({
      variant: 'debug',
      ref: 'release',
      projectRoot: root,
      platform: 'win32',
      runCommand
    });

    assert.deepEqual(calls, [
      `${nodeExecutable} --test --test-concurrency=1 test/no-node/alpha.test.mjs test/no-node/zeta.test.mjs`,
      `${nodeExecutable} scripts/stapk-transform-no-node.mjs --ref release --out ${path.join(root, 'build', 'no-node-payload')} --android-assets ${path.join(root, 'mobile', 'app', 'src', 'main', 'assets')} --clean`,
      `${nodeExecutable} scripts/stapk-verify-no-node-transform.mjs --out ${path.join(root, 'build', 'no-node-payload')} --capabilities ${path.join(root, 'transform', 'no-node', 'capabilities.json')}`,
      `${nodeExecutable} scripts/stapk-verify-capability-contract.mjs --contract ${path.join(root, 'build', 'no-node-payload', 'api-contract.json')} --capabilities ${path.join(root, 'transform', 'no-node', 'capabilities.json')}`,
      'gradlew.bat --no-daemon :app:testDebugUnitTest',
      'gradlew.bat --no-daemon :app:assembleDebug'
    ]);
    assert.equal(result.variant, 'debug');
    assert.equal(result.artifacts.length, 6);
    assert.equal(await readFile(path.join(root, 'output', 'stapk-mobile-debug.apk'), 'utf8'), 'debug apk');
    assert.match(
      await readFile(path.join(root, 'output', 'stapk-mobile-debug.apk.sha256'), 'utf8'),
      /^[a-f0-9]{64}  stapk-mobile-debug\.apk\n$/
    );
    for (const name of [
      'api-contract.json',
      'stapk-capabilities.json',
      'stapk-web-manifest.json',
      'transform-report.json'
    ]) {
      await access(path.join(root, 'output', name));
    }
  });
});

test('build orchestrator preserves previous output when a strict gate fails', async () => {
  await withTempProject(async (root) => {
    const output = path.join(root, 'output');
    await mkdir(output, { recursive: true });
    await writeFile(path.join(output, 'stapk-mobile-debug.apk'), 'previous apk');
    const runCommand = async ({ args }) => {
      if (args.includes('scripts/stapk-transform-no-node.mjs')) await createTransformArtifacts(root);
      if (args.includes('scripts/stapk-verify-capability-contract.mjs')) throw new Error('strict capability failure');
    };

    await assert.rejects(
      buildNoNodeApk({
        variant: 'debug',
        ref: 'release',
        projectRoot: root,
        platform: 'win32',
        runCommand
      }),
      /strict capability failure/
    );

    assert.equal(await readFile(path.join(output, 'stapk-mobile-debug.apk'), 'utf8'), 'previous apk');
    await assert.rejects(access(path.join(output, 'stapk-mobile-debug.apk.sha256')));
    await assert.rejects(access(path.join(output, '.stapk-no-node-staging')));
  });
});

test('build orchestrator rejects unsafe variants and refs before running commands', async () => {
  let called = false;
  const runCommand = async () => { called = true; };

  await assert.rejects(buildNoNodeApk({ variant: 'profile', runCommand }), /debug 或 release/);
  await assert.rejects(buildNoNodeApk({ variant: 'debug', ref: '../release', runCommand }), /ref 非法/);
  assert.equal(called, false);
});

test('package workflows and LFS rules use the unified no-node build boundary', async () => {
  const [packageJson, ci, release, gradle, attributes] = await Promise.all([
    readFile(path.join(PROJECT_ROOT, 'package.json'), 'utf8').then(JSON.parse),
    readFile(path.join(PROJECT_ROOT, '.github', 'workflows', 'ci.yml'), 'utf8'),
    readFile(path.join(PROJECT_ROOT, '.github', 'workflows', 'release.yml'), 'utf8'),
    readFile(path.join(PROJECT_ROOT, 'mobile', 'app', 'build.gradle.kts'), 'utf8'),
    readFile(path.join(PROJECT_ROOT, '.gitattributes'), 'utf8')
  ]);
  const artifacts = [
    'stapk-mobile-',
    'api-contract.json',
    'stapk-capabilities.json',
    'stapk-web-manifest.json',
    'transform-report.json'
  ];

  assert.equal(packageJson.scripts['build:no-node-apk'], 'node scripts/stapk-build-no-node-apk.mjs');
  assert.match(ci, /npm run build:no-node-apk -- --variant debug --ref release/);
  assert.match(release, /npm run build:no-node-apk -- --variant release --ref "\$SILLYTAVERN_REF"/);
  assert.match(release, /SILLYTAVERN_REF:/);
  assert.match(release, /STAPK_VERSION_NAME=\$\{VERSION_NAME\}/);
  assert.match(release, /STAPK_VERSION_CODE=\$\{VERSION_CODE\}/);
  assert.match(release, /stapk-mobile_\$\{\{ github\.ref_name \}\}\.apk/);
  assert.doesNotMatch(release, /arm64-v8a/);
  assert.match(release, /prerelease: \$\{\{ contains\(github\.ref_name, '-'\) \}\}/);
  assert.match(gradle, /System\.getenv\("STAPK_VERSION_NAME"\)/);
  assert.ok(!gradle.includes('abiFilters'));
  for (const artifact of artifacts) {
    assert.ok(ci.includes(artifact), `CI missing artifact: ${artifact}`);
    assert.ok(release.includes(artifact), `Release missing artifact: ${artifact}`);
  }
  assert.ok(!attributes.includes('mobile/app/src/main/assets/payload.tgz'));
  assert.ok(!attributes.includes('mobile/app/src/main/assets/runtime-android-arm64-node'));
});
