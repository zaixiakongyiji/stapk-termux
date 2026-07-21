# 移动端全局世界书选择修复实施计划

> **执行要求：** 实施时必须使用 `superpowers:test-driven-development`，逐项勾选步骤。项目规范禁止自动执行 Git commit/push；每个任务仅给出建议提交信息，由用户决定是否手动提交。

**目标：** 将移动端裸露的 `<select multiple>` 替换为可明确选择和取消的统一多选 UI，并验证全局世界书状态写入正确的 `settings.world_info_settings.world_info.globalSelect`。

**架构：** 修复必须进入 `patches/sillytavern-no-node/` patch queue，不直接把生成后的 `mobile/app/src/main/assets/sillytavern-web/` 当作源码。桌面端继续使用现有 Select2；移动端仅对全局世界书选择器启用同一 Select2 多选控件，世界书编辑器下拉框仍保留原生移动端控件。

**技术栈：** SillyTavern Web UI、JavaScript、jQuery、Select2、Node.js 20+ 构建期 transform、Node test runner、Android WebView。

## 全局约束

- 当前主线仅修改 no-node patch queue、测试和生成资产；不得修改废弃的 Termux 路线。
- 不直接手改 `mobile/app/src/main/assets/sillytavern-web/scripts/world-info.js` 后结束任务；最终结果必须可由 transform 重建。
- 不改变世界书导入、25 条数据规范化、角色绑定世界书或聊天绑定世界书语义。
- 移动端选择器必须是紧凑控件，不再直接显示“未找到世界书”和全部 option 列表。
- 选中后必须可见、可取消；取消后不得残留假选中状态。
- 持久化验收字段固定为 `settings.world_info_settings.world_info.globalSelect`，不得再检查旧的顶层 `settings.world_info`。
- 保持桌面端现有 Select2 行为；移动端 `#world_editor_select` 保持原生单选下拉。
- 不新增前端依赖；使用上游已打包的 Select2。
- 禁止自动执行 Git commit/push。建议提交信息仅作参考。

## 文件结构

- Create: `patches/sillytavern-no-node/0011-stapk-mobile-world-info-global-selector.patch`
  - 将全局世界书 Select2 初始化从桌面条件分支中提取并在移动端启用。
- Modify: `patches/sillytavern-no-node/series`
  - 在 `0010` 后登记新 patch。
- Create: `test/no-node/mobile-world-info-global-selection.test.mjs`
  - 固定生成资产中的初始化位置、移动端行为和持久化字段契约。
- Regenerate: `mobile/app/src/main/assets/sillytavern-web/scripts/world-info.js`
  - 由 `npm run transform:no-node` 生成，不手工维护。
- Regenerate: `mobile/app/src/main/assets/stapk-web-manifest.json`
- Regenerate: `mobile/app/src/main/assets/transform-report.json`
  - patch queue hash 和 patch 名称必须同步更新。

---

### 任务 1：先固定移动端选择器合同

**Files:**
- Create: `test/no-node/mobile-world-info-global-selection.test.mjs`

**Interfaces:**
- Consumes: 生成资产 `mobile/app/src/main/assets/sillytavern-web/scripts/world-info.js`
- Produces: 对 `initializeGlobalWorldInfoSelector()`、无条件调用和正确设置字段的静态合同测试。

- [x] **步骤 1：写失败测试，证明当前移动端跳过 Select2**

创建测试文件：

```javascript
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../..', import.meta.url);

test('global World Info selector initializes Select2 outside the desktop-only guard', async () => {
  const source = await readFile(
    new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/world-info.js', root),
    'utf8',
  );

  assert.match(source, /function initializeGlobalWorldInfoSelector\(\)/);
  assert.match(source, /initializeGlobalWorldInfoSelector\(\);/);

  const desktopOnly = source.match(/if \(!isMobile\(\)\) \{([\s\S]*?)\n    \}/)?.[1] ?? '';
  assert.match(desktopOnly, /#world_editor_select/);
  assert.doesNotMatch(desktopOnly, /#world_info['"]\)\.select2/);

  const globalInitializer = source.match(
    /function initializeGlobalWorldInfoSelector\(\) \{[\s\S]*?\n\}/,
  )?.[0] ?? '';
  assert.match(globalInitializer, /\$\('#world_info'\)\.select2\(/);
  assert.match(globalInitializer, /closeOnSelect: false/);
  assert.match(globalInitializer, /select2ChoiceClickSubscribe\(\$\('#world_info'\)/);
});

test('World Info settings continue to persist under world_info_settings', async () => {
  const [script, worldInfo] = await Promise.all([
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/script.js', root), 'utf8'),
    readFile(new URL('./mobile/app/src/main/assets/sillytavern-web/scripts/world-info.js', root), 'utf8'),
  ]);

  assert.match(script, /world_info_settings: getWorldInfoSettings\(\)/);
  assert.match(worldInfo, /Object\.assign\(world_info, \{ globalSelect: selected_world_info \}\)/);
  assert.match(worldInfo, /saveSettings\(\);/);
});
```

- [x] **步骤 2：运行测试并确认先失败**

Run:

```powershell
node --test test/no-node/mobile-world-info-global-selection.test.mjs
```

Expected: FAIL，缺少 `initializeGlobalWorldInfoSelector()`，且 `#world_info.select2()` 仍位于 `!isMobile()` 条件内。

- [x] **步骤 3：阶段检查，不执行 Git 提交**

Run:

```powershell
git diff --check
```

---

### 任务 2：通过 patch queue 启用统一全局多选控件

**Files:**
- Create: `patches/sillytavern-no-node/0011-stapk-mobile-world-info-global-selector.patch`
- Modify: `patches/sillytavern-no-node/series`
- Regenerate: `mobile/app/src/main/assets/sillytavern-web/scripts/world-info.js`
- Regenerate: `mobile/app/src/main/assets/stapk-web-manifest.json`
- Regenerate: `mobile/app/src/main/assets/transform-report.json`

**Interfaces:**
- Produces: `initializeGlobalWorldInfoSelector(): void`
- Preserves: `onWorldInfoChange('__notSlashCommand__')` 作为 `change` 事件后的唯一状态同步入口。

- [x] **步骤 1：在临时 patched tree 中实现目标结构**

将 `#world_editor_select` 保留在桌面条件分支，把 `#world_info` 初始化与 choice click 订阅提取为以下函数：

```javascript
function initializeGlobalWorldInfoSelector() {
    $('#world_info').select2({
        width: '100%',
        placeholder: t`No Worlds active. Click here to select.`,
        allowClear: true,
        closeOnSelect: false,
    });

    select2ChoiceClickSubscribe($('#world_info'), target => {
        const name = $(target).text();
        const selectedIndex = world_names.indexOf(name);
        const alreadySelectedInEditor = $('#world_editor_select option:selected').text() === name;
        if (selectedIndex !== -1 && !alreadySelectedInEditor) {
            $('#world_editor_select').val(selectedIndex).trigger('change');
            console.log('Quick selection of world', name);
        } else {
            console.warn('lets not reload an already loaded list yes?');
        }
    }, { buttonStyle: true, closeDrawer: true });
}
```

`initWorldInfo()` 中的初始化结构必须为：

```javascript
if (!isMobile()) {
    $('#world_editor_select').select2({
        placeholder: t`--- Pick to Edit ---`,
        searchInputPlaceholder: t`Search...`,
        allowClear: true,
        closeOnSelect: true,
        multiple: false,
    });
}

initializeGlobalWorldInfoSelector();
```

不得把 `#world_editor_select` 一并强制改成移动端 Select2。

- [x] **步骤 2：生成独立 0011 patch 并登记 series**

新 patch 只允许修改 `public/scripts/world-info.js`。在 `series` 末尾增加：

```text
0011-stapk-mobile-world-info-global-selector.patch
```

新 patch 的 diff 基线必须是当前 release 叠加 0001-0010 后的 `public/scripts/world-info.js`，不得针对生成资产路径制作 patch。完整可应用性统一由下一步 transform 验证。

- [x] **步骤 3：运行 transform 并同步 Android 资产**

Run:

```powershell
npm run transform:no-node
```

Expected:

- `0011-stapk-mobile-world-info-global-selector.patch` 成功应用。
- `mobile/app/src/main/assets/sillytavern-web/scripts/world-info.js` 包含目标函数。
- `transform-report.json` 的 patches 列表包含 0011。
- `stapk-web-manifest.json` 的 `patchQueueSha256` 更新。

- [x] **步骤 4：运行失败测试并确认转为通过**

Run:

```powershell
node --test test/no-node/mobile-world-info-global-selection.test.mjs
npm run transform:no-node:verify
```

Expected: PASS，transform verifier 输出通过。

- [x] **步骤 5：运行完整 no-node 测试**

Run:

```powershell
npm run test:no-node
```

Expected: 全部测试 PASS；现有 World Info 导入、角色内嵌世界书和 capability 合同无回归。

- [x] **步骤 6：阶段检查，不执行 Git 提交**

Run:

```powershell
git diff --check
git status --short
```

建议提交信息：`fix: 修复移动端全局世界书选择器`

---

### 任务 3：验证选中、取消和持久化

**Files:**
- Verify only: `output/`
- Verify only: 模拟器与真机设置

- [x] **步骤 1：构建 Debug APK**

Run:

```powershell
npm run build:no-node-apk -- --variant debug --ref release
```

Expected: transform、verifier、Node tests、Android JVM tests 和 APK 构建全部通过。

- [x] **步骤 2：在 Pixel 8 模拟器执行 UI 验收**

使用 `real-world-v7.82.json`：

1. 导入后显示 `写实世界V7.82`，条目数为 25。
2. “已启用的世界书（全局有效）”显示紧凑 Select2 占位符，不再平铺原生 option。
3. 点击控件出现可选列表。
4. 选择后显示明确的已选项。
5. 点击移除按钮后恢复空选择。
6. 世界书编辑下拉框仍能正常打开和编辑 25 条内容。

- [x] **步骤 3：使用正确嵌套字段验证持久化**

通过 `run-as` 直接读取测试设备的私有设置文件，不依赖随机 HTTP 端口：

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$settingsJson = & $adb exec-out run-as com.stapk.mobile cat files/user_config/settings.json
$settings = $settingsJson | ConvertFrom-Json
$settings.world_info_settings.world_info.globalSelect | ConvertTo-Json -Compress
```

Expected:

- 选中后：`["写实世界V7.82"]`
- 取消后：`[]`

- [x] **步骤 4：验证重启保持状态**

1. 选中 `写实世界V7.82`。
2. 正常关闭并重新启动 App，不清数据。
3. 确认 UI 仍显示已选中，API 字段仍为 `["写实世界V7.82"]`。
4. 取消选择，再次重启。
5. 确认 UI 为空，API 字段为 `[]`。

- [ ] **步骤 5：用户重新连接小米真机后复验**

1. 覆盖安装，不清数据。
2. 确认原来截图中的裸多选列表已消失。
3. 验证选择、取消和重启持久化。
4. 截图记录选择前、选择后、取消后三个状态。
5. 不删除用户已有世界书；测试完成后恢复用户原先的全局选择。

- [ ] **步骤 6：最终检查，不执行 Git 提交**

Run:

```powershell
git diff --check
git status --short
```

记录测试结果、APK 路径和 SHA256；由用户决定是否暂存或提交。

## 执行记录（2026-07-20）

- Pixel 8 / Android 15 上验证了紧凑 Select2、候选列表、选中 chip、单项移除和全部清空。
- `settings.world_info_settings.world_info.globalSelect` 选中时为 1 项 `写实世界V7.82`，清空时为 0 项；两种状态在强制终止并重启 App 后均保持。
- 截图保存在 `output/validation/2026-07-20-import-world-info/`，包含选择后重启、清空前重启和清空后重启状态。
- Debug APK 为 `output/stapk-mobile-debug.apk`，大小 25,347,122 bytes，SHA256 为 `c85ff682f824ef6d051f784e8bf84aeef08c9e4b20876fa13aa68ccf9e259986`。
- 小米真机选择、取消和重启持久化仍待用户重新连接设备后复验，测试完成后需恢复用户原先的全局选择。
