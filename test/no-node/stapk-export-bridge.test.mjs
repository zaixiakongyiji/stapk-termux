import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { pathToFileURL } from 'node:url';

const helperPath = path.resolve('transform/no-node/web/stapk-export.js');

async function loadHelper() {
  assert.equal(existsSync(helperPath), true, 'Missing stAPK export bridge helper');
  return import(`${pathToFileURL(helperPath).href}?test=${Date.now()}-${Math.random()}`);
}

test('response export uses the Android bridge only when token nonce and bridge exist', async () => {
  const { requestStapkResponseExport } = await loadHelper();
  const calls = [];
  const response = new Response('data', {
    headers: { 'X-stAPK-Export-Token': 'T'.repeat(43) },
  });
  const android = {
    window: {
      stapkBridgeNonce: 'N'.repeat(43),
      StapkFiles: { saveExport: (...args) => calls.push(args) },
    },
  };

  assert.equal(requestStapkResponseExport(response, 'chat.jsonl', 'application/x-ndjson', android), true);
  assert.deepEqual(calls, [['N'.repeat(43), 'T'.repeat(43), 'chat.jsonl', 'application/x-ndjson']]);
  assert.equal(requestStapkResponseExport(response, 'chat.jsonl', 'application/x-ndjson', { window: {} }), false);
});

test('browser generated export stages a multipart ticket before requesting SAF', async () => {
  const { stageStapkGeneratedExport } = await loadHelper();
  const calls = [];
  let uploadedFile;
  const environment = {
    Blob,
    FormData,
    window: {
      stapkBridgeNonce: 'N'.repeat(43),
      StapkFiles: { saveExport: (...args) => calls.push(args) },
    },
    fetch: async (url, options) => {
      assert.equal(url, '/api/stapk/exports/create');
      assert.equal(options.method, 'POST');
      assert.equal(options.headers['X-stAPK-Bridge-Nonce'], 'N'.repeat(43));
      uploadedFile = options.body.get('file');
      return new Response(JSON.stringify({
        token: 'T'.repeat(43),
        fileName: '世界书.json',
        mimeType: 'application/json',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    },
  };

  assert.equal(
    await stageStapkGeneratedExport('{"entries":{}}', '世界书.json', 'application/json', environment),
    true
  );
  assert.equal(uploadedFile.name, '世界书.json');
  assert.equal(await uploadedFile.text(), '{"entries":{}}');
  assert.deepEqual(calls, [['N'.repeat(43), 'T'.repeat(43), '世界书.json', 'application/json']]);
});

test('generated export failure is visible to the user', async () => {
  const { requestStapkGeneratedExport } = await loadHelper();
  const errors = [];
  const environment = {
    Blob,
    FormData,
    console: { error: () => {} },
    toastr: { error: message => errors.push(message) },
    window: {
      stapkBridgeNonce: 'N'.repeat(43),
      StapkFiles: { saveExport: () => {} },
    },
    fetch: async () => new Response('', { status: 500 }),
  };

  assert.equal(requestStapkGeneratedExport('data', 'data.txt', 'text/plain', environment), true);
  await new Promise(resolve => setTimeout(resolve, 0));

  assert.deepEqual(errors, ['Unable to save export.']);
});

test('browser generated export reports no bridge without issuing a request', async () => {
  const { stageStapkGeneratedExport } = await loadHelper();
  let fetchCalls = 0;

  const handled = await stageStapkGeneratedExport('data', 'data.txt', 'text/plain', {
    Blob,
    FormData,
    window: {},
    fetch: async () => {
      fetchCalls++;
      throw new Error('must not fetch');
    },
  });

  assert.equal(handled, false);
  assert.equal(fetchCalls, 0);
});

test('SAF staging endpoint is implemented under the core files capability', async () => {
  const [allowlistText, capabilitiesText] = await Promise.all([
    readFile('transform/no-node/mvp-api-allowlist.json', 'utf8'),
    readFile('transform/no-node/capabilities.json', 'utf8'),
  ]);
  const allowlist = JSON.parse(allowlistText);
  const capability = JSON.parse(capabilitiesText).capabilities
    .find(item => item.id === 'core.files');

  assert.ok(
    allowlist.implemented.some(item =>
      item.method === 'POST' && item.path === '/api/stapk/exports/create'),
  );
  assert.ok(capability.endpointPrefixes.includes('/api/stapk/exports'));
});

test('formal Android assets route response generated and attachment exports through SAF', async () => {
  const [helper, script, utils, chats, contractText] = await Promise.all([
    readFile('mobile/app/src/main/assets/sillytavern-web/scripts/stapk-export.js', 'utf8'),
    readFile('mobile/app/src/main/assets/sillytavern-web/script.js', 'utf8'),
    readFile('mobile/app/src/main/assets/sillytavern-web/scripts/utils.js', 'utf8'),
    readFile('mobile/app/src/main/assets/sillytavern-web/scripts/chats.js', 'utf8'),
    readFile('mobile/app/src/main/assets/api-contract.json', 'utf8'),
  ]);
  const contract = JSON.parse(contractText);
  const endpoint = contract.endpoints.find(item =>
    item.method === 'POST' && item.path === '/api/stapk/exports/create');

  assert.match(helper, /stageStapkGeneratedExport/);
  assert.match(script, /requestStapkResponseExport\(response, body\.exportfilename, mimeType\)/);
  assert.match(script, /requestStapkResponseExport\(response, filename, mimeType\)/);
  assert.match(utils, /requestStapkGeneratedExport\(content, fileName, contentType\)/);
  const attachmentDownload = chats.match(/async function downloadAttachment\(attachment\) \{[\s\S]*?\n\}/)?.[0] ?? '';
  assert.match(attachmentDownload, /download\(fileText, fileName, 'text\/plain'\)/);
  assert.doesNotMatch(attachmentDownload, /URL\.createObjectURL/);
  assert.match(chats, /\.jsonl`, 'application\/x-ndjson'\)/);
  assert.equal(endpoint?.status, 'implemented');
  assert.equal(endpoint?.capability, 'core.files');
});
