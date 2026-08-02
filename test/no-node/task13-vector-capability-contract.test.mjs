import assert from 'node:assert/strict';
import Ajv2020 from 'ajv/dist/2020.js';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { verifyCapabilityContract } from '../../scripts/stapk-verify-capability-contract.mjs';
import {
  validateUiCapabilityContract,
  verifyUiCapabilityContract,
} from '../../scripts/stapk-verify-ui-capability-contract.mjs';

const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

const [capabilities, uiContract, allowlist] = await Promise.all([
  readJson('transform/no-node/capabilities.json'),
  readJson('transform/no-node/ui-capabilities.json'),
  readJson('transform/no-node/mvp-api-allowlist.json'),
]);
const capabilitySchema = await readJson('transform/schemas/capability-contract.schema.json');

function readJson(relativePath) {
  return readFile(path.join(PROJECT_ROOT, relativePath), 'utf8').then(JSON.parse);
}

test('embedding is the sole runtime-available remote capability and owns its controlled routes', () => {
  const embedding = capabilities.capabilities.find(({ id }) => id === 'remote.embeddings');
  assert.deepEqual(embedding, {
    id: 'remote.embeddings',
    kind: 'external_optional',
    defaultStatus: 'external_optional',
    endpointPrefixes: [
      '/api/openai/chutes/models/embedding',
      '/api/openai/nanogpt/models/embedding',
      '/api/openai/siliconflow/models/embedding',
      '/api/openai/workers-ai/models/embedding',
      '/api/openrouter/models/embedding',
      '/api/vector',
      '/api/stapk/embeddings',
    ],
    uiPolicy: 'visible_when_configured',
    runtimeAvailable: true,
  });

  const availableRemoteIds = capabilities.capabilities
    .filter(({ kind, runtimeAvailable }) => kind === 'external_optional' && runtimeAvailable === true)
    .map(({ id }) => id);
  assert.deepEqual(availableRemoteIds, ['remote.embeddings']);

  const implementedEndpoints = new Set(
    allowlist.implemented.map(({ method, path: endpointPath }) => `${method} ${endpointPath}`)
  );
  for (const endpoint of [
    'POST /api/vector/list',
    'POST /api/vector/insert',
    'POST /api/vector/delete',
    'POST /api/vector/query',
    'POST /api/vector/query-multi',
    'POST /api/vector/purge',
    'POST /api/vector/purge-all',
    'POST /api/stapk/embeddings/config/get',
    'POST /api/stapk/embeddings/config/save',
    'POST /api/stapk/embeddings/test',
  ]) {
    assert.equal(implementedEndpoints.has(endpoint), true, `missing allowlisted endpoint ${endpoint}`);
  }
});

test('capability schema accepts the complete 20-item contract and rejects an unknown id', () => {
  const validate = new Ajv2020({ strict: false }).compile(capabilitySchema);
  assert.equal(validate(capabilities), true, JSON.stringify(validate.errors));

  const unknownId = structuredClone(capabilities);
  unknownId.capabilities.find(({ id }) => id === 'remote.embeddings').id = 'remote.unreviewed';
  assert.equal(validate(unknownId), false);
  assert.match(JSON.stringify(validate.errors), /capabilityId|enum/);
});

test('configured Vector Storage action is a visible configured capability, not an implemented core action', () => {
  const action = uiContract.configuredActions?.find(({ name }) => name === 'Vector Storage');
  assert.deepEqual(action, {
    name: 'Vector Storage',
    selector: '#vectors_container',
    capability: 'remote.embeddings',
    endpoint: 'POST /api/vector/query',
    source: { type: 'html', path: 'index.html' },
  });
  assert.equal(
    uiContract.hiddenSelectors.some(({ selector }) => selector === '#vectors_container'),
    false
  );
  assert.equal(
    uiContract.implementedActions.some(({ selector }) => selector === '#vectors_container'),
    false
  );
});

test('validators fail closed when a configured action uses an unavailable remote capability', () => {
  const fixtureCapabilities = {
    schemaVersion: 1,
    capabilities: [
      {
        id: 'remote.fixture',
        kind: 'external_optional',
        defaultStatus: 'external_optional',
        endpointPrefixes: ['/api/vector'],
        uiPolicy: 'visible_when_configured',
      },
    ],
  };
  const fixtureUiContract = {
    schemaVersion: 1,
    hiddenStylesheets: [],
    implementedActions: [],
    configuredActions: [
      {
        name: 'Fixture vectors',
        selector: '#vectors',
        capability: 'remote.fixture',
        endpoint: 'POST /api/vector/query',
        source: { type: 'html', path: 'index.html' },
      },
    ],
    hiddenSelectors: [],
  };

  assert.match(
    validateUiCapabilityContract({ uiContract: fixtureUiContract, capabilities: fixtureCapabilities }).join('\n'),
    /runtimeAvailable/
  );

  const illegalCore = structuredClone(capabilities);
  illegalCore.capabilities.find(({ id }) => id === 'core.settings').runtimeAvailable = true;
  const result = verifyCapabilityContract({ apiContract: { endpoints: [] }, capabilities: illegalCore });
  assert.match(result.errors.join('\n'), /runtimeAvailable/);
});

test('capability verifier rejects unknown fields and non-external runtimeAvailable declarations', () => {
  const malformed = structuredClone(capabilities);
  const embedding = malformed.capabilities.find(({ id }) => id === 'remote.embeddings');
  embedding.unrecognized = true;
  embedding.kind = 'core';
  const result = verifyCapabilityContract({ apiContract: { endpoints: [] }, capabilities: malformed });

  assert.match(result.errors.join('\n'), /unknown field.*unrecognized/i);
  assert.match(result.errors.join('\n'), /runtimeAvailable.*external_optional/);
});

test('configured actions require an implemented endpoint and a selector that CSS does not hide', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'stapk-vector-ui-contract-'));
  const webRoot = path.join(root, 'web');
  const uiContractFile = path.join(webRoot, 'stapk-ui-capabilities.json');
  const apiContractFile = path.join(root, 'api-contract.json');
  const capabilityFile = path.join(root, 'capabilities.json');
  const ui = {
    schemaVersion: 1,
    hiddenStylesheets: [{ path: 'css/main.css', catalogBefore: '#catalog-end' }],
    implementedActions: [],
    configuredActions: [
      {
        name: 'Fixture vectors',
        selector: '#vectors',
        capability: 'remote.embeddings',
        endpoint: 'POST /api/vector/query',
        source: { type: 'html', path: 'index.html' },
      },
    ],
    hiddenSelectors: [],
  };
  const api = {
    schemaVersion: 1,
    endpoints: [
      {
        method: 'POST',
        path: '/api/vector/query',
        status: 'implemented',
        capability: 'remote.embeddings',
      },
    ],
  };
  const remoteCapabilities = {
    schemaVersion: 1,
    capabilities: [
      {
        id: 'remote.embeddings',
        kind: 'external_optional',
        defaultStatus: 'external_optional',
        endpointPrefixes: ['/api/vector'],
        uiPolicy: 'visible_when_configured',
        runtimeAvailable: true,
      },
    ],
  };

  try {
    await mkdir(path.join(webRoot, 'css'), { recursive: true });
    await Promise.all([
      writeFile(path.join(webRoot, 'index.html'), '<link rel="stylesheet" href="css/main.css"><div id="vectors"></div>', 'utf8'),
      writeFile(path.join(webRoot, 'css', 'main.css'), '#catalog-end { display: block !important; }', 'utf8'),
      writeFile(uiContractFile, JSON.stringify(ui), 'utf8'),
      writeFile(apiContractFile, JSON.stringify(api), 'utf8'),
      writeFile(capabilityFile, JSON.stringify(remoteCapabilities), 'utf8'),
    ]);

    const visible = await verifyUiCapabilityContract({
      webRoot,
      uiContractFile,
      apiContractFile,
      capabilityFile,
    });
    assert.equal(visible.ok, true, visible.errors.join('\n'));

    await writeFile(
      path.join(webRoot, 'css', 'main.css'),
      '#vectors { display: none !important; }\n#catalog-end { display: block !important; }',
      'utf8'
    );
    const hidden = await verifyUiCapabilityContract({
      webRoot,
      uiContractFile,
      apiContractFile,
      capabilityFile,
    });
    assert.equal(hidden.ok, false);
    assert.match(hidden.errors.join('\n'), /Configured action "Fixture vectors" is hidden by CSS selector: #vectors/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
