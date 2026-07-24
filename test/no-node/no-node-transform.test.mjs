import { execFileSync } from 'node:child_process';
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

import {
  copyCapabilityRuntime,
  copyNoNodeWebAssets,
  copyNoNodeWebSupportAssets,
  hashDirectory,
  syncNoNodeAndroidAssets
} from '../../scripts/stapk-transform-no-node.mjs';
import { hashPatchQueue } from '../../scripts/stapk-artifact-hashes.mjs';
import * as transformModule from '../../scripts/stapk-transform-no-node.mjs';
import { verifyNoNodeOutput } from '../../scripts/stapk-verify-no-node-transform.mjs';

const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const PATCH_QUEUE_DIR = path.join(PROJECT_ROOT, 'patches', 'sillytavern-no-node');
const ANDROID_WEB_ROOT = path.join(PROJECT_ROOT, 'mobile', 'app', 'src', 'main', 'assets', 'sillytavern-web');

async function withTempOutput(fn) {
  const out = await mkdtemp(path.join(os.tmpdir(), 'stapk-no-node-output-'));
  try {
    await fn(out);
  } finally {
    await rm(out, { recursive: true, force: true });
  }
}

async function refreshOutputHashes(out, {
  patchQueueDir = PATCH_QUEUE_DIR,
} = {}) {
  const manifestPath = path.join(out, 'stapk-web-manifest.json');
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  manifest.hashes = {
    webRootSha256: await hashDirectory(path.join(out, 'sillytavern-web')),
    patchQueueSha256: await hashPatchQueue(patchQueueDir),
  };
  await writeFile(manifestPath, JSON.stringify(manifest), 'utf8');
}

async function writeValidOutput(out, options = {}) {
  await mkdir(path.join(out, 'sillytavern-web', 'css'), { recursive: true });
  await writeFile(
    path.join(out, 'sillytavern-web', 'index.html'),
    '<!doctype html><link rel="stylesheet" href="css/main.css">\n',
    'utf8'
  );
  await writeFile(
    path.join(out, 'sillytavern-web', 'css', 'main.css'),
    '#catalog-end { display: block !important; }\n',
    'utf8'
  );
  await writeFile(
    path.join(out, 'sillytavern-web', 'lib.js'),
    'export const bundled = true;\n',
    'utf8'
  );
  await writeFile(
    path.join(out, 'sillytavern-web', 'stapk-capabilities.json'),
    JSON.stringify({ schemaVersion: 1, capabilities: {} }),
    'utf8'
  );
  await writeFile(
    path.join(out, 'sillytavern-web', 'stapk-ui-capabilities.json'),
    JSON.stringify({
      schemaVersion: 1,
      hiddenStylesheets: [{ path: 'css/main.css', catalogBefore: '#catalog-end' }],
      implementedActions: [],
      hiddenSelectors: []
    }),
    'utf8'
  );
  await writeFile(
    path.join(out, 'api-contract.json'),
    JSON.stringify({
      schemaVersion: 1,
      generatedAt: '2026-07-09T00:00:00.000Z',
      upstream: { ref: 'release', commit: 'abc123', version: '1.0.0' },
      webRoot: path.join(out, 'sillytavern-web'),
      endpoints: [],
      summary: { implemented: 0, unsupported_hidden: 0, needs_review: 0 }
    }),
    'utf8'
  );
  await writeFile(
    path.join(out, 'stapk-web-manifest.json'),
    JSON.stringify({
      schemaVersion: 1,
      generatedAt: '2026-07-09T00:00:00.000Z',
      upstream: { repo: 'https://github.com/SillyTavern/SillyTavern.git', ref: 'release', commit: 'abc123' },
      output: { webRoot: 'sillytavern-web', apiContract: 'api-contract.json' },
      hashes: { webRootSha256: '', patchQueueSha256: '' },
      noRuntimeNode: true
    }),
    'utf8'
  );
  await writeFile(
    path.join(out, 'transform-report.json'),
    JSON.stringify({ ok: true }),
    'utf8'
  );
  await refreshOutputHashes(out, options);
}

function git(cwd, args) {
  return execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
}

function readSelectValues(html, id) {
  const select = html.match(new RegExp(`<select[^>]+id=["']${id}["'][^>]*>([\\s\\S]*?)<\\/select>`));
  assert.ok(select, `Missing select#${id}`);
  return [...select[1].matchAll(/<option[^>]+value=["']([^"']+)["']/g)].map((match) => match[1]);
}

test('applyPatchQueue returns empty patch metadata when series is absent', async () => {
  await withTempOutput(async (root) => {
    const patchedDir = path.join(root, 'patched');
    const patchQueueDir = path.join(root, 'patches');
    await mkdir(patchedDir, { recursive: true });
    await mkdir(patchQueueDir, { recursive: true });

    const result = await transformModule.applyPatchQueue({ patchedDir, patchQueueDir });

    assert.deepEqual(result.names, []);
    assert.match(result.sha256, /^[a-f0-9]{64}$/);
  });
});

test('applyPatchQueue applies patches in series order and reports their names', async () => {
  await withTempOutput(async (root) => {
    const patchedDir = path.join(root, 'patched');
    const patchQueueDir = path.join(root, 'patches');
    await mkdir(patchedDir, { recursive: true });
    await mkdir(patchQueueDir, { recursive: true });
    git(patchedDir, ['init']);
    git(patchedDir, ['config', 'user.name', 'stapk-test']);
    git(patchedDir, ['config', 'user.email', 'stapk-test@localhost']);
    git(patchedDir, ['config', 'core.autocrlf', 'false']);

    const target = path.join(patchedDir, 'target.txt');
    await writeFile(target, 'zero\n', 'utf8');
    git(patchedDir, ['add', 'target.txt']);
    git(patchedDir, ['commit', '-m', 'zero']);
    const baseline = git(patchedDir, ['rev-parse', 'HEAD']);

    await writeFile(target, 'one\n', 'utf8');
    await writeFile(path.join(patchQueueDir, '0001.patch'), `${git(patchedDir, ['diff', '--binary'])}\n`, 'utf8');
    git(patchedDir, ['add', 'target.txt']);
    git(patchedDir, ['commit', '-m', 'one']);

    await writeFile(target, 'two\n', 'utf8');
    await writeFile(path.join(patchQueueDir, '0002.patch'), `${git(patchedDir, ['diff', '--binary'])}\n`, 'utf8');
    git(patchedDir, ['reset', '--hard', baseline]);
    await writeFile(path.join(patchQueueDir, 'series'), '0001.patch\n0002.patch\n', 'utf8');

    const result = await transformModule.applyPatchQueue({ patchedDir, patchQueueDir });

    assert.deepEqual(result.names, ['0001.patch', '0002.patch']);
    assert.match(result.sha256, /^[a-f0-9]{64}$/);
    assert.equal(await readFile(target, 'utf8'), 'two\n');
  });
});

test('buildNoNodeTransformReport records applied patch names', () => {
  const report = transformModule.buildNoNodeTransformReport({
    generatedAt: '2026-07-11T00:00:00.000Z',
    output: 'C:/tmp/no-node-payload',
    upstream: { ref: 'release', commit: 'abc123' },
    apiSummary: { implemented: 17, unsupported_hidden: 23, needs_review: 217 },
    verification: { ok: true },
    patchNames: ['0001-defaults.patch', '0002-hide-features.patch']
  });

  assert.deepEqual(report.patches, ['0001-defaults.patch', '0002-hide-features.patch']);
  assert.equal(report.generatedAt, '2026-07-11T00:00:00.000Z');
  assert.equal(report.ok, true);
});

test('Android no-node patch series lists all auditable MVP patches', async () => {
  const series = (await readFile(path.join(PATCH_QUEUE_DIR, 'series'), 'utf8'))
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);

  assert.deepEqual(series, [
    '0001-stapk-mobile-default-openai-compatible.patch',
    '0002-stapk-mobile-hide-unsupported-mvp-features.patch',
    '0003-stapk-mobile-chat-management.patch',
    '0004-stapk-mobile-world-info.patch',
    '0005-stapk-mobile-media-management.patch',
    '0006-stapk-mobile-openai-tokenizer.patch',
    '0007-stapk-mobile-saf-export.patch',
    '0008-stapk-mobile-capability-gates.patch',
    '0009-stapk-mobile-extension-and-import-compatibility.patch',
    '0010-stapk-mobile-unicode-and-embedded-lorebook.patch',
    '0011-stapk-mobile-world-info-global-selector.patch'
  ]);
  const patches = await Promise.all(series.map(async (patchName) => {
    const patchPath = path.join(PATCH_QUEUE_DIR, patchName);
    await access(patchPath);
    return [patchName, await readFile(patchPath, 'utf8')];
  }));
  for (const [patchName, contents] of patches) {
    assert.ok(!contents.includes('\r'), `${patchName} must use LF line endings`);
  }
});

test('default OpenAI patch preserves upstream streaming preset behavior', async () => {
  await withTempOutput(async (root) => {
    const patchedDir = path.join(root, 'patched');
    const patchQueueDir = path.join(root, 'patches');
    await mkdir(path.join(patchedDir, 'public', 'scripts'), { recursive: true });
    await mkdir(patchQueueDir, { recursive: true });
    git(patchedDir, ['init']);
    git(patchedDir, ['config', 'user.name', 'stapk-test']);
    git(patchedDir, ['config', 'user.email', 'stapk-test@localhost']);
    git(patchedDir, ['config', 'core.autocrlf', 'false']);

    await writeFile(
      path.join(patchedDir, 'public', 'script.js'),
      [
        'export async function getSettings(initLoaderHandle = null) {',
        "        $('#amount_gen').val(amount_gen);",
        "        $('#amount_gen_counter').val(amount_gen);",
        '',
        '        //Load which API we are using',
        '        if (settings.main_api == undefined) {',
        "            settings.main_api = 'kobold';",
        '        }',
        '',
        "        if (settings.main_api == 'poe') {",
        "            settings.main_api = 'openai';",
        '        }',
        '',
        '        main_api = settings.main_api;',
        "        $('#main_api').val(main_api);",
        "        $(`#main_api option[value=${main_api}]`).attr('selected', 'true');",
        ''
      ].join('\n'),
      'utf8'
    );
    await writeFile(
      path.join(patchedDir, 'public', 'scripts', 'openai.js'),
      [
        ...Array(4234).fill(''),
        'function loadOpenAISettings(data, settings) {',
        '',
        '    migrateChatCompletionSettings(settings);',
        '',
        '    for (const key of Object.keys(default_settings)) {',
        '        oai_settings[key] = settings[key] ?? default_settings[key];',
        '        const settingToUpdate = Object.values(settingsToUpdate).find(([_, k]) => k === key);',
        '    }',
        '}',
        '',
        ...Array(425).fill(''),
        'async function onPresetImportFileChange(e) {',
        '    let presetBody;',
        '',
        '    try {',
        '        presetBody = JSON.parse(await getFileText(e.target.files[0]));',
        '    } catch (err) {',
        '        toastr.error(t`Invalid file`);',
        '        return;',
        '    }',
        '',
        '    const fields = sensitiveFields.filter(field => presetBody[field]).map(field => `<b>${field}</b>`);',
        '    const shouldConfirm = fields.length > 0;',
        '',
        '}',
        '',
        'const settingsToUpdate = {',
        "    stream_openai: ['#stream_toggle', 'stream_openai', true, false],",
        '};',
        '',
        "$('#stream_toggle').on('change', function () {",
        "    oai_settings.stream_openai = !!$('#stream_toggle').prop('checked');",
        '    saveSettingsDebounced();',
        '});',
        ''
      ].join('\n'),
      'utf8'
    );
    git(patchedDir, ['add', '.']);
    git(patchedDir, ['commit', '-m', 'upstream fixtures']);
    await writeFile(
      path.join(patchQueueDir, '0001-stapk-mobile-default-openai-compatible.patch'),
      await readFile(path.join(PATCH_QUEUE_DIR, '0001-stapk-mobile-default-openai-compatible.patch'), 'utf8'),
      'utf8'
    );
    await writeFile(patchQueueDir + path.sep + 'series', '0001-stapk-mobile-default-openai-compatible.patch\n', 'utf8');

    await transformModule.applyPatchQueue({ patchedDir, patchQueueDir });

    const openAiScript = await readFile(path.join(patchedDir, 'public', 'scripts', 'openai.js'), 'utf8');
    assert.doesNotMatch(openAiScript, /settings\.stream_openai\s*=\s*false/);
    assert.match(openAiScript, /stream_openai:\s*\['#stream_toggle'/);
    assert.match(openAiScript, /oai_settings\.stream_openai\s*=\s*!!\$\('#stream_toggle'\)/);
  });
});

test('default OpenAI patch normalizes imported streaming presets to booleans', async () => {
  await withTempOutput(async (root) => {
    const patchedDir = path.join(root, 'patched');
    const patchQueueDir = path.join(root, 'patches');
    await mkdir(path.join(patchedDir, 'public', 'scripts'), { recursive: true });
    await mkdir(patchQueueDir, { recursive: true });
    git(patchedDir, ['init']);
    git(patchedDir, ['config', 'user.name', 'stapk-test']);
    git(patchedDir, ['config', 'user.email', 'stapk-test@localhost']);
    git(patchedDir, ['config', 'core.autocrlf', 'false']);

    await writeFile(
      path.join(patchedDir, 'public', 'script.js'),
      [
        'export async function getSettings(initLoaderHandle = null) {',
        "        $('#amount_gen').val(amount_gen);",
        "        $('#amount_gen_counter').val(amount_gen);",
        '',
        '        //Load which API we are using',
        '        if (settings.main_api == undefined) {',
        "            settings.main_api = 'kobold';",
        '        }',
        '',
        "        if (settings.main_api == 'poe') {",
        "            settings.main_api = 'openai';",
        '        }',
        '',
        '        main_api = settings.main_api;',
        "        $('#main_api').val(main_api);",
        "        $(`#main_api option[value=${main_api}]`).attr('selected', 'true');",
        ''
      ].join('\n'),
      'utf8'
    );
    await writeFile(
      path.join(patchedDir, 'public', 'scripts', 'openai.js'),
      [
        ...Array(4234).fill(''),
        'function loadOpenAISettings(data, settings) {',
        '',
        '    migrateChatCompletionSettings(settings);',
        '',
        '    for (const key of Object.keys(default_settings)) {',
        '        oai_settings[key] = settings[key] ?? default_settings[key];',
        '        const settingToUpdate = Object.values(settingsToUpdate).find(([_, k]) => k === key);',
        '    }',
        '}',
        '',
        ...Array(425).fill(''),
        'async function onPresetImportFileChange(e) {',
        '    const importedFile = await getFileText(e.target.files[0]);',
        '    let presetBody;',
        '',
        '    try {',
        '        presetBody = JSON.parse(importedFile);',
        '    } catch (err) {',
        '        return;',
        '    }',
        '',
        '    const fields = sensitiveFields.filter(field => presetBody[field]).map(field => `<b>${field}</b>`);',
        '    const shouldConfirm = fields.length > 0;',
        '',
        '}',
        ''
      ].join('\n'),
      'utf8'
    );
    git(patchedDir, ['add', '.']);
    git(patchedDir, ['commit', '-m', 'upstream preset fixture']);
    await writeFile(
      path.join(patchQueueDir, '0001-stapk-mobile-default-openai-compatible.patch'),
      await readFile(path.join(PATCH_QUEUE_DIR, '0001-stapk-mobile-default-openai-compatible.patch'), 'utf8'),
      'utf8'
    );
    await writeFile(path.join(patchQueueDir, 'series'), '0001-stapk-mobile-default-openai-compatible.patch\n', 'utf8');

    await transformModule.applyPatchQueue({ patchedDir, patchQueueDir });

    const openAiScript = await readFile(path.join(patchedDir, 'public', 'scripts', 'openai.js'), 'utf8');
    const normalization = openAiScript.match(
      /presetBody\.stream_openai = typeof presetBody\.stream_openai === 'boolean'\s*\? presetBody\.stream_openai\s*:\s*false;/,
    )?.[0];
    assert.ok(normalization, 'Missing imported stream_openai normalization');
    const normalize = new Function('presetBody', `${normalization}\nreturn presetBody.stream_openai;`);

    for (const [preset, expected] of [
      [{ stream_openai: true }, true],
      [{ stream_openai: false }, false],
      [{}, false],
      [{ stream_openai: 'true' }, false],
      [{ stream_openai: 1 }, false],
      [{ stream_openai: null }, false],
    ]) {
      assert.equal(normalize(preset), expected);
    }
  });
});

test('Android no-node Web assets expose only MVP API providers', async () => {
  const html = await readFile(path.join(ANDROID_WEB_ROOT, 'index.html'), 'utf8');

  assert.deepEqual(readSelectValues(html, 'main_api'), ['openai']);
  assert.deepEqual(readSelectValues(html, 'chat_completion_source'), ['openai', 'custom']);
});

test('Android no-node Web assets hide unsupported features while exposing Quick Replies and lorebooks', async () => {
  const html = await readFile(path.join(ANDROID_WEB_ROOT, 'index.html'), 'utf8');
  const css = await readFile(path.join(ANDROID_WEB_ROOT, 'css', 'stapk-mobile.css'), 'utf8');
  const hiddenSelectors = [
    '#main-API-selector-block',
    '#sd_container',
    '#sd_wand_container',
    '#sd_gen',
    '#sd_dropdown',
    '.sd_message_gen',
    '#tts_container',
    '#tts_wand_container',
    '#ttsExtensionMenuItem',
    '#ttsExtensionNarrateAll',
    '.mes_narrate',
    '#stt_container',
    '#vectors_container'
  ];

  for (const selector of hiddenSelectors) {
    assert.ok(css.includes(selector), `Missing hidden selector: ${selector}`);
  }
  for (const selector of [
    '#extensions-settings-button',
    '#extensionsMenuButton',
    '#extensionsMenu',
    '[data-target="extensions-settings-button"]',
    '#persona_lore_button',
    '#WI-SP-button',
    '#world_button',
    '.chat_lorebook_button',
    '#set_character_world',
    '#import_character_info',
    '#character_world_template',
    '#rm_button_group_chats',
    '#rm_group_chats_block'
  ]) {
    assert.ok(!css.includes(selector), `Unexpected hidden selector: ${selector}`);
  }
  for (const id of ['rm_button_group_chats', 'rm_group_chats_block']) {
    assert.match(html, new RegExp(`id=["']${id}["']`), `Missing visible group UI: ${id}`);
  }
  for (const id of [
    'set_character_world',
    'import_character_info',
    'group_chat_lorebook_link'
  ]) {
    assert.match(html, new RegExp(`<option[^>]+id=["']${id}["']`));
  }
});

test('Android no-node Web assets load capability gates and avoid unnamed Quick Reply saves', async () => {
  const html = await readFile(path.join(ANDROID_WEB_ROOT, 'index.html'), 'utf8');
  const quickReplies = await readFile(
    path.join(ANDROID_WEB_ROOT, 'scripts', 'extensions', 'quick-reply', 'src', 'QuickReplySet.js'),
    'utf8'
  );

  assert.match(html, /<script src=["']scripts\/stapk-capabilities\.js["']><\/script>/);
  assert.match(html, /id=["']stapk-external-capabilities-note["']/);
  assert.match(quickReplies, /if \(!this\.name\)\s*\{\s*return;\s*\}/);
});

test('Android no-node Web assets keep every implemented single-user entrypoint visible', async () => {
  const html = await readFile(path.join(ANDROID_WEB_ROOT, 'index.html'), 'utf8');
  const css = await readFile(path.join(ANDROID_WEB_ROOT, 'css', 'stapk-mobile.css'), 'utf8');
  const attachmentButton = await readFile(
    path.join(ANDROID_WEB_ROOT, 'scripts', 'extensions', 'attachments', 'attach-button.html'),
    'utf8'
  );
  const visibleIds = [
    'persona-management-button',
    'world_button',
    'rm_button_group_chats',
    'option_select_chat',
    'backgrounds-button',
    'character_import_button',
    'export_button',
    'chat_import_button',
    'themes',
    'settings_preset_openai'
  ];

  for (const id of visibleIds) {
    assert.match(html, new RegExp(`id=["']${id}["']`), `Missing core UI entrypoint: ${id}`);
    assert.ok(!css.includes(`#${id}`), `Core UI entrypoint is hidden: ${id}`);
  }
  assert.match(attachmentButton, /id=["']attachFile["']/);
  assert.ok(!css.includes('#attachFile'), 'Attachment entrypoint is hidden');
});

test('verifyNoNodeOutput accepts required no-node transform files', async () => {
  await withTempOutput(async (out) => {
    await writeValidOutput(out);

    const result = await verifyNoNodeOutput({ out });

    assert.equal(result.ok, true);
    assert.deepEqual(result.errors, []);
  });
});

test('verifyNoNodeOutput rejects runtime Node artifacts', async () => {
  await withTempOutput(async (out) => {
    await writeValidOutput(out);
    await mkdir(path.join(out, 'sillytavern-web', 'node_modules'), { recursive: true });
    await writeFile(path.join(out, 'runtime-android-arm64-node24.zip'), 'legacy runtime', 'utf8');
    await writeFile(path.join(out, 'runtime-android-x86-node20.zip'), 'legacy runtime', 'utf8');

    await assert.rejects(
      verifyNoNodeOutput({ out }),
      /Forbidden runtime artifact/
    );
  });
});

test('verifyNoNodeOutput rejects browser libraries with bare module imports', async () => {
  await withTempOutput(async (out) => {
    await writeValidOutput(out);
    await writeFile(
      path.join(out, 'sillytavern-web', 'lib.js'),
      "import lodash from 'lodash';\nexport { lodash };\n",
      'utf8'
    );

    await assert.rejects(
      verifyNoNodeOutput({ out }),
      /Bare browser module specifier: lodash/
    );
  });
});

test('copyNoNodeWebAssets copies static Web files and excludes node_modules', async () => {
  await withTempOutput(async (out) => {
    const sourceWebRoot = path.join(out, 'source-public');
    const outWebRoot = path.join(out, 'sillytavern-web');
    await mkdir(path.join(sourceWebRoot, 'assets'), { recursive: true });
    await mkdir(path.join(sourceWebRoot, 'node_modules', 'bad'), { recursive: true });
    await writeFile(path.join(sourceWebRoot, 'index.html'), '<!doctype html>\n', 'utf8');
    await writeFile(path.join(sourceWebRoot, 'assets', 'app.js'), 'console.log("ok");\n', 'utf8');
    await writeFile(path.join(sourceWebRoot, 'node_modules', 'bad', 'index.js'), 'bad\n', 'utf8');

    await copyNoNodeWebAssets({ sourceWebRoot, outWebRoot });

    await access(path.join(outWebRoot, 'index.html'));
    await access(path.join(outWebRoot, 'assets', 'app.js'));
    await assert.rejects(access(path.join(outWebRoot, 'node_modules', 'bad', 'index.js')));

    const hash = await hashDirectory(outWebRoot);
    assert.match(hash, /^[a-f0-9]{64}$/);
  });
});

test('copyNoNodeWebSupportAssets injects the tested SAF export helper', async () => {
  await withTempOutput(async (out) => {
    const outWebRoot = path.join(out, 'sillytavern-web');
    await mkdir(path.join(outWebRoot, 'scripts'), { recursive: true });

    await copyNoNodeWebSupportAssets({ outWebRoot });

    assert.equal(
      await readFile(path.join(outWebRoot, 'scripts', 'stapk-export.js'), 'utf8'),
      await readFile(path.join(PROJECT_ROOT, 'transform', 'no-node', 'web', 'stapk-export.js'), 'utf8')
    );
    assert.equal(await readFile(path.join(outWebRoot, 'css', 'user.css'), 'utf8'), '');
    const transparentBackground = await readFile(path.join(outWebRoot, 'backgrounds', '__transparent.png'));
    assert.deepEqual([...transparentBackground.subarray(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10]);
  });
});

test('copyCapabilityRuntime exposes only core capabilities without build paths', async () => {
  await withTempOutput(async (root) => {
    const capabilityFile = path.join(root, 'capabilities.json');
    const outWebRoot = path.join(root, 'sillytavern-web');
    await writeFile(capabilityFile, JSON.stringify({
      schemaVersion: 1,
      capabilities: [
        { id: 'core.settings', kind: 'core' },
        { id: 'remote.image', kind: 'external_optional' },
        { id: 'excluded.extensions', kind: 'excluded' }
      ]
    }), 'utf8');

    await copyCapabilityRuntime({ capabilityFile, outWebRoot });

    const runtimeText = await readFile(path.join(outWebRoot, 'stapk-capabilities.json'), 'utf8');
    assert.deepEqual(JSON.parse(runtimeText), {
      schemaVersion: 1,
      capabilities: {
        'core.settings': true,
        'remote.image': false,
        'excluded.extensions': false
      }
    });
    assert.ok(!runtimeText.includes(root));
  });
});

test('verifyNoNodeOutput rejects a Web asset changed after manifest generation', async () => {
  await withTempOutput(async (out) => {
    await writeValidOutput(out);
    await writeFile(
      path.join(out, 'sillytavern-web', 'css', 'main.css'),
      '#catalog-end { color: red; display: block !important; }\n',
      'utf8'
    );

    await assert.rejects(
      verifyNoNodeOutput({ out }),
      /Web root SHA-256 mismatch/
    );
  });
});

test('verifyNoNodeOutput rejects patch queue source changed after manifest generation', async () => {
  await withTempOutput(async (out) => {
    const patchQueueDir = await mkdtemp(path.join(os.tmpdir(), 'stapk-patch-queue-'));
    try {
      await writeFile(path.join(patchQueueDir, 'series'), 'sample.patch\n', 'utf8');
      await writeFile(path.join(patchQueueDir, 'sample.patch'), 'original patch\n', 'utf8');
      await writeValidOutput(out, { patchQueueDir });
      await writeFile(path.join(patchQueueDir, 'sample.patch'), 'changed patch\n', 'utf8');

      await assert.rejects(
        verifyNoNodeOutput({ out, patchQueueDir }),
        /Patch queue SHA-256 mismatch/
      );
    } finally {
      await rm(patchQueueDir, { recursive: true, force: true });
    }
  });
});

test('verifyNoNodeOutput accepts a published patch hash without local patch queue access', async () => {
  await withTempOutput(async (out) => {
    const patchQueueDir = path.join(out, 'missing-patch-queue');
    await writeValidOutput(out);
    const manifest = JSON.parse(await readFile(
      path.join(out, 'stapk-web-manifest.json'),
      'utf8'
    ));

    const result = await verifyNoNodeOutput({
      out,
      patchQueueDir,
      expectedPatchQueueSha256: manifest.hashes.patchQueueSha256,
    });

    assert.equal(result.ok, true);
  });
});

test('copyUiCapabilityContract validates and publishes the formal UI contract', async () => {
  assert.equal(
    typeof transformModule.copyUiCapabilityContract,
    'function',
    'transform must export copyUiCapabilityContract'
  );

  await withTempOutput(async (root) => {
    const uiCapabilityFile = path.join(root, 'ui-capabilities.json');
    const capabilityFile = path.join(root, 'capabilities.json');
    const outWebRoot = path.join(root, 'sillytavern-web');
    const uiContract = {
      schemaVersion: 1,
      hiddenStylesheets: [{ path: 'css/stapk-mobile.css', catalogBefore: '#catalog-end' }],
      implementedActions: [],
      hiddenSelectors: []
    };
    await Promise.all([
      writeFile(uiCapabilityFile, JSON.stringify(uiContract), 'utf8'),
      writeFile(capabilityFile, JSON.stringify({ schemaVersion: 1, capabilities: [] }), 'utf8')
    ]);

    await transformModule.copyUiCapabilityContract({
      uiCapabilityFile,
      capabilityFile,
      outWebRoot
    });

    assert.deepEqual(
      JSON.parse(await readFile(path.join(outWebRoot, 'stapk-ui-capabilities.json'), 'utf8')),
      uiContract
    );
  });
});

test('copyDefaultPresets includes upstream OpenAI defaults in the generated Web root', async () => {
  await withTempOutput(async (root) => {
    const patchedDir = path.join(root, 'patched');
    const outWebRoot = path.join(root, 'sillytavern-web');
    await mkdir(path.join(patchedDir, 'default', 'content', 'presets', 'openai'), { recursive: true });
    await writeFile(
      path.join(patchedDir, 'default', 'content', 'presets', 'openai', 'starter.json'),
      '{"temperature":0.7}\n',
      'utf8'
    );

    await transformModule.copyDefaultPresets({ patchedDir, outWebRoot });

    assert.equal(
      await readFile(path.join(outWebRoot, 'defaults', 'presets', 'openai', 'starter.json'), 'utf8'),
      '{"temperature":0.7}\n'
    );
  });
});

test('syncNoNodeAndroidAssets replaces Web output and removes legacy Node assets', async () => {
  await withTempOutput(async (root) => {
    const transformOut = path.join(root, 'transform-out');
    const androidAssetsDir = path.join(root, 'android-assets');
    await mkdir(transformOut, { recursive: true });
    await writeValidOutput(transformOut);
    await writeFile(path.join(transformOut, 'sillytavern-web', 'app.js'), 'new web asset\n', 'utf8');
    await refreshOutputHashes(transformOut);

    await mkdir(path.join(androidAssetsDir, 'sillytavern-web'), { recursive: true });
    await mkdir(path.join(androidAssetsDir, 'nested'), { recursive: true });
    await writeFile(path.join(androidAssetsDir, 'sillytavern-web', 'stale.js'), 'stale\n', 'utf8');
    await writeFile(path.join(androidAssetsDir, 'nested', 'runtime-android-x86-node20.zip'), 'legacy\n', 'utf8');
    await writeFile(path.join(androidAssetsDir, 'unrelated.txt'), 'stale\n', 'utf8');
    for (const legacyAsset of [
      'dummy-server.js',
      'payload-manifest.json',
      'payload.tgz',
      'runtime-android-arm64-node24.zip',
      'runtime-android-arm64-node24.zip.sha256'
    ]) {
      await writeFile(path.join(androidAssetsDir, legacyAsset), 'legacy\n', 'utf8');
    }

    await syncNoNodeAndroidAssets({ transformOut, androidAssetsDir });

    assert.equal(
      await readFile(path.join(androidAssetsDir, 'sillytavern-web', 'app.js'), 'utf8'),
      'new web asset\n'
    );
    await access(path.join(androidAssetsDir, 'api-contract.json'));
    await access(path.join(androidAssetsDir, 'stapk-web-manifest.json'));
    await access(path.join(androidAssetsDir, 'transform-report.json'));
    await access(path.join(androidAssetsDir, 'sillytavern-web', 'stapk-capabilities.json'));
    await assert.rejects(access(path.join(androidAssetsDir, 'sillytavern-web', 'stale.js')));
    await assert.rejects(access(path.join(androidAssetsDir, 'nested')));
    await assert.rejects(access(path.join(androidAssetsDir, 'unrelated.txt')));

    for (const legacyAsset of [
      'dummy-server.js',
      'payload-manifest.json',
      'payload.tgz',
      'runtime-android-arm64-node24.zip',
      'runtime-android-arm64-node24.zip.sha256'
    ]) {
      await assert.rejects(access(path.join(androidAssetsDir, legacyAsset)));
    }
  });
});
