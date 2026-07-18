import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const HELPER_FILE = path.join(PROJECT_ROOT, 'transform', 'no-node', 'web', 'stapk-capabilities.js');

async function runHelper(fetch, document) {
  const source = await readFile(HELPER_FILE, 'utf8');
  const window = {};
  const context = vm.createContext({
    window,
    fetch,
    ...(document ? { document } : {}),
    console: { error() {} }
  });
  vm.runInContext(source, context);
  return window;
}

test('capability helper fails closed before and after a failed load', async () => {
  const window = await runHelper(async () => {
    throw new Error('offline');
  });

  assert.equal(window.isStapkCapabilityAvailable('core.settings'), false);
  await window.stapkCapabilitiesReady;
  assert.equal(window.isStapkCapabilityAvailable('core.settings'), false);
  assert.deepEqual(Object.keys(window.stapkCapabilities), []);
});

test('capability helper enables only explicit true values', async () => {
  let requestedUrl = '';
  const window = await runHelper(async (url) => {
    requestedUrl = url;
    return {
    ok: true,
    json: async () => ({
      schemaVersion: 1,
      capabilities: {
        'core.settings': true,
        'remote.image': false
      }
    })
    };
  });

  assert.equal(window.isStapkCapabilityAvailable('core.settings'), false);
  await window.stapkCapabilitiesReady;
  assert.equal(window.isStapkCapabilityAvailable('core.settings'), true);
  assert.equal(window.isStapkCapabilityAvailable('remote.image'), false);
  assert.equal(window.isStapkCapabilityAvailable('unknown.capability'), false);
  assert.equal(requestedUrl, '/stapk-capabilities.json');
});

test('capability helper shows the external service note until every remote capability is enabled', async () => {
  const note = { hidden: true };
  const document = {
    readyState: 'complete',
    getElementById: (id) => id === 'stapk-external-capabilities-note' ? note : null
  };
  const window = await runHelper(async () => ({
    ok: true,
    json: async () => ({
      schemaVersion: 1,
      capabilities: {
        'remote.embeddings': true,
        'remote.image': false,
        'remote.tts': true,
        'remote.stt': true,
        'remote.caption': true,
        'remote.translation': true
      }
    })
  }), document);

  await window.stapkCapabilitiesReady;
  assert.equal(note.hidden, false);
});
