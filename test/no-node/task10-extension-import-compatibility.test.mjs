import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../..', import.meta.url);

test('character import resolves missing extensions from MIME and reports unsupported files', async () => {
  const script = await readFile(
    new URL('./mobile/app/src/main/assets/sillytavern-web/script.js', root),
    'utf8',
  );

  assert.match(script, /function resolveCharacterImportFormat\(file\)/);
  assert.match(script, /file\.type === ['"]image\/png['"]\s*\) return ['"]png['"]/);
  assert.match(script, /application\/json/);
  assert.match(script, /toastr\.warning\(t`Only PNG and JSON character cards are supported\.`\)/);
  assert.match(script, /formData\.append\(['"]avatar['"], file, uploadName\)/);
});

test('character import refreshes an embedded World Info returned by the native adapter', async () => {
  const script = await readFile(
    new URL('./mobile/app/src/main/assets/sillytavern-web/script.js', root),
    'utf8',
  );
  const worldInfoImports = script.match(
    /import \{\s*world_info,([\s\S]*?)\} from ['"]\.\/scripts\/world-info\.js['"]/,
  );
  const importCharacter = script.match(
    /async function importCharacter\([\s\S]*?\r?\n\}\r?\n\r?\nasync function importFromURL/,
  )?.[0] ?? '';

  assert.ok(worldInfoImports);
  assert.match(worldInfoImports[1], /updateWorldInfoList/);
  assert.match(importCharacter, /if \(data\.embedded_world\) \{/);
  assert.match(importCharacter, /await updateWorldInfoList\(\);/);
  assert.match(importCharacter, /data\.embedded_world\.created/);
  assert.match(importCharacter, /data\.embedded_world\.entry_count/);
  assert.doesNotMatch(importCharacter, /importEmbeddedWorldInfo\(/);
  assert.doesNotMatch(importCharacter, /saveWorldInfo\(/);
});

test('World Info import reloads persisted entries before opening the editor', async () => {
  const worldInfo = await readFile(
    new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/world-info.js', root),
    'utf8',
  );

  assert.match(worldInfo, /worldInfoCache\.delete\(data\.name\);/);
  assert.match(worldInfo, /const imported = await loadWorldInfo\(data\.name\);/);
  assert.match(worldInfo, /typeof imported\.entries !== ['"]object['"]/);
  assert.match(worldInfo, /Array\.isArray\(imported\.entries\)/);
  assert.match(worldInfo, /await showWorldEditor\(data\.name\);/);
});

test('Regex and Main API Summarize are loaded and visible system extensions', async () => {
  const [controller, mobileCss] = await Promise.all([
    readFile(new URL('./mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ExtensionController.kt', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/css/stapk-mobile.css', root), 'utf8'),
  ]);

  assert.match(controller, /SYSTEM_EXTENSIONS = listOf\([\s\S]*?"regex"/);
  assert.match(controller, /SYSTEM_EXTENSIONS = listOf\([\s\S]*?"memory"/);
  assert.match(
    mobileCss,
    /#extensions_settings \.extension_container:not\(#expressions_container\)/,
  );
  assert.match(
    mobileCss,
    /#extensions_settings2 \.extension_container:not\(#qr_container\):not\(#regex_container\):not\(#summarize_container\)/,
  );
});

test('Summarize accepts only Main API in settings and slash commands', async () => {
  const [memory, settings] = await Promise.all([
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/extensions/memory/index.js', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/extensions/memory/settings.html', root), 'utf8'),
  ]);
  const sourceSelect = settings.match(/<select id="summary_source"[^>]*>([\s\S]*?)<\/select>/);

  assert.ok(sourceSelect);
  assert.deepEqual(
    [...sourceSelect[1].matchAll(/<option[^>]+value="([^"]+)"/g)].map(match => match[1]),
    ['main'],
  );
  assert.match(memory, /source: summary_sources\.main,/);
  assert.match(memory, /extension_settings\.memory\.source !== summary_sources\.main/);
  assert.match(memory, /extension_settings\.memory\.source = summary_sources\.main;/);
  assert.match(memory, /Only Main API summarization is supported/);
  assert.match(memory, /if \(source !== summary_sources\.main\) \{[\s\S]*?return '';/);
});

test('extension capability contract exposes only the five native manager endpoints', async () => {
  const [allowlist, capabilities] = await Promise.all([
    readFile(new URL('./transform/no-node/mvp-api-allowlist.json', root), 'utf8').then(JSON.parse),
    readFile(new URL('./transform/no-node/capabilities.json', root), 'utf8').then(JSON.parse),
  ]);
  const expectedEndpoints = [
    ['GET', '/api/extensions/discover'],
    ['POST', '/api/extensions/install'],
    ['POST', '/api/extensions/version'],
    ['POST', '/api/extensions/update'],
    ['POST', '/api/extensions/delete'],
  ];
  const implemented = new Set(allowlist.implemented.map(({ method, path }) => `${method} ${path}`));

  for (const [method, path] of expectedEndpoints) {
    assert.ok(implemented.has(`${method} ${path}`), `${method} ${path} must be implemented`);
  }
  assert.equal(allowlist.unsupportedHidden.some(({ prefix }) => prefix === '/api/extensions'), false);

  const nativeExtensions = capabilities.capabilities.find(({ id }) => id === 'native.extensions');
  assert.ok(nativeExtensions, 'native.extensions capability must exist');
  assert.equal(nativeExtensions.kind, 'core');
  assert.deepEqual(nativeExtensions.endpointPrefixes, expectedEndpoints.map(([, path]) => path));

  const excluded = capabilities.capabilities.find(({ id }) => id === 'excluded.extensions');
  assert.ok(excluded);
  assert.equal(excluded.endpointPrefixes.includes('/api/extensions'), false);
  assert.ok(excluded.endpointPrefixes.includes('/api/modules'));
  assert.ok(excluded.endpointPrefixes.includes('/api/summarize'));
});

test('extension manager discovers native extensions and exposes only supported controls', async () => {
  const [extensions, mobileCss] = await Promise.all([
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/extensions.js', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/css/stapk-mobile.css', root), 'utf8'),
  ]);
  const unsupportedSelector = mobileCss.match(/([\s\S]*?)\{\s*display:\s*none\s*!important;/)?.[1] ?? '';
  const installMenu = extensions.match(/export async function openThirdPartyExtensionMenu[\s\S]*?\n\}/)?.[0] ?? '';

  assert.match(extensions, /async function discoverExtensions\(\)/);
  assert.match(extensions, /const extensions = await discoverExtensions\(\);/);
  assert.doesNotMatch(unsupportedSelector, /#extensions_details/);
  assert.doesNotMatch(unsupportedSelector, /#extensions_install/);
  assert.doesNotMatch(unsupportedSelector, /#third_party_extension_button/);
  assert.match(mobileCss, /\.btn_move\s*,\s*\.btn_branch\s*\{\s*display:\s*none\s*!important;/);
  assert.doesNotMatch(installMenu, /Install for all users/);
  assert.doesNotMatch(installMenu, /extension_branch_name/);
  assert.match(installMenu, /await installExtension\(url, false\);/);
});
