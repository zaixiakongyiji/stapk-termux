import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';

import { classifyEndpoint, scanWebContract } from '../../scripts/stapk-scan-web-contract.mjs';

async function withTempWebRoot(fn) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'stapk-contract-'));
  try {
    await fn(root);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

test('classifyEndpoint maps exact, hidden prefix, and unknown endpoints', async () => {
  const allowlist = {
    implemented: [
      { method: 'GET', path: '/api/settings/get' },
      { method: 'POST', path: '/api/characters' }
    ],
    unsupportedHidden: [
      { prefix: '/api/extensions' }
    ]
  };

  assert.equal(classifyEndpoint('GET', '/api/settings/get', allowlist), 'implemented');
  assert.equal(classifyEndpoint('POST', '/api/characters', allowlist), 'implemented');
  assert.equal(classifyEndpoint('GET', '/api/extensions/status', allowlist), 'unsupported_hidden');
  assert.equal(classifyEndpoint('POST', '/api/unknown', allowlist), 'needs_review');
});

test('real MVP allowlist classifies native implemented endpoints', async () => {
  const allowlist = JSON.parse(await readFile(
    path.resolve('transform/no-node/mvp-api-allowlist.json'),
    'utf8'
  ));
  const implementedEndpoints = [
    'POST /api/settings/get',
    'POST /api/settings/save',
    'POST /api/characters/all',
    'POST /api/characters/create',
    'POST /api/characters/get',
    'POST /api/characters/edit',
    'POST /api/characters/delete',
    'POST /api/characters/chats',
    'POST /api/chats/get',
    'POST /api/chats/save',
    'POST /api/chats/delete',
    'POST /api/chats/search',
    'POST /api/groups/all',
    'POST /api/groups/create',
    'POST /api/groups/edit',
    'POST /api/groups/delete',
    'POST /api/chats/group/get',
    'POST /api/chats/group/save',
    'POST /api/chats/group/delete',
    'POST /api/chats/group/info',
    'POST /api/chats/group/import',
    'POST /api/secrets/read',
    'POST /api/secrets/write',
    'POST /api/secrets/delete',
    'POST /api/backends/chat-completions/status',
    'POST /api/backends/chat-completions/generate'
  ];

  for (const endpoint of implementedEndpoints) {
    const [method, apiPath] = endpoint.split(' ');
    assert.equal(classifyEndpoint(method, apiPath, allowlist), 'implemented', endpoint);
  }
});

test('scanner assigns group chat save to the implemented core groups capability', async () => {
  await withTempWebRoot(async (webRoot) => {
    await writeFile(
      path.join(webRoot, 'group.js'),
      'await fetch("/api/chats/group/save", { method: "POST" });\n',
      'utf8'
    );

    const contract = await scanWebContract({
      webRoot,
      allowlistFile: path.resolve('transform/no-node/mvp-api-allowlist.json'),
      capabilityFile: path.resolve('transform/no-node/capabilities.json'),
      upstream: { ref: 'test-ref' }
    });
    const endpoint = contract.endpoints.find((entry) => entry.path === '/api/chats/group/save');

    assert.ok(endpoint);
    assert.equal(endpoint.status, 'implemented');
    assert.equal(endpoint.capability, 'core.groups');
  });
});

test('real MVP allowlist keeps unsupported secrets management endpoints precise', async () => {
  const allowlist = JSON.parse(await readFile(
    path.resolve('transform/no-node/mvp-api-allowlist.json'),
    'utf8'
  ));
  const unsupportedSecretsEndpoints = [
    '/api/secrets/find',
    '/api/secrets/rename',
    '/api/secrets/rotate',
    '/api/secrets/settings',
    '/api/secrets/view'
  ];

  for (const apiPath of unsupportedSecretsEndpoints) {
    assert.equal(classifyEndpoint('POST', apiPath, allowlist), 'unsupported_hidden', apiPath);
  }

  assert.equal(classifyEndpoint('POST', '/api/secrets/unknown', allowlist), 'needs_review');
  assert.equal(classifyEndpoint('POST', '/api/chats/recent', allowlist), 'implemented');
});

test('scanWebContract extracts fetch API calls from built Web assets', async () => {
  await withTempWebRoot(async (webRoot) => {
    await mkdir(path.join(webRoot, 'assets'), { recursive: true });
    await writeFile(
      path.join(webRoot, 'index.html'),
      '<script>fetch("/api/settings/get", { method: "POST" })</script>\n',
      'utf8'
    );
    await writeFile(
      path.join(webRoot, 'assets', 'app.js'),
      [
        'await fetch("/api/characters/all", { method: "POST" });',
        'await fetch("/api/extensions/status");',
        'await fetch("/api/settings/get", { method: "POST" });'
      ].join('\n'),
      'utf8'
    );

    const contract = await scanWebContract({
      webRoot,
      allowlistFile: path.resolve('transform/no-node/mvp-api-allowlist.json'),
      upstream: {
        ref: 'test-ref',
        commit: 'test-commit',
        version: 'test-version'
      }
    });

    const endpointKey = (endpoint) => `${endpoint.method} ${endpoint.path}`;
    const byKey = new Map(contract.endpoints.map((endpoint) => [endpointKey(endpoint), endpoint]));

    assert.equal(contract.schemaVersion, 1);
    assert.equal(contract.upstream.ref, 'test-ref');
    assert.equal(byKey.get('POST /api/settings/get').status, 'implemented');
    assert.equal(byKey.get('POST /api/characters/all').status, 'implemented');
    assert.equal(byKey.get('GET /api/extensions/status').status, 'needs_review');
    assert.deepEqual(byKey.get('POST /api/settings/get').sourceFiles, [
      'assets/app.js',
      'index.html'
    ]);
    assert.equal(contract.summary.implemented, 2);
    assert.equal(contract.summary.unsupported_hidden, 0);
    assert.equal(contract.summary.needs_review, 1);
  });
});
