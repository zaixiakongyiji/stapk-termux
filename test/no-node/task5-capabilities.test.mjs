import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../..', import.meta.url);

const required = [
  'POST /api/chats/recent',
  'POST /api/chats/rename',
  'POST /api/chats/import',
  'POST /api/chats/export',
  'POST /api/backups/chat/get',
  'POST /api/backups/chat/download',
  'POST /api/backups/chat/delete',
  'POST /api/stats/get',
  'POST /api/stats/recreate',
  'POST /api/stats/update',
];

test('Task 5 chat management backup and stats endpoints are implemented by the native adapter', async () => {
  const [allowlistText, series] = await Promise.all([
    readFile(new URL('./transform/no-node/mvp-api-allowlist.json', root), 'utf8'),
    readFile(new URL('./patches/sillytavern-no-node/series', root), 'utf8'),
  ]);
  const allowlist = JSON.parse(allowlistText);
  const implemented = new Set(allowlist.implemented.map(({ method, path }) => `${method} ${path}`));

  required.forEach((endpoint) => assert.ok(implemented.has(endpoint), `missing ${endpoint}`));
  assert.match(series, /^0003-stapk-mobile-chat-management\.patch$/m);
});

test('formal Android assets use stem keyed stats and direct file chat exports', async () => {
  const [contractText, script, stats] = await Promise.all([
    readFile(new URL('./mobile/app/src/main/assets/api-contract.json', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/script.js', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/stats.js', root), 'utf8'),
  ]);
  const contract = JSON.parse(contractText);
  const entries = new Map(contract.endpoints.map((item) => [`${item.method} ${item.path}`, item]));

  required.forEach((endpoint) => assert.equal(entries.get(endpoint)?.status, 'implemented', endpoint));
  assert.match(stats, /const statsKey = characters\[this_chid\]\.avatar\.replace\(\/\\\.png\$\/i, ''\);/);
  assert.match(script, /const result = await response\.blob\(\);/);
  assert.doesNotMatch(script, /const data = await response\.json\(\);\s+if \(!response\.ok\)[\s\S]{0,800}download\(data\.result, body\.exportfilename, mimeType\);/);
});
