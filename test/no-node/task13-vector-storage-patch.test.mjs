import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, cp, mkdir } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

const execFileAsync = promisify(execFile);
const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const PATCH_NAME = '0012-stapk-mobile-remote-embedding-vector-storage.patch';
const PATCH_PATH = path.join(PROJECT_ROOT, 'patches/sillytavern-no-node', PATCH_NAME);
const PATCH_BASELINE = path.join(PROJECT_ROOT, 'build/stapk-no-node/patched');

async function applyVectorPatchFixture() {
    const root = await mkdtemp(path.join(os.tmpdir(), 'stapk-vector-patch-'));
    const fixture = path.join(root, 'patched');
    await mkdir(fixture, { recursive: true });
    await Promise.all([
        cp(path.join(PATCH_BASELINE, 'public/scripts/extensions/vectors'), path.join(fixture, 'public/scripts/extensions/vectors'), { recursive: true }),
        cp(path.join(PATCH_BASELINE, 'public/css/stapk-mobile.css'), path.join(fixture, 'public/css/stapk-mobile.css')),
    ]);
    await execFileAsync('git', ['init', '--quiet'], { cwd: fixture });
    await execFileAsync('git', ['add', '.'], { cwd: fixture });
    await execFileAsync('git', ['-c', 'user.name=stapk', '-c', 'user.email=stapk@localhost', 'commit', '--quiet', '-m', 'baseline'], { cwd: fixture });
    await execFileAsync('git', ['apply', '--reverse', '--check', PATCH_PATH], { cwd: fixture });
    await execFileAsync('git', ['apply', '--reverse', PATCH_PATH], { cwd: fixture });
    await execFileAsync('git', ['apply', '--check', PATCH_PATH], { cwd: fixture });
    await execFileAsync('git', ['apply', PATCH_PATH], { cwd: fixture });
    return { root, fixture };
}

test('Vector Storage fixed patch applies and exposes only controlled embedding providers', async () => {
    const { root, fixture } = await applyVectorPatchFixture();
    try {
        const html = await readFile(path.join(fixture, 'public/scripts/extensions/vectors/settings.html'), 'utf8');
        const selector = html.match(/<select id="vectors_source"[^>]*>([\s\S]*?)<\/select>/);
        assert.ok(selector, 'vectors source selector is present');
        assert.deepEqual(
            [...selector[1].matchAll(/<option[^>]+value="([^"]+)"/g)].map((match) => match[1]),
            ['openai', 'stapk_openai_compatible'],
        );
        assert.match(html, /id="stapk_embedding_base_url"/);
        assert.match(html, /id="stapk_embedding_api_key"[^>]+type="password"/);
        assert.match(html, /id="stapk_embedding_save"/);
        assert.match(html, /id="stapk_embedding_test"/);
    } finally {
        await rm(root, { recursive: true, force: true });
    }
});

test('Vector Storage fixed patch keeps URL and key out of vector requests and fails closed before configuration', async () => {
    const { root, fixture } = await applyVectorPatchFixture();
    try {
        const [script, css] = await Promise.all([
            readFile(path.join(fixture, 'public/scripts/extensions/vectors/index.js'), 'utf8'),
            readFile(path.join(fixture, 'public/css/stapk-mobile.css'), 'utf8'),
        ]);
        const requestBody = script.match(/function getVectorsRequestBody\(args = \{\}\) \{([\s\S]*?)\n\}/);
        assert.ok(requestBody, 'vector request body helper is present');
        assert.match(requestBody[1], /case 'openai':/);
        assert.match(requestBody[1], /case 'stapk_openai_compatible':/);
        assert.match(requestBody[1], /body\.model = getStapkEmbeddingModel\(\);/);
        assert.doesNotMatch(requestBody[1], /(?:apiKey|api_key|baseUrl|base_url|apiUrl|api_url)/);
        assert.match(script, /\/api\/stapk\/embeddings\/config\/get/);
        assert.match(script, /\/api\/stapk\/embeddings\/config\/save/);
        assert.match(script, /\/api\/stapk\/embeddings\/test/);
        assert.match(script, /await window\.stapkCapabilitiesReady/);
        assert.match(script, /window\.isStapkCapabilityAvailable\('remote\.embeddings'\)/);
        assert.match(script, /stapk_embedding_privacy_acknowledged/);
        assert.match(script, /allowStapkEmbeddingToggle\('#vectors_enabled_chats'\)/);
        assert.match(script, /allowStapkEmbeddingToggle\('#vectors_enabled_files'\)/);
        assert.match(script, /allowStapkEmbeddingToggle\('#vectors_enabled_world_info'\)/);
        assert.doesNotMatch(css, /#vectors_container\s*\{/);
        assert.match(css, /extension_container:not\(#qr_container\):not\(#regex_container\):not\(#summarize_container\):not\(#vectors_container\)/);
    } finally {
        await rm(root, { recursive: true, force: true });
    }
});

test('Vector Storage patch stages model changes and rechecks capability before save, test, and enable', async () => {
    const { root, fixture } = await applyVectorPatchFixture();
    try {
        const script = await readFile(path.join(fixture, 'public/scripts/extensions/vectors/index.js'), 'utf8');
        const modelChange = script.match(/\$\('#vectors_openai_model'\)[\s\S]*?\.on\('change', \(\) => \{([\s\S]*?)\n    \}\);/);
        assert.ok(modelChange, 'OpenAI model change handler is present');
        assert.doesNotMatch(modelChange[1], /settings\.openai_model|Object\.assign\(extension_settings\.vectors|saveSettingsDebounced/);
        assert.match(script, /async function ensureStapkEmbeddingCapability\(\)/);
        assert.match(script, /async function saveStapkEmbeddingConfig\(\) \{\s*if \(!await ensureStapkEmbeddingCapability\(\)\)/);
        assert.match(script, /await saveStapkEmbeddingConfig\(\);\s*if \(!await ensureStapkEmbeddingCapability\(\)\)/);
        assert.match(script, /async function allowStapkEmbeddingToggle\(selector\) \{\s*if \(!await ensureStapkEmbeddingCapability\(\)\)/);
        assert.doesNotMatch(script, /await saveStapkEmbeddingConfig\(\);\s*stapkEmbeddingsReady = true/);
    } finally {
        await rm(root, { recursive: true, force: true });
    }
});

test('Vector Storage init disables imported remote toggles until privacy is acknowledged', async () => {
    const { root, fixture } = await applyVectorPatchFixture();
    try {
        const script = await readFile(path.join(fixture, 'public/scripts/extensions/vectors/index.js'), 'utf8');
        const initGuard = script.match(/await loadStapkEmbeddingConfig\(\);\s*if \(([\s\S]*?)\) \{\s*settings\.enabled_chats = false;/);
        assert.ok(initGuard, 'init fail-closed guard is present');
        const shouldDisable = new Function(
            'stapkEmbeddingsReady',
            'stapkEmbeddingConfig',
            'extension_settings',
            `return (${initGuard[1]});`,
        );

        const importedSettings = { vectors: { enabled_chats: true, enabled_files: true, enabled_world_info: true } };
        assert.equal(shouldDisable(true, { keyConfigured: true }, importedSettings), true);
        importedSettings.vectors.stapk_embedding_privacy_acknowledged = true;
        assert.equal(shouldDisable(true, { keyConfigured: true }, importedSettings), false);
    } finally {
        await rm(root, { recursive: true, force: true });
    }
});
