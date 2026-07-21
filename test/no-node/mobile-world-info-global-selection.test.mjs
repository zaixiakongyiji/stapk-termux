import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../..', import.meta.url);

function extractBlock(source, openingBraceIndex) {
  assert.equal(source[openingBraceIndex], '{', 'expected block opening brace');

  let depth = 0;
  let quote = null;

  for (let index = openingBraceIndex; index < source.length; index += 1) {
    const character = source[index];
    const nextCharacter = source[index + 1];

    if (quote) {
      if (character === '\\') {
        index += 1;
      } else if (character === quote) {
        quote = null;
      }
      continue;
    }

    if (character === '/' && nextCharacter === '/') {
      index = source.indexOf('\n', index + 2);
      if (index === -1) break;
      continue;
    }

    if (character === '/' && nextCharacter === '*') {
      index = source.indexOf('*/', index + 2);
      if (index === -1) break;
      index += 1;
      continue;
    }

    if (character === '\'' || character === '"' || character === '`') {
      quote = character;
    } else if (character === '{') {
      depth += 1;
    } else if (character === '}') {
      depth -= 1;
      if (depth === 0) {
        return { source: source.slice(openingBraceIndex, index + 1), end: index + 1 };
      }
    }
  }

  assert.fail('unterminated block');
}

function extractFunction(source, name) {
  const declaration = new RegExp(`(?:export\\s+)?function\\s+${name}\\s*\\([^)]*\\)\\s*\\{`);
  const match = declaration.exec(source);
  assert.ok(match, `missing function ${name}()`);

  const openingBraceIndex = match.index + match[0].lastIndexOf('{');
  return extractBlock(source, openingBraceIndex);
}

function getBraceDepthAt(source, position) {
  let depth = 0;
  let quote = null;

  for (let index = 0; index < position; index += 1) {
    const character = source[index];
    const nextCharacter = source[index + 1];

    if (quote) {
      if (character === '\\') {
        index += 1;
      } else if (character === quote) {
        quote = null;
      }
      continue;
    }

    if (character === '/' && nextCharacter === '/') {
      index = source.indexOf('\n', index + 2);
      if (index === -1) break;
      continue;
    }

    if (character === '/' && nextCharacter === '*') {
      index = source.indexOf('*/', index + 2);
      if (index === -1) break;
      index += 1;
      continue;
    }

    if (character === '\'' || character === '"' || character === '`') {
      quote = character;
    } else if (character === '{') {
      depth += 1;
    } else if (character === '}') {
      depth -= 1;
    }
  }

  return depth;
}

function findWorldInfoDesktopGuard(initWorldInfo) {
  const guards = [...initWorldInfo.matchAll(/if \(!isMobile\(\)\) \{/g)]
    .map(match => {
      const openingBraceIndex = match.index + match[0].lastIndexOf('{');
      const block = extractBlock(initWorldInfo, openingBraceIndex);
      return { ...block, start: match.index };
    })
    .filter(guard => guard.source.includes('#world_editor_select'));

  assert.equal(guards.length, 1, 'expected one desktop guard for the World Info editor');
  return guards[0];
}

test('global World Info selector initializes Select2 outside the desktop-only guard', async () => {
  const source = await readFile(
    new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/world-info.js', root),
    'utf8',
  );

  const initWorldInfo = extractFunction(source, 'initWorldInfo');
  const desktopOnly = findWorldInfoDesktopGuard(initWorldInfo.source);
  assert.match(desktopOnly.source, /\$\('#world_editor_select'\)\.select2\(/);
  const allEditorInitializers = [...source.matchAll(/\$\('#world_editor_select'\)\.select2\(/g)];
  assert.equal(allEditorInitializers.length, 1, 'world-info.js must initialize the editor Select2 once');
  const editorInitializers = [...initWorldInfo.source.matchAll(/\$\('#world_editor_select'\)\.select2\(/g)];
  assert.equal(editorInitializers.length, 1, 'initWorldInfo() must initialize the editor Select2 once');
  assert.ok(
    editorInitializers[0].index >= desktopOnly.start && editorInitializers[0].index < desktopOnly.end,
    'initWorldInfo() must keep the editor Select2 inside the desktop-only guard',
  );

  const globalInitializer = extractFunction(source, 'initializeGlobalWorldInfoSelector');
  assert.match(globalInitializer.source, /\$\('#world_info'\)\.select2\(/);
  assert.match(globalInitializer.source, /closeOnSelect: false/);
  assert.match(globalInitializer.source, /select2ChoiceClickSubscribe\(\$\('#world_info'\)/);

  const initializerCall = /(?:^|\n)\s*initializeGlobalWorldInfoSelector\(\);/g.exec(initWorldInfo.source);
  assert.ok(initializerCall, 'initWorldInfo() must call initializeGlobalWorldInfoSelector()');
  assert.ok(
    initializerCall.index < desktopOnly.start || initializerCall.index >= desktopOnly.end,
    'initWorldInfo() must call initializeGlobalWorldInfoSelector() outside the desktop-only guard',
  );
  assert.equal(
    getBraceDepthAt(initWorldInfo.source, initializerCall.index),
    1,
    'initWorldInfo() must call initializeGlobalWorldInfoSelector() at its direct function level',
  );
  assert.doesNotMatch(desktopOnly.source, /#world_info['"]\)\.select2/);
});

test('World Info settings continue to persist under world_info_settings', async () => {
  const [script, worldInfo] = await Promise.all([
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/script.js', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/world-info.js', root), 'utf8'),
  ]);

  assert.match(script, /world_info_settings: getWorldInfoSettings\(\)/);
  const settingsGetter = extractFunction(worldInfo, 'getWorldInfoSettings');
  const returnStart = settingsGetter.source.indexOf('return {');
  assert.notEqual(returnStart, -1, 'getWorldInfoSettings() must return an object');
  const returnedSettings = extractBlock(settingsGetter.source, returnStart + 'return '.length);
  assert.match(returnedSettings.source, /(?:^|[,{])\s*world_info\s*(?=,|})/);

  const saveSettingsDeclaration = 'const saveSettingsDebounced = debounce(() => {';
  const saveSettingsStart = worldInfo.indexOf(saveSettingsDeclaration);
  assert.notEqual(saveSettingsStart, -1, 'missing saveSettingsDebounced callback');
  const saveSettingsBlock = extractBlock(
    worldInfo,
    saveSettingsStart + saveSettingsDeclaration.length - 1,
  );
  assert.match(saveSettingsBlock.source, /Object\.assign\(world_info, \{ globalSelect: selected_world_info \}\)/);
  assert.match(saveSettingsBlock.source, /saveSettings\(\);/);
  assert.match(worldInfo, /settings\.world_info\?\.globalSelect\?\.filter\(/);
});
