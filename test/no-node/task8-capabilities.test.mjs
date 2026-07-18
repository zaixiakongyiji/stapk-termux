import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../..', import.meta.url);
const openAiTokenizerEndpoints = [
  'POST /api/tokenizers/openai/encode',
  'POST /api/tokenizers/openai/decode',
  'POST /api/tokenizers/openai/count',
];

test('Task 8 allowlist and capability contract isolate OpenAI tokenizer routes', async () => {
  const [allowlistText, capabilitiesText, series, patch] = await Promise.all([
    readFile(new URL('./transform/no-node/mvp-api-allowlist.json', root), 'utf8'),
    readFile(new URL('./transform/no-node/capabilities.json', root), 'utf8'),
    readFile(new URL('./patches/sillytavern-no-node/series', root), 'utf8'),
    readFile(new URL('./patches/sillytavern-no-node/0006-stapk-mobile-openai-tokenizer.patch', root), 'utf8'),
  ]);
  const allowlist = JSON.parse(allowlistText);
  const capabilities = JSON.parse(capabilitiesText).capabilities;
  const implemented = new Set(allowlist.implemented.map(({ method, path }) => `${method} ${path}`));
  const hiddenPrefixes = new Set(allowlist.unsupportedHidden.map(({ prefix }) => prefix));

  [...openAiTokenizerEndpoints, 'POST /api/backends/chat-completions/bias'].forEach(endpoint => {
    assert.ok(implemented.has(endpoint), `missing ${endpoint}`);
  });
  assert.ok(hiddenPrefixes.has('/api/tokenizers'));
  assert.deepEqual(
    capabilities.find(item => item.id === 'core.tokenizers').endpointPrefixes,
    openAiTokenizerEndpoints.map(endpoint => endpoint.split(' ')[1]),
  );
  assert.ok(
    capabilities.find(item => item.id === 'excluded.local_models').endpointPrefixes
      .includes('/api/tokenizers/remote'),
  );
  assert.match(series, /^0006-stapk-mobile-openai-tokenizer\.patch$/m);
  assert.match(patch, /<select id="tokenizer" disabled>/);
  assert.match(patch, /power_user\.tokenizer = tokenizers\.BEST_MATCH;/);
  assert.match(patch, /count: '\/api\/tokenizers\/openai\/count'/);
});

test('formal assets fix tokenizer UI and URLs to OpenAI-compatible automatic mode', async () => {
  const [html, tokenizers, powerUser] = await Promise.all([
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/index.html', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/tokenizers.js', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/power-user.js', root), 'utf8'),
  ]);
  const selector = html.match(/<select id="tokenizer"[^>]*>([\s\S]*?)<\/select>/);
  assert.ok(selector);
  assert.match(selector[0], /disabled/);
  assert.deepEqual([...selector[1].matchAll(/<option[^>]+value="([^"]+)"/g)].map(match => match[1]), ['99']);
  assert.match(selector[1], /OpenAI-compatible \(automatic\)/);
  assert.match(powerUser, /power_user\.tokenizer = tokenizers\.BEST_MATCH;/);
  assert.match(
    tokenizers,
    /\[tokenizers\.OPENAI\]: \{\s*encode: '\/api\/tokenizers\/openai\/encode',\s*decode: '\/api\/tokenizers\/openai\/decode',\s*count: '\/api\/tokenizers\/openai\/count',\s*\}/s,
  );
  assert.match(tokenizers, /`\/api\/tokenizers\/openai\/count\?model=\$\{getTokenizerModel\(\)\}`/);
});

test('formal contract implements only OpenAI tokenizer paths and hides every other tokenizer', async () => {
  const contract = JSON.parse(await readFile(
    new URL('./mobile/app/src/main/assets/api-contract.json', root),
    'utf8',
  ));
  const endpoints = new Map(contract.endpoints.map(item => [`${item.method} ${item.path}`, item]));

  openAiTokenizerEndpoints.forEach(endpoint => {
    assert.equal(endpoints.get(endpoint)?.status, 'implemented', endpoint);
    assert.equal(endpoints.get(endpoint)?.capability, 'core.tokenizers', endpoint);
  });
  const bias = endpoints.get('POST /api/backends/chat-completions/bias');
  assert.equal(bias?.status, 'implemented');
  assert.equal(bias?.capability, 'core.chats');

  const nonOpenAi = contract.endpoints.filter(item =>
    item.path.startsWith('/api/tokenizers/') && !item.path.startsWith('/api/tokenizers/openai/'));
  assert.ok(nonOpenAi.length > 0);
  nonOpenAi.forEach(item => {
    assert.equal(item.status, 'unsupported_hidden', item.path);
    assert.equal(item.capability, 'excluded.local_models', item.path);
    assert.equal(item.exposure, 'hidden', item.path);
  });
});
