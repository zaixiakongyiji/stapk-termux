import { pathToFileURL } from 'node:url';

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';

import { EmulatorLifecycleError, createEmulatorLifecycle } from './stapk-emulator-core.mjs';

export function createStapkEmulatorMcp(lifecycle = createEmulatorLifecycle()) {
  const server = new McpServer({ name: 'stapk-emulator', version: '0.3.0' });

  registerLifecycleTool(
    server,
    'stapk_emulator_status',
    '查询 stAPK 使用的 Pixel_8 AVD 状态。',
    () => lifecycle.status(),
    { readOnlyHint: true },
  );
  registerLifecycleTool(
    server,
    'stapk_emulator_start',
    '启动可见的 Pixel_8 Emulator；已运行时幂等返回。',
    () => lifecycle.start(),
  );
  registerLifecycleTool(
    server,
    'stapk_emulator_ensure_started',
    '确保 Pixel_8 已完成 Android 启动，并返回实际设备 serial。',
    () => lifecycle.ensureStarted(),
  );
  registerLifecycleTool(
    server,
    'stapk_emulator_stop',
    '停止经 AVD 名称确认的 Pixel_8 Emulator，不影响其他设备。',
    () => lifecycle.stop(),
    { destructiveHint: true },
  );
  registerLifecycleTool(
    server,
    'stapk_emulator_restart',
    '安全停止并重新启动 Pixel_8 Emulator，等待系统 ready。',
    () => lifecycle.restart(),
    { destructiveHint: true },
  );

  return server;
}

function registerLifecycleTool(server, name, description, action, annotations = {}) {
  server.registerTool(
    name,
    {
      description,
      annotations: {
        idempotentHint: name !== 'stapk_emulator_restart',
        openWorldHint: false,
        ...annotations,
      },
    },
    async () => {
      try {
        return textResult(await action());
      } catch (error) {
        const code = error instanceof EmulatorLifecycleError ? error.code : 'unexpected_error';
        const message = error instanceof Error ? error.message : 'Emulator 操作失败';
        return {
          ...textResult({ error: code, message }),
          isError: true,
        };
      }
    },
  );
}

function textResult(value) {
  return {
    content: [{ type: 'text', text: JSON.stringify(value) }],
  };
}

async function main() {
  const server = createStapkEmulatorMcp();
  await server.connect(new StdioServerTransport());
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`stapk-emulator MCP 启动失败: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  });
}
