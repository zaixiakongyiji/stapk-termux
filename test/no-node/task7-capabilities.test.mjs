import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../..', import.meta.url);

const nativeEndpoints = [
  'POST /api/backgrounds/all',
  'POST /api/backgrounds/folders',
  'POST /api/backgrounds/upload',
  'POST /api/backgrounds/rename',
  'POST /api/backgrounds/delete',
  'POST /api/files/sanitize-filename',
  'POST /api/files/upload',
  'POST /api/files/delete',
  'POST /api/files/verify',
  'POST /api/images/upload',
  'POST /api/images/list',
  'POST /api/images/folders',
  'POST /api/images/delete',
  'POST /api/image-metadata',
  'POST /api/image-metadata/all',
  'POST /api/image-metadata/cleanup',
  'POST /api/image-metadata/folders/get',
  'POST /api/image-metadata/folders/create',
  'POST /api/image-metadata/folders/update',
  'POST /api/image-metadata/folders/delete',
  'POST /api/image-metadata/folders/assign',
  'POST /api/image-metadata/folders/unassign',
  'POST /api/image-metadata/folders/set-thumbnails',
  'GET /api/sprites/get',
  'POST /api/sprites/upload',
  'POST /api/sprites/upload-zip',
  'POST /api/sprites/delete',
];

const frontendEndpoints = nativeEndpoints.filter(endpoint => ![
  'POST /api/image-metadata',
  'POST /api/image-metadata/cleanup',
  'POST /api/image-metadata/folders/get',
].includes(endpoint));

test('Task 7 native media endpoints are implemented and patch is sequenced', async () => {
  const [allowlistText, series] = await Promise.all([
    readFile(new URL('./transform/no-node/mvp-api-allowlist.json', root), 'utf8'),
    readFile(new URL('./patches/sillytavern-no-node/series', root), 'utf8'),
  ]);
  const allowlist = JSON.parse(allowlistText);
  const implemented = new Set(allowlist.implemented.map(({ method, path }) => `${method} ${path}`));

  nativeEndpoints.forEach(endpoint => assert.ok(implemented.has(endpoint), `missing ${endpoint}`));
  assert.match(series, /^0005-stapk-mobile-media-management\.patch$/m);
});

test('formal assets implement local media endpoints and expose only supported system extensions', async () => {
  const [contractText, html, mobileCss, extensions, backgrounds, expressionSettings, expressionScript, galleryScript] = await Promise.all([
    readFile(new URL('./mobile/app/src/main/assets/api-contract.json', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/index.html', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/css/stapk-mobile.css', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/extensions.js', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/backgrounds.js', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/extensions/expressions/settings.html', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/extensions/expressions/index.js', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/extensions/gallery/index.js', root), 'utf8'),
  ]);
  const contract = JSON.parse(contractText);
  const entries = new Map(contract.endpoints.map(item => [`${item.method} ${item.path}`, item]));

  frontendEndpoints.forEach(endpoint => assert.equal(entries.get(endpoint)?.status, 'implemented', endpoint));
  assert.match(extensions, /async function discoverExtensions\(\)/);
  assert.match(extensions, /const extensions = await discoverExtensions\(\);/);
  assert.match(html, /id="backgrounds-button"/);
  assert.match(html, /id="add_background_button_top"/);
  assert.match(html, /id="file_form_input"/);
  assert.match(html, /id="expressions_container"/);
  assert.match(expressionSettings, /id="expression_upload_pack_button"/);
  assert.match(expressionSettings, /id="expression_upload"/);
  assert.match(galleryScript, /id = 'show_gallery_wand_button'/);
  assert.match(galleryScript, /id: 'show_char_gallery'/);
  assert.match(mobileCss, /^\s*#extensions_settings \.extension_container:not\(#expressions_container\),?\s*$/m);
  assert.match(mobileCss, /^\s*#extensions_settings2 \.extension_container:not\(#qr_container\):not\(#regex_container\):not\(#summarize_container\):not\(#vectors_container\),?\s*$/m);
  assert.doesNotMatch(mobileCss, /^\s*#extensions_settings,?\s*$/m);
  assert.match(expressionScript, /extension_settings\.expressions\.api = EXPRESSION_API\.none;/);
  assert.match(mobileCss, /^\s*label\[for="expression_override"\],?\s*$/m);
  assert.match(mobileCss, /#stapk-video-background/);
  assert.match(backgrounds, /const VIDEO_BACKGROUND_EXTENSIONS = \['mp4', 'webm'\];/);
  assert.match(backgrounds, /function applyBackground\(url\)/);
  assert.match(backgrounds, /getVideoThumbnail\(url, THUMBNAIL_CONFIG\.width, THUMBNAIL_CONFIG\.height\)/);
  assert.doesNotMatch(backgrounds, /\$\('#bg1'\)\.css\('background-image'/);
  assert.doesNotMatch(backgrounds, /Video Background Loader extension/);
});

test('automatic classification remote image and converter extension remain unavailable', async () => {
  const [contractText, mobileCss, extensions] = await Promise.all([
    readFile(new URL('./mobile/app/src/main/assets/api-contract.json', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/css/stapk-mobile.css', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/extensions.js', root), 'utf8'),
  ]);
  const contract = JSON.parse(contractText);
  const classify = contract.endpoints.filter(item => item.path.includes('classify'));
  const remoteImage = contract.endpoints.filter(item => item.capability === 'remote.image');

  assert.ok(classify.length > 0);
  classify.forEach(item => assert.equal(item.status, 'unsupported_hidden', item.path));
  assert.ok(remoteImage.length > 0);
  remoteImage.forEach(item => assert.equal(item.status, 'external_optional', item.path));
  assert.match(mobileCss, /^\s*\.expression_api_block,?\s*$/m);
  assert.match(mobileCss, /^\s*#sd_container,?\s*$/m);
  assert.doesNotMatch(extensions, /'caption'|'image-generation'|'sd'|'video'/);
});
