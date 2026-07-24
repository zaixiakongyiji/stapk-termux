import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const androidAssets = path.join(root, 'mobile', 'app', 'src', 'main', 'assets');
const androidWebRoot = path.join(androidAssets, 'sillytavern-web');
const EXPECTED_IMPLEMENTED_ACTIONS = [
  {
    name: 'Streaming',
    selector: '#stream_toggle',
    capability: 'core.chats',
    endpoint: 'POST /api/backends/chat-completions/generate',
    source: { type: 'html', path: 'index.html' },
  },
  {
    name: 'Extension install',
    selector: '#third_party_extension_button',
    capability: 'native.extensions',
    endpoint: 'POST /api/extensions/install',
    source: { type: 'html', path: 'index.html' },
  },
  {
    name: 'Extension details',
    selector: '#extensions_details',
    capability: 'native.extensions',
    endpoint: 'GET /api/extensions/discover',
    source: { type: 'html', path: 'index.html' },
  },
  {
    name: 'Extension manual update',
    selector: '.btn_update',
    capability: 'native.extensions',
    endpoint: 'POST /api/extensions/update',
    source: { type: 'javascript', path: 'scripts/extensions.js' },
  },
  {
    name: 'Extension delete',
    selector: '.btn_delete',
    capability: 'native.extensions',
    endpoint: 'POST /api/extensions/delete',
    source: { type: 'javascript', path: 'scripts/extensions.js' },
  },
  {
    name: 'Extension update notifications',
    selector: '#extensions_notify_updates',
    capability: 'native.extensions',
    endpoint: 'POST /api/extensions/version',
    source: { type: 'html', path: 'index.html' },
  },
  {
    name: 'World Info',
    selector: '#WI-SP-button',
    capability: 'core.world_info',
    endpoint: 'POST /api/worldinfo/get',
    source: { type: 'html', path: 'index.html' },
  },
];

async function loadVerifier() {
  return import('../../scripts/stapk-verify-ui-capability-contract.mjs');
}

test('final Android UI capability asset is generated from the formal source contract', async () => {
  const [source, generated] = await Promise.all([
    readFile(path.join(root, 'transform', 'no-node', 'ui-capabilities.json'), 'utf8').then(JSON.parse),
    readFile(path.join(androidWebRoot, 'stapk-ui-capabilities.json'), 'utf8').then(JSON.parse),
  ]);

  assert.deepEqual(generated, source);
  assert.deepEqual(
    generated.implementedActions.map((action) => ({
      name: action.name,
      selector: action.selector,
      capability: action.capability,
      endpoint: action.endpoint ?? null,
      source: {
        type: action.source.type,
        path: action.source.path,
      },
    })),
    EXPECTED_IMPLEMENTED_ACTIONS,
  );
  assert.equal(generated.hiddenSelectors.length, 33);
});

test('production UI verifier accepts implemented actions and hidden selectors in final Android assets', async () => {
  const { verifyUiCapabilityContract } = await loadVerifier();
  const result = await verifyUiCapabilityContract({
    webRoot: androidWebRoot,
    uiContractFile: path.join(androidWebRoot, 'stapk-ui-capabilities.json'),
    apiContractFile: path.join(androidAssets, 'api-contract.json'),
    capabilityFile: path.join(root, 'transform', 'no-node', 'capabilities.json'),
  });

  assert.deepEqual(result.errors, []);
  assert.equal(result.ok, true);
  assert.equal(result.summary.implementedActions, 7);
  assert.equal(result.summary.hiddenSelectors, 33);
  assert.ok(result.summary.localStylesheets >= 1);
});
