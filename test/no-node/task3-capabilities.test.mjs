import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../..', import.meta.url);

test('Task 3 character endpoints are implemented while external URL imports stay unavailable', async () => {
  const [allowlistText, capabilitiesText, patch] = await Promise.all([
    readFile(new URL('./transform/no-node/mvp-api-allowlist.json', root), 'utf8'),
    readFile(new URL('./transform/no-node/capabilities.json', root), 'utf8'),
    readFile(new URL('./patches/sillytavern-no-node/0002-stapk-mobile-hide-unsupported-mvp-features.patch', root), 'utf8'),
  ]);
  const allowlist = JSON.parse(allowlistText);
  const capabilities = JSON.parse(capabilitiesText);
  const implemented = new Set(allowlist.implemented.map(({ method, path }) => `${method} ${path}`));
  const required = [
    'POST /api/characters/import',
    'POST /api/characters/export',
    'POST /api/characters/duplicate',
    'POST /api/characters/rename',
    'POST /api/characters/merge-attributes',
    'POST /api/characters/edit-avatar',
  ];

  required.forEach((endpoint) => assert.ok(implemented.has(endpoint), `missing ${endpoint}`));
  assert.ok(capabilities.capabilities
    .find(({ id }) => id === 'core.characters')
    .endpointPrefixes.includes('/api/characters'));
  assert.equal(implemented.has('POST /api/content/importURL'), false);
  assert.equal(implemented.has('POST /api/content/importUUID'), false);
  assert.match(patch, /^\+.*#external_import_button/m);
  assert.match(patch, /^\+.*\.external_import_button/m);
  assert.match(patch, /^-.*accept="\.json, image\/png, \.yaml, \.yml, \.charx, \.byaf"/m);
  assert.match(patch, /^\+.*accept="\.json, image\/png"/m);
  assert.match(patch, /^-.*'application\/yaml'/m);
  assert.match(patch, /^-.*'charx'/m);
  assert.match(patch, /^-.*\['json', 'png', 'yaml', 'yml', 'charx', 'byaf'\]/m);
  assert.match(patch, /^\+.*\['json', 'png'\]/m);
  assert.match(patch, /^-.*Replace with URL/m);
  assert.match(patch, /^-.*importFromExternalUrl\(onlineUrl/m);
  assert.match(patch, /^-.*await importFromURL\(event\.originalEvent\.dataTransfer\.items, files\)/m);
});
