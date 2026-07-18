import assert from 'node:assert/strict';
import test from 'node:test';

import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';

import { createStapkEmulatorMcp } from '../../scripts/mcp/stapk-emulator-mcp.mjs';

test('MCP exposes the complete Pixel_8 lifecycle and returns structured status', async () => {
  const lifecycle = {
    status: async () => ({ avd: 'Pixel_8', serial: 'emulator-5554', state: 'ready' }),
    start: async () => ({ avd: 'Pixel_8', serial: 'emulator-5554', state: 'ready', started: false }),
    ensureStarted: async () => ({ avd: 'Pixel_8', serial: 'emulator-5554', state: 'ready', started: false }),
    stop: async () => ({ avd: 'Pixel_8', serial: null, state: 'stopped', stopped: true }),
    restart: async () => ({ avd: 'Pixel_8', serial: 'emulator-5554', state: 'ready', restarted: true }),
  };
  const server = createStapkEmulatorMcp(lifecycle);
  const client = new Client({ name: 'stapk-emulator-test', version: '1.0.0' });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();

  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
  try {
    const tools = await client.listTools();
    assert.deepEqual(
      tools.tools.map((tool) => tool.name).sort(),
      [
        'stapk_emulator_ensure_started',
        'stapk_emulator_restart',
        'stapk_emulator_start',
        'stapk_emulator_status',
        'stapk_emulator_stop',
      ],
    );

    const response = await client.callTool({ name: 'stapk_emulator_status', arguments: {} });
    assert.equal(response.isError, undefined);
    assert.deepEqual(JSON.parse(response.content[0].text), {
      avd: 'Pixel_8',
      serial: 'emulator-5554',
      state: 'ready',
    });
  } finally {
    await client.close();
    await server.close();
  }
});
