import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

test('Android shell uses dark system bars around the SillyTavern WebView', async () => {
  const [manifest, themes] = await Promise.all([
    readFile(path.join(PROJECT_ROOT, 'mobile/app/src/main/AndroidManifest.xml'), 'utf8'),
    readFile(path.join(PROJECT_ROOT, 'mobile/app/src/main/res/values/themes.xml'), 'utf8')
  ]);

  assert.match(manifest, /android:theme="@style\/Theme\.StAPKMobile"/);
  assert.match(themes, /<item name="android:windowBackground">#101010<\/item>/);
  assert.match(themes, /<item name="android:statusBarColor">#101010<\/item>/);
  assert.match(themes, /<item name="android:windowLightStatusBar">false<\/item>/);
  assert.match(themes, /<item name="android:navigationBarColor">#000000<\/item>/);
  assert.match(themes, /<item name="android:windowLightNavigationBar">false<\/item>/);
});
