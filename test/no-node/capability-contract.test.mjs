import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import { Worker } from 'node:worker_threads';
import semver from 'semver';

import {
  extractApiLiterals,
  extractFetchRequests,
  scanWebContract
} from '../../scripts/stapk-scan-web-contract.mjs';
import {
  isCapabilityVerificationAllowed,
  verifyCapabilityContract
} from '../../scripts/stapk-verify-capability-contract.mjs';

const PROJECT_CAPABILITIES = JSON.parse(await readFile(
  path.resolve('transform/no-node/capabilities.json'),
  'utf8'
));
const BUILD_PARSER_ROOTS = [
  'acorn',
  'linkedom',
  'parse5',
  'postcss',
  'postcss-selector-parser',
];

async function withTempDirectory(prefix, fn) {
  const directory = await mkdtemp(path.join(os.tmpdir(), prefix));
  try {
    await fn(directory);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

function verifyBuildParserLockfile({ packageJson, packageLock }) {
  const rootPackage = packageLock.packages[''];
  const parse5Package = packageLock.packages['node_modules/parse5'];
  const entitiesPackage = packageLock.packages['node_modules/entities'];
  const linkedomPackage = packageLock.packages['node_modules/linkedom'];
  const cssSelectPackage = packageLock.packages['node_modules/css-select'];

  assert.equal(packageJson.engines.node, '>=20.0.0');
  assert.ok(semver.satisfies('20.0.0', packageJson.engines.node));
  assert.equal(packageJson.devDependencies.parse5, '7.3.0');
  assert.equal(packageJson.devDependencies.linkedom, '0.18.12');
  assert.equal(packageJson.devDependencies.semver, '7.7.3');
  assert.equal(rootPackage.devDependencies.parse5, '7.3.0');
  assert.equal(rootPackage.devDependencies.linkedom, '0.18.12');
  assert.equal(rootPackage.devDependencies.semver, '7.7.3');
  assert.equal(parse5Package.version, '7.3.0');
  assert.equal(parse5Package.dev, true);
  assert.equal(parse5Package.dependencies.entities, '^6.0.0');
  assert.ok(Number.parseInt(entitiesPackage.version, 10) < 8, entitiesPackage.version);
  assert.equal(linkedomPackage.version, '0.18.12');
  assert.equal(linkedomPackage.dependencies['css-select'], '^5.1.0');
  assert.ok(Number.parseInt(cssSelectPackage.version, 10) < 7, cssSelectPackage.version);

  const visited = new Set();
  const queue = BUILD_PARSER_ROOTS.map((name) => ({ name, requesterPath: '' }));
  while (queue.length > 0) {
    const { name, requesterPath } = queue.shift();
    const packagePath = resolveLockfileDependencyPath(
      packageLock.packages,
      requesterPath,
      name
    );
    assert.ok(
      packagePath,
      `${name} dependency node is missing (required by ${requesterPath || 'lockfile root'})`
    );
    if (visited.has(packagePath)) continue;
    visited.add(packagePath);

    const packageRecord = packageLock.packages[packagePath];
    const engineRange = packageRecord.engines?.node;
    if (engineRange) {
      assert.ok(
        semver.satisfies('20.0.0', engineRange),
        `${packagePath} engines.node "${engineRange}" does not accept Node 20.0.0`
      );
    }
    for (const dependencyName of runtimeDependencyNames(packageRecord)) {
      queue.push({ name: dependencyName, requesterPath: packagePath });
    }
  }
}

function runtimeDependencyNames(packageRecord) {
  const names = new Set([
    ...Object.keys(packageRecord.dependencies ?? {}),
    ...Object.keys(packageRecord.optionalDependencies ?? {}),
  ]);
  for (const peerName of Object.keys(packageRecord.peerDependencies ?? {})) {
    if (packageRecord.peerDependenciesMeta?.[peerName]?.optional !== true) {
      names.add(peerName);
    }
  }
  return names;
}

function resolveLockfileDependencyPath(packages, requesterPath, dependencyName) {
  let searchPath = requesterPath;
  while (true) {
    const candidate = searchPath
      ? `${searchPath}/node_modules/${dependencyName}`
      : `node_modules/${dependencyName}`;
    if (Object.hasOwn(packages, candidate)) return candidate;
    if (!searchPath) return null;
    searchPath = parentLockfilePackagePath(searchPath);
  }
}

function parentLockfilePackagePath(packagePath) {
  return packagePath.replace(
    /(?:^|\/)node_modules\/(?:@[^/]+\/)?[^/]+$/,
    ''
  );
}

test('build parser lockfile preserves the Node 20.0 dependency contract', async () => {
  const packageJson = JSON.parse(await readFile(path.resolve('package.json'), 'utf8'));
  const packageLock = JSON.parse(await readFile(path.resolve('package-lock.json'), 'utf8'));

  verifyBuildParserLockfile({ packageJson, packageLock });
});

test('build parser lockfile rejects a deep dependency engine incompatible with Node 20.0', async () => {
  const packageJson = JSON.parse(await readFile(path.resolve('package.json'), 'utf8'));
  const packageLock = JSON.parse(await readFile(path.resolve('package-lock.json'), 'utf8'));
  packageLock.packages['node_modules/dom-serializer'].engines = { node: '>=20.19.0' };

  assert.throws(
    () => verifyBuildParserLockfile({ packageJson, packageLock }),
    /dom-serializer.*20\.0\.0/
  );
});

test('build parser lockfile rejects a missing deep dependency node', async () => {
  const packageJson = JSON.parse(await readFile(path.resolve('package.json'), 'utf8'));
  const packageLock = JSON.parse(await readFile(path.resolve('package-lock.json'), 'utf8'));
  delete packageLock.packages['node_modules/dom-serializer'];

  assert.throws(
    () => verifyBuildParserLockfile({ packageJson, packageLock }),
    /dom-serializer.*missing/
  );
});

test('scanWebContract discovers API literals from constants and template strings', async () => {
  await withTempDirectory('stapk-capability-contract-', async (root) => {
    const webRoot = path.join(root, 'web');
    const allowlistFile = path.join(root, 'allowlist.json');
    const capabilityFile = path.join(root, 'capabilities.json');
    await mkdir(webRoot, { recursive: true });
    await writeFile(
      path.join(webRoot, 'tokenizers.js'),
      [
        'const endpoints = {',
        "  encode: '/api/tokenizers/openai/encode',",
        "  decode: '/api/tokenizers/openai/decode',",
        '};',
        'const countUrl = `/api/tokenizers/openai/count?model=${model}`;',
        "await fetch(endpoints.encode, { method: 'POST', body: '{}' });"
      ].join('\n'),
      'utf8'
    );
    await writeFile(allowlistFile, JSON.stringify({ implemented: [], unsupportedHidden: [] }), 'utf8');
    await writeFile(
      capabilityFile,
      JSON.stringify({
        capabilities: [{
          id: 'core.tokenizers',
          kind: 'core',
          defaultStatus: 'needs_review',
          endpointPrefixes: ['/api/tokenizers'],
          uiPolicy: 'visible_when_implemented'
        }]
      }),
      'utf8'
    );

    const contract = await scanWebContract({
      webRoot,
      allowlistFile,
      capabilityFile,
      upstream: { ref: 'test-ref' }
    });
    const endpoints = new Map(contract.endpoints.map((endpoint) => [endpoint.path, endpoint]));

    assert.deepEqual([...endpoints.keys()].sort(), [
      '/api/tokenizers/openai/count',
      '/api/tokenizers/openai/decode',
      '/api/tokenizers/openai/encode'
    ]);
    assert.deepEqual(endpoints.get('/api/tokenizers/openai/count').sourceLocations, [{
      file: 'tokenizers.js',
      line: 5,
      expression: '/api/tokenizers/openai/count?model=${model}'
    }]);
    assert.equal(endpoints.get('/api/tokenizers/openai/count').dynamic, true);
    assert.equal(endpoints.get('/api/tokenizers/openai/encode').inferredMethod, true);
    assert.equal(endpoints.get('/api/tokenizers/openai/encode').capability, 'core.tokenizers');
    assert.equal(endpoints.get('/api/tokenizers/openai/encode').exposure, 'visible_when_implemented');
  });
});

test('extractApiLiterals ignores comments and tracks literal lines in one pass', () => {
  const source = [
    "// const ignored = '/api/not-real';",
    "const escaped = '/api/characters/it\\'s-valid';",
    '/* `/api/also-not-real` */',
    'const dynamic = `/api/chats/${chatId}?page=${page}`;'
  ].join('\n');

  assert.deepEqual(extractApiLiterals(source, 'fixtures/literals.js'), [
    {
      path: "/api/characters/it's-valid",
      expression: "/api/characters/it\\'s-valid",
      line: 2,
      dynamic: false,
      sourceFile: 'fixtures/literals.js'
    },
    {
      path: '/api/chats/{dynamic}',
      expression: '/api/chats/${chatId}?page=${page}',
      line: 4,
      dynamic: true,
      sourceFile: 'fixtures/literals.js'
    }
  ]);
});

test('extractApiLiterals ignores debug messages and external URL pathnames', () => {
  const source = [
    "console.debug('/api/chats/save called by /Generate');",
    "const external = 'https://openrouter.ai/api/v1/auth/keys';"
  ].join('\n');

  assert.deepEqual(extractApiLiterals(source, 'fixtures/debug.js'), []);
});

test('extractApiLiterals skips regex literals at top level and inside templates', () => {
  const source = [
    String.raw`const matcher = /[//]/; const settings = '/api/settings/get';`,
    String.raw`const escaped = /https?:\/\/host/; const files = '/api/files/verify';`,
    'const chat = `/api/chats/${/[//]/.test(value)&&chatId}`;'
  ].join('\n');

  assert.deepEqual(
    extractApiLiterals(source, 'fixtures/regex.js').map(({ path: apiPath }) => apiPath),
    [
      '/api/settings/get',
      '/api/files/verify',
      '/api/chats/{dynamic}'
    ]
  );
});

test('extractApiLiterals handles regex after catch, class, and labeled blocks', () => {
  const source = [
    String.raw`try { run(); } catch {} /[//]/.test(value); const settings = '/api/settings/get';`,
    String.raw`class Example { method() {} } /[//]/.test(value); const files = '/api/files/verify';`,
    String.raw`retry: { break retry; } /[//]/.test(value); const chats = '/api/chats/get';`
  ].join('\n');

  assert.deepEqual(
    extractApiLiterals(source, 'fixtures/statement-regex.js').map(({ path: apiPath }) => apiPath),
    ['/api/settings/get', '/api/files/verify', '/api/chats/get']
  );
});

test('extractApiLiterals normalizes dynamic templates from quasis and decodes escaped API prefixes', () => {
  const source = [
    'const dynamic = `/api/chats/${condition ? left : right}/messages#fragment`;',
    'const query = `/api/tokenizers/${kind}?model=${model}`;',
    String.raw`const escaped = '\/api/settings/get';`,
    String.raw`const hex = '\x2fapi/characters/all';`,
    String.raw`const unicode = '\u002fapi/chats/get';`,
    String.raw`const codePoint = '\u{2f}api/groups/all';`
  ].join('\n');

  assert.deepEqual(
    extractApiLiterals(source, 'fixtures/escaped.js').map(({ path: apiPath }) => apiPath),
    [
      '/api/chats/{dynamic}/messages',
      '/api/tokenizers/{dynamic}',
      '/api/settings/get',
      '/api/characters/all',
      '/api/chats/get',
      '/api/groups/all'
    ]
  );

  const [dynamic] = extractApiLiterals(source, 'fixtures/escaped.js');
  assert.equal(dynamic.expression, '/api/chats/${condition ? left : right}/messages#fragment');
  assert.equal(dynamic.dynamic, true);
});

test('extractFetchRequests balances nested options and ignores delimiter text', () => {
  const source = [
    "await fetch('/api/files/delete', {",
    "  headers: { nested: { marker: '})' } },",
    '  template: `})`,',
    '  matcher: /[})]/,',
    "  method: 'DELETE'",
    '});',
    "fetch('/api/settings/get', { note: '})', method: 'PATCH' });",
    "fetch('/api/settings/save', saveSettingsRequest);"
  ].join('\n');

  assert.deepEqual(extractFetchRequests(source), [
    { path: '/api/files/delete', method: 'DELETE' },
    { path: '/api/settings/get', method: 'PATCH' }
  ]);
});

test('extractFetchRequests resolves complete static method keys without inventing dynamic methods', () => {
  const source = [
    "fetch('/api/files/identifier', { method: 'PATCH' });",
    "fetch('/api/files/quoted', { 'method': 'DELETE' });",
    "fetch('/api/files/computed', { ['method']: 'PUT' });",
    "fetch('/api/files/dynamic', { [methodKey]: 'POST' });"
  ].join('\n');

  assert.deepEqual(extractFetchRequests(source), [
    { path: '/api/files/identifier', method: 'PATCH' },
    { path: '/api/files/quoted', method: 'DELETE' },
    { path: '/api/files/computed', method: 'PUT' }
  ]);
});

test('extractFetchRequests applies object overwrite semantics to method evidence', () => {
  const source = [
    "fetch('/api/files/duplicate', { method: 'POST', method: 'DELETE' });",
    "fetch('/api/files/spread-before', { ...defaults, method: 'PATCH' });",
    "fetch('/api/files/dynamic-before', { [methodKey]: 'POST', method: 'PUT' });",
    "fetch('/api/files/spread-after', { method: 'PATCH', ...defaults });",
    "fetch('/api/files/dynamic-after', { method: 'PATCH', [methodKey]: 'POST' });",
    "fetch('/api/files/no-method', { headers: {} });",
    "fetch('/api/files/no-options');"
  ].join('\n');

  assert.deepEqual(extractFetchRequests(source), [
    { path: '/api/files/duplicate', method: 'DELETE' },
    { path: '/api/files/spread-before', method: 'PATCH' },
    { path: '/api/files/dynamic-before', method: 'PUT' },
    { path: '/api/files/no-method', method: 'GET' },
    { path: '/api/files/no-options', method: 'GET' }
  ]);
});

test('extractFetchRequests keeps method evidence for dynamic template URLs', () => {
  const source = [
    'fetch(`/api/chats/${chatId}`);',
    "fetch(`/api/files/${fileId}?download=${download}`, { method: 'POST' });"
  ].join('\n');

  assert.deepEqual(extractFetchRequests(source), [
    { path: '/api/chats/{dynamic}', method: 'GET' },
    { path: '/api/files/{dynamic}', method: 'POST' }
  ]);
});

test('scanWebContract uses HTML parsing rules and preserves mixed-newline source locations', async () => {
  await withTempDirectory('stapk-html-contract-', async (root) => {
    const webRoot = path.join(root, 'web');
    const allowlistFile = path.join(root, 'allowlist.json');
    await mkdir(webRoot, { recursive: true });
    await writeFile(allowlistFile, JSON.stringify({ implemented: [], unsupportedHidden: [] }), 'utf8');
    await writeFile(path.join(webRoot, 'index.html'), [
      '<!-- <script>const ignoredComment = "/api/ignored/comment";</script> -->\r',
      '<template><script>const ignoredTemplate = "/api/ignored/template";</script></template>\r\n',
      '<script src>const ignoredBooleanSrc = "/api/ignored/src";</script>\n',
      '<script type="application/json">{"path":"/api/ignored/json"}</script>\r',
      '<script type="text/ecmascript">const ignoredMime = "/api/ignored/mime";</script>\r\n',
      '<script type=" MoDuLe ; charset=utf-8 ">const ignoredModuleParameters = "/api/ignored/module-parameters";</script>\n',
      '<script type=" Text/JavaScript ; Charset=UTF-8 ">const classic = "/api/chats/get";</script>\r',
      '<script data-marker=">">const first = "/api/settings/get";</script>\n',
      '<script type="module">const second = `/api/chats/${chatId}`;</script>'
    ].join(''), 'utf8');

    const contract = await scanWebContract({
      webRoot,
      allowlistFile,
      upstream: { ref: 'html-test' }
    });

    assert.deepEqual(contract.endpoints.map((endpoint) => endpoint.path), [
      '/api/chats/{dynamic}',
      '/api/chats/get',
      '/api/settings/get'
    ]);
    assert.deepEqual(
      contract.endpoints.map((endpoint) => endpoint.sourceLocations[0].line),
      [9, 7, 8]
    );

    await writeFile(
      path.join(webRoot, 'broken.html'),
      '\r<script data-marker=">">const broken = ;</script>',
      'utf8'
    );
    await assert.rejects(
      scanWebContract({ webRoot, allowlistFile, upstream: { ref: 'html-error-test' } }),
      (error) => {
        assert.match(error.message, /broken\.html/);
        assert.match(error.message, /\(2:39\)/);
        return true;
      }
    );
  });
});

test('scanWebContract selects source type by extension and falls back for js scripts', async () => {
  await withTempDirectory('stapk-source-type-', async (root) => {
    const webRoot = path.join(root, 'web');
    const allowlistFile = path.join(root, 'allowlist.json');
    await mkdir(webRoot, { recursive: true });
    await writeFile(allowlistFile, JSON.stringify({ implemented: [], unsupportedHidden: [] }), 'utf8');
    await writeFile(path.join(webRoot, 'module.mjs'), "export const api = '/api/settings/get';", 'utf8');
    await writeFile(path.join(webRoot, 'common.cjs'), "with ({}) { const api = '/api/chats/get'; }", 'utf8');
    await writeFile(path.join(webRoot, 'fallback.js'), "with ({}) { const api = '/api/files/list'; }", 'utf8');

    const contract = await scanWebContract({
      webRoot,
      allowlistFile,
      upstream: { ref: 'source-type-test' }
    });

    assert.deepEqual(contract.endpoints.map((endpoint) => endpoint.path), [
      '/api/chats/get',
      '/api/files/list',
      '/api/settings/get'
    ]);
  });
});

test('scanWebContract reports source file and module/script parse errors', async () => {
  await withTempDirectory('stapk-parse-error-', async (root) => {
    const webRoot = path.join(root, 'web');
    const allowlistFile = path.join(root, 'allowlist.json');
    await mkdir(webRoot, { recursive: true });
    await writeFile(allowlistFile, JSON.stringify({ implemented: [], unsupportedHidden: [] }), 'utf8');
    await writeFile(path.join(webRoot, 'broken.js'), "const broken = '/api/settings/get", 'utf8');

    await assert.rejects(
      scanWebContract({ webRoot, allowlistFile, upstream: { ref: 'parse-error-test' } }),
      (error) => {
        assert.match(error.message, /broken\.js/);
        assert.match(error.message, /module:/);
        assert.match(error.message, /script:/);
        return true;
      }
    );
  });
});

test('complete scanner scans regression assets without hanging', { timeout: 15_000 }, async () => {
  const webRoot = path.resolve('mobile/app/src/main/assets/sillytavern-web');
  const assetFiles = [
    'scripts/extensions/gallery/jquery.nanogallery2.min.js',
    'scripts/extensions/tts/index.js',
    'script.js'
  ].map((file) => path.join(webRoot, ...file.split('/')));
  const timings = await scanAssetsInWorker(assetFiles, 10_000);

  assert.equal(timings.length, assetFiles.length);
  for (const timing of timings) {
    assert.ok(timing.elapsedMs < 5_000, `${timing.file} took ${timing.elapsedMs.toFixed(2)}ms`);
  }
});

async function scanAssetsInWorker(files, timeoutMs) {
  const workerSource = `
    const { parentPort, workerData } = require('node:worker_threads');
    const { readFile } = require('node:fs/promises');
    const { performance } = require('node:perf_hooks');
    (async () => {
      const { extractEndpointEvidence } = await import(workerData.scannerUrl);
      const timings = [];
      for (const file of workerData.files) {
        const source = await readFile(file, 'utf8');
        const startedAt = performance.now();
        extractEndpointEvidence(source, file);
        timings.push({ file, elapsedMs: performance.now() - startedAt });
      }
      parentPort.postMessage(timings);
    })().catch((error) => { throw error; });
  `;
  const worker = new Worker(workerSource, {
    eval: true,
    workerData: {
      files,
      scannerUrl: new URL('../../scripts/stapk-scan-web-contract.mjs', import.meta.url).href
    }
  });

  return await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      void worker.terminate();
      reject(new Error(`Asset scanner exceeded ${timeoutMs}ms regression budget`));
    }, timeoutMs);
    worker.once('message', (message) => {
      clearTimeout(timer);
      resolve(message);
    });
    worker.once('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    worker.once('exit', (code) => {
      if (code !== 0) {
        clearTimeout(timer);
        reject(new Error(`Asset scanner worker exited with code ${code}`));
      }
    });
  });
}

function createCapabilities() {
  return structuredClone(PROJECT_CAPABILITIES);
}

function endpoint(method, apiPath, status, capability, exposure) {
  return { method, path: apiPath, status, capability, exposure };
}

test('verifyCapabilityContract reports visible needs_review without hiding other valid endpoints', () => {
  const result = verifyCapabilityContract({
    apiContract: {
      endpoints: [
        endpoint('POST', '/api/settings/get', 'needs_review', 'core.settings', 'visible_when_implemented'),
        endpoint('POST', '/api/openai/generate-image', 'external_optional', 'remote.image', 'visible_when_configured'),
        endpoint('POST', '/api/extensions/move', 'unsupported_hidden', 'excluded.extensions', 'hidden')
      ]
    },
    capabilities: createCapabilities()
  });

  assert.equal(result.ok, false);
  assert.deepEqual(result.visibleNeedsReview, ['POST /api/settings/get']);
  assert.deepEqual(result.unassignedEndpoints, []);
  assert.match(result.errors.join('\n'), /Visible endpoint still needs review: POST \/api\/settings\/get/);
  assert.deepEqual(result.summaryByCapability, {
    'core.settings': { needs_review: 1 },
    'excluded.extensions': { unsupported_hidden: 1 },
    'remote.image': { external_optional: 1 }
  });
});

test('verifyCapabilityContract rejects unassigned, overlapping, and illegal endpoint states', () => {
  const capabilities = createCapabilities();
  capabilities.capabilities.push({
    id: 'core.invalid-remote-kind',
    kind: 'external_optional',
    defaultStatus: 'external_optional',
    endpointPrefixes: ['/api/invalid-remote'],
    uiPolicy: 'visible_when_configured'
  });
  capabilities.capabilities
    .find((capability) => capability.id === 'core.characters')
    .endpointPrefixes.push('/api/settings/get');

  const result = verifyCapabilityContract({
    apiContract: {
      endpoints: [
        endpoint('POST', '/api/unassigned', 'needs_review', null, null),
        endpoint('POST', '/api/settings/get', 'external_optional', 'core.settings', 'visible_when_implemented'),
        endpoint('POST', '/api/settings/save', 'external_optional', 'core.settings', 'visible_when_implemented'),
        endpoint('POST', '/api/settings/legacy', 'unsupported_hidden', 'core.settings', 'visible_when_implemented'),
        endpoint('POST', '/api/extensions/move', 'implemented', 'excluded.extensions', 'hidden')
      ]
    },
    capabilities
  });

  assert.equal(result.ok, false);
  assert.deepEqual(result.unassignedEndpoints, ['POST /api/unassigned']);
  assert.match(result.errors.join('\n'), /Endpoint has no capability: POST \/api\/unassigned/);
  assert.match(result.errors.join('\n'), /Endpoint has multiple capabilities: POST \/api\/settings\/get/);
  assert.match(result.errors.join('\n'), /Core capability cannot be external_optional: POST \/api\/settings\/save/);
  assert.match(result.errors.join('\n'), /Visible-when-implemented capability cannot be unsupported_hidden: POST \/api\/settings\/legacy/);
  assert.match(result.errors.join('\n'), /Excluded capability cannot be implemented: POST \/api\/extensions\/move/);
  assert.match(result.errors.join('\n'), /External optional capability id must use remote prefix: core\.invalid-remote-kind/);
});

test('verifyCapabilityContract rejects endpoint declaration mismatches and does not exempt them', () => {
  const result = verifyCapabilityContract({
    apiContract: {
      endpoints: [
        endpoint('POST', '/api/settings/get', 'needs_review', 'excluded.extensions', 'hidden')
      ]
    },
    capabilities: createCapabilities()
  });

  assert.match(result.errors.join('\n'), /Endpoint capability declaration mismatch: POST \/api\/settings\/get/);
  assert.match(result.errors.join('\n'), /Endpoint exposure declaration mismatch: POST \/api\/settings\/get/);
  assert.equal(isCapabilityVerificationAllowed(result, true), false);
});

test('verifyCapabilityContract rejects unknown and missing fixed capability ids', () => {
  const capabilities = createCapabilities();
  capabilities.capabilities.find((capability) => capability.id === 'core.settings').id = 'core.custom';
  const result = verifyCapabilityContract({
    apiContract: {
      endpoints: [
        endpoint('POST', '/api/settings/get', 'needs_review', 'core.custom', 'visible_when_implemented')
      ]
    },
    capabilities
  });

  assert.match(result.errors.join('\n'), /Unknown capability id: core\.custom/);
  assert.match(result.errors.join('\n'), /Missing capability id: core\.settings/);
  assert.equal(isCapabilityVerificationAllowed(result, true), false);
});

test('allow-visible-needs-review never allows structural or illegal-state errors', async () => {
  await withTempDirectory('stapk-capability-cli-', async (root) => {
    const capabilitiesFile = path.join(root, 'capabilities.json');
    const contractFile = path.join(root, 'api-contract.json');
    await writeFile(capabilitiesFile, JSON.stringify(createCapabilities()), 'utf8');

    await writeFile(contractFile, JSON.stringify({
      endpoints: [endpoint('POST', '/api/settings/get', 'needs_review', 'core.settings', 'visible_when_implemented')]
    }), 'utf8');
    assert.equal(runCapabilityVerifier(contractFile, capabilitiesFile).status, 0);

    await writeFile(contractFile, JSON.stringify({
      endpoints: [
        endpoint('POST', '/api/settings/get', 'needs_review', 'core.settings', 'visible_when_implemented'),
        endpoint('POST', '/api/unassigned', 'needs_review', null, null)
      ]
    }), 'utf8');
    const unassigned = runCapabilityVerifier(contractFile, capabilitiesFile);
    assert.notEqual(unassigned.status, 0);
    assert.match(unassigned.stderr, /Endpoint has no capability/);

    await writeFile(contractFile, JSON.stringify({
      endpoints: [endpoint('POST', '/api/settings/save', 'external_optional', 'core.settings', 'visible_when_implemented')]
    }), 'utf8');
    const illegalState = runCapabilityVerifier(contractFile, capabilitiesFile);
    assert.notEqual(illegalState.status, 0);
    assert.match(illegalState.stderr, /Core capability cannot be external_optional/);
  });
});

test('capabilities explicitly assign every bundled endpoint exactly once', { timeout: 15_000 }, async () => {
  const capabilities = JSON.parse(await readFile(
    path.resolve('transform/no-node/capabilities.json'),
    'utf8'
  ));
  const contract = await scanWebContract({
    webRoot: path.resolve('mobile/app/src/main/assets/sillytavern-web'),
    allowlistFile: path.resolve('transform/no-node/mvp-api-allowlist.json'),
    capabilityFile: path.resolve('transform/no-node/capabilities.json'),
    upstream: { ref: 'bundled-assets' }
  });
  const result = verifyCapabilityContract({ apiContract: contract, capabilities });
  const structuralErrors = result.errors.filter((error) =>
    !error.startsWith('Visible endpoint still needs review:')
  );

  assert.ok(contract.endpoints.length > 300, `Expected full Web contract, got ${contract.endpoints.length}`);
  assert.ok(contract.summary.external_optional > 0);
  assert.equal(
    Object.values(contract.summary).reduce((total, count) => total + count, 0),
    contract.endpoints.length
  );
  assert.deepEqual(result.unassignedEndpoints, []);
  assert.deepEqual(structuralErrors, []);
  assert.equal(
    capabilities.capabilities
      .filter((capability) => capability.kind === 'excluded')
      .flatMap((capability) => capability.endpointPrefixes)
      .includes('/api'),
    false
  );
});

function runCapabilityVerifier(contractFile, capabilitiesFile) {
  return spawnSync(process.execPath, [
    path.resolve('scripts/stapk-verify-capability-contract.mjs'),
    '--contract', contractFile,
    '--capabilities', capabilitiesFile,
    '--allow-visible-needs-review'
  ], {
    cwd: path.resolve(),
    encoding: 'utf8',
    timeout: 5_000
  });
}
