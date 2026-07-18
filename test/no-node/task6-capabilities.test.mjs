import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../..', import.meta.url);

const required = [
  'POST /api/worldinfo/list',
  'POST /api/worldinfo/get',
  'POST /api/worldinfo/edit',
  'POST /api/worldinfo/delete',
  'POST /api/worldinfo/import',
];

const lorebookSelectors = [
  '#WI-SP-button',
  '#world_button',
  '.chat_lorebook_button',
  '#set_character_world',
  '#import_character_info',
  '#character_world_template',
  '#persona_lore_button',
  '#group_chat_lorebook_link',
];

test('Task 6 World Info endpoints are implemented by the native adapter', async () => {
  const [allowlistText, series] = await Promise.all([
    readFile(new URL('./transform/no-node/mvp-api-allowlist.json', root), 'utf8'),
    readFile(new URL('./patches/sillytavern-no-node/series', root), 'utf8'),
  ]);
  const allowlist = JSON.parse(allowlistText);
  const implemented = new Set(allowlist.implemented.map(({ method, path }) => `${method} ${path}`));

  required.forEach((endpoint) => assert.ok(implemented.has(endpoint), `missing ${endpoint}`));
  assert.match(series, /^0004-stapk-mobile-world-info\.patch$/m);
});

test('formal Android assets expose lorebook UI while keeping vector RAG hidden', async () => {
  const [contractText, html, mobileCss] = await Promise.all([
    readFile(new URL('./mobile/app/src/main/assets/api-contract.json', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/index.html', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/css/stapk-mobile.css', root), 'utf8'),
  ]);
  const contract = JSON.parse(contractText);
  const entries = new Map(contract.endpoints.map((item) => [`${item.method} ${item.path}`, item]));

  required.filter((endpoint) => !endpoint.endsWith('/list')).forEach((endpoint) => {
    assert.equal(entries.get(endpoint)?.status, 'implemented', endpoint);
  });
  lorebookSelectors.forEach((selector) => {
    const htmlToken = selector.startsWith('.')
      ? `class=\"${selector.slice(1)}`
      : `id=\"${selector.slice(1)}\"`;
    assert.ok(html.includes(htmlToken), `missing lorebook UI ${selector}`);
    assert.doesNotMatch(mobileCss, new RegExp(`^\\s*${escapeRegExp(selector)}\\s*,?\\s*$`, 'm'));
  });
  assert.match(mobileCss, /^\s*#vectors_container\s*\{/m);
});

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
