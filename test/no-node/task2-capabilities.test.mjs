import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../..', import.meta.url);
const addedPatchLines = (patch) => patch.split(/\r?\n/)
  .filter((line) => line.startsWith('+') && !line.startsWith('+++'))
  .join('\n');

test('Task 2 settings and Persona endpoints are implemented and their UI is not hidden', async () => {
  const [allowlistText, capabilitiesText, patch] = await Promise.all([
    readFile(new URL('./transform/no-node/mvp-api-allowlist.json', root), 'utf8'),
    readFile(new URL('./transform/no-node/capabilities.json', root), 'utf8'),
    readFile(new URL('./patches/sillytavern-no-node/0002-stapk-mobile-hide-unsupported-mvp-features.patch', root), 'utf8'),
  ]);
  const allowlist = JSON.parse(allowlistText);
  const capabilities = JSON.parse(capabilitiesText);
  const implemented = new Set(allowlist.implemented.map(({ method, path }) => `${method} ${path}`));
  const required = [
    'POST /api/avatars/get', 'POST /api/avatars/upload', 'POST /api/avatars/delete',
    'GET /api/users/me', 'POST /api/users/change-avatar', 'POST /api/users/reset-settings',
    'POST /api/themes/save', 'POST /api/themes/delete',
    'POST /api/presets/save', 'POST /api/presets/delete', 'POST /api/presets/restore',
    'POST /api/settings/get-snapshots', 'POST /api/settings/load-snapshot',
    'POST /api/settings/make-snapshot', 'POST /api/settings/restore-snapshot',
    'POST /api/quick-replies/save', 'POST /api/quick-replies/delete', 'POST /api/moving-ui/save',
  ];

  required.forEach((endpoint) => assert.ok(implemented.has(endpoint), `missing ${endpoint}`));
  const settingsCapability = capabilities.capabilities.find(({ id }) => id === 'core.settings');
  const multiuserCapability = capabilities.capabilities.find(({ id }) => id === 'excluded.multiuser');
  assert.ok(settingsCapability.endpointPrefixes.includes('/api/settings'));
  assert.ok(settingsCapability.endpointPrefixes.includes('/api/users/me'));
  assert.ok(!multiuserCapability.endpointPrefixes.includes('/api/users/me'));
  assert.ok(capabilities.capabilities.find(({ id }) => id === 'core.personas').endpointPrefixes.includes('/api/avatars'));
  assert.doesNotMatch(patch, /#persona_lore_button/);
  assert.doesNotMatch(patch, /id="persona_lorebook_link"/);
  const additions = addedPatchLines(patch);
  assert.match(additions, /const extensions = \[\{ name: 'quick-reply', type: 'system' \}\];/);
  assert.doesNotMatch(additions, /api\/extensions\/discover/);
  for (const selector of [
    'label[for="extensions_notify_updates"]', '#extensions_details', '#extensions_install',
    '#extensions_settings', '#extensions_settings2 .extension_container:not(#qr_container)',
    '#extensions_settings2 ~ hr',
    '#extensions_settings2 ~ .alignitemscenter.flex-container.justifyCenter.wide100p',
    '#extensions_settings2 ~ .alignitemsflexstart.flex-container.wide100p',
    '.userBackupButton', '.userResetAllButton',
  ]) {
    assert.ok(additions.includes(selector), `missing unsupported extension selector: ${selector}`);
  }
});

test('formal Android assets expose Task 2 endpoints and supported UI entrypoints', async () => {
  const [contractText, css, extensions] = await Promise.all([
    readFile(new URL('./mobile/app/src/main/assets/api-contract.json', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/css/stapk-mobile.css', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/extensions.js', root), 'utf8'),
  ]);
  const contract = JSON.parse(contractText);
  const entries = new Map(contract.endpoints.map((item) => [`${item.method} ${item.path}`, item]));
  for (const endpoint of [
    'POST /api/avatars/get', 'POST /api/avatars/upload', 'POST /api/avatars/delete',
    'GET /api/users/me', 'POST /api/users/change-avatar', 'POST /api/users/reset-settings',
    'POST /api/themes/save', 'POST /api/themes/delete', 'POST /api/presets/save',
    'POST /api/presets/delete', 'POST /api/presets/restore', 'POST /api/settings/get-snapshots',
    'POST /api/settings/load-snapshot', 'POST /api/settings/make-snapshot',
    'POST /api/settings/restore-snapshot', 'POST /api/quick-replies/save',
    'POST /api/quick-replies/delete', 'POST /api/moving-ui/save',
  ]) {
    assert.equal(entries.get(endpoint)?.status, 'implemented', endpoint);
  }
  assert.equal(entries.get('GET /api/users/me')?.capability, 'core.settings');
  assert.equal(entries.get('POST /api/users/change-avatar')?.capability, 'core.personas');
  assert.doesNotMatch(css, /#extensions-settings-button|\[data-target="extensions-settings-button"\]|#persona_lore_button/);
  assert.match(css, /#qr_container/);
  assert.match(extensions, /async function discoverExtensions\(\)/);
  assert.match(extensions, /fetch\('\/api\/extensions\/discover'\)/);
  assert.match(extensions, /const extensions = await discoverExtensions\(\);/);
  assert.match(css, /#extensions_settings2 \.extension_container:not\(#qr_container\)/);
  assert.match(css, /label\[for="extensions_notify_updates"\]/);
  assert.match(css, /#extensions_settings2 ~ hr/);
  assert.match(css, /\.userBackupButton/);
  assert.match(css, /\.userResetAllButton/);
  assert.doesNotMatch(css, /\.userSettingsSnapshotsButton/);
  assert.doesNotMatch(css, /\.userResetSettingsButton/);
});
