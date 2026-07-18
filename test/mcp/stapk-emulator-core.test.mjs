import assert from 'node:assert/strict';
import path from 'node:path';
import test from 'node:test';

import {
  EmulatorLifecycleError,
  createEmulatorLifecycle,
} from '../../scripts/mcp/stapk-emulator-core.mjs';

const SDK_ROOT = path.join('C:', 'Android', 'Sdk');

function result(stdout = '', code = 0, stderr = '') {
  return { stdout, stderr, code };
}

function commandName(command) {
  return path.basename(command).toLowerCase();
}

test('status identifies only the Pixel_8 emulator and reports ready', async () => {
  const calls = [];
  const lifecycle = createEmulatorLifecycle({
    sdkRoot: SDK_ROOT,
    run: async (command, args) => {
      calls.push([commandName(command), args]);
      if (args[0] === 'devices') {
        return result('List of devices attached\nemulator-5554\tdevice\nemulator-5556\tdevice\nphysical-1\tdevice\n');
      }
      if (args.join(' ') === '-s emulator-5554 emu avd name') return result('Other_AVD\nOK\n');
      if (args.join(' ') === '-s emulator-5556 emu avd name') return result('Pixel_8\nOK\n');
      if (args.join(' ') === '-s emulator-5556 shell getprop sys.boot_completed') return result('1\n');
      throw new Error(`unexpected command: ${args.join(' ')}`);
    },
  });

  assert.deepEqual(await lifecycle.status(), {
    avd: 'Pixel_8',
    serial: 'emulator-5556',
    state: 'ready',
  });
  assert.equal(calls.some(([, args]) => args.includes('physical-1')), false);
});

test('ensureStarted launches a visible Pixel_8 once and waits for boot completion', async () => {
  let spawned = false;
  let polls = 0;
  let now = 0;
  const spawnCalls = [];
  const lifecycle = createEmulatorLifecycle({
    sdkRoot: SDK_ROOT,
    clock: () => now,
    sleep: async (milliseconds) => { now += milliseconds; },
    run: async (command, args) => {
      if (commandName(command).startsWith('emulator') && args[0] === '-list-avds') return result('Pixel_8\n');
      if (args[0] === 'devices') {
        if (!spawned) return result('List of devices attached\n');
        polls += 1;
        return result('List of devices attached\nemulator-5554\tdevice\n');
      }
      if (args.join(' ') === '-s emulator-5554 emu avd name') return result('Pixel_8\nOK\n');
      if (args.join(' ') === '-s emulator-5554 shell getprop sys.boot_completed') {
        return result(polls >= 2 ? '1\n' : '0\n');
      }
      throw new Error(`unexpected command: ${args.join(' ')}`);
    },
    spawn: async (command, args) => {
      spawned = true;
      spawnCalls.push([commandName(command), args]);
    },
    pollIntervalMs: 100,
    bootTimeoutMs: 1_000,
  });

  assert.deepEqual(await lifecycle.ensureStarted(), {
    avd: 'Pixel_8',
    serial: 'emulator-5554',
    state: 'ready',
    started: true,
  });
  assert.deepEqual(spawnCalls, [['emulator.exe', ['-avd', 'Pixel_8']]]);
});

test('start is idempotent when Pixel_8 is already booting', async () => {
  let spawnCount = 0;
  const lifecycle = createEmulatorLifecycle({
    sdkRoot: SDK_ROOT,
    run: async (_command, args) => {
      if (args[0] === 'devices') return result('List of devices attached\nemulator-5554\toffline\n');
      if (args.join(' ') === '-s emulator-5554 emu avd name') return result('Pixel_8\nOK\n');
      throw new Error(`unexpected command: ${args.join(' ')}`);
    },
    spawn: async () => { spawnCount += 1; },
  });

  assert.deepEqual(await lifecycle.start(), {
    avd: 'Pixel_8',
    serial: 'emulator-5554',
    state: 'booting',
    started: false,
  });
  assert.equal(spawnCount, 0);
});

test('stop kills only a serial confirmed as Pixel_8', async () => {
  let killed = false;
  const calls = [];
  const lifecycle = createEmulatorLifecycle({
    sdkRoot: SDK_ROOT,
    sleep: async () => {},
    run: async (_command, args) => {
      calls.push(args.join(' '));
      if (args[0] === 'devices') {
        return result(killed
          ? 'List of devices attached\nemulator-5556\tdevice\n'
          : 'List of devices attached\nemulator-5554\tdevice\nemulator-5556\tdevice\n');
      }
      if (args.join(' ') === '-s emulator-5554 emu avd name') return result('Pixel_8\nOK\n');
      if (args.join(' ') === '-s emulator-5556 emu avd name') return result('Other_AVD\nOK\n');
      if (args.join(' ') === '-s emulator-5554 shell getprop sys.boot_completed') return result('1\n');
      if (args.join(' ') === '-s emulator-5554 emu kill') {
        killed = true;
        return result('OK\n');
      }
      throw new Error(`unexpected command: ${args.join(' ')}`);
    },
    stopTimeoutMs: 1_000,
  });

  assert.deepEqual(await lifecycle.stop(), {
    avd: 'Pixel_8',
    serial: null,
    state: 'stopped',
    stopped: true,
  });
  assert.equal(calls.includes('-s emulator-5556 emu kill'), false);
});

test('start rejects a missing Pixel_8 AVD before spawning', async () => {
  let spawnCount = 0;
  const lifecycle = createEmulatorLifecycle({
    sdkRoot: SDK_ROOT,
    run: async (command, args) => {
      if (args[0] === 'devices') return result('List of devices attached\n');
      if (commandName(command).startsWith('emulator') && args[0] === '-list-avds') return result('Pixel_7\n');
      throw new Error(`unexpected command: ${args.join(' ')}`);
    },
    spawn: async () => { spawnCount += 1; },
  });

  await assert.rejects(
    lifecycle.start(),
    (error) => error instanceof EmulatorLifecycleError && error.code === 'avd_not_found',
  );
  assert.equal(spawnCount, 0);
});

test('ensureStarted returns a stable timeout error when boot never completes', async () => {
  let spawned = false;
  let now = 0;
  const lifecycle = createEmulatorLifecycle({
    sdkRoot: SDK_ROOT,
    clock: () => now,
    sleep: async (milliseconds) => { now += milliseconds; },
    run: async (command, args) => {
      if (commandName(command).startsWith('emulator') && args[0] === '-list-avds') return result('Pixel_8\n');
      if (args[0] === 'devices') {
        return result(spawned ? 'List of devices attached\nemulator-5554\tdevice\n' : 'List of devices attached\n');
      }
      if (args.join(' ') === '-s emulator-5554 emu avd name') return result('Pixel_8\nOK\n');
      if (args.join(' ') === '-s emulator-5554 shell getprop sys.boot_completed') return result('0\n');
      throw new Error(`unexpected command: ${args.join(' ')}`);
    },
    spawn: async () => { spawned = true; },
    pollIntervalMs: 100,
    bootTimeoutMs: 250,
  });

  await assert.rejects(
    lifecycle.ensureStarted(),
    (error) => error instanceof EmulatorLifecycleError && error.code === 'boot_timeout',
  );
});
