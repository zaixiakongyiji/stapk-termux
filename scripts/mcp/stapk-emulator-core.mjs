import { execFile, spawn as spawnProcess } from 'node:child_process';
import path from 'node:path';

const TARGET_AVD = 'Pixel_8';

export class EmulatorLifecycleError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'EmulatorLifecycleError';
    this.code = code;
  }
}

export function createEmulatorLifecycle(options = {}) {
  const sdkRoot = resolveAndroidSdkRoot(options);
  const adb = path.join(sdkRoot, 'platform-tools', executableName('adb'));
  const emulator = path.join(sdkRoot, 'emulator', executableName('emulator'));
  const run = options.run ?? runCommand;
  const spawn = options.spawn ?? spawnDetached;
  const sleep = options.sleep ?? delay;
  const clock = options.clock ?? Date.now;
  const pollIntervalMs = options.pollIntervalMs ?? 1_000;
  const bootTimeoutMs = options.bootTimeoutMs ?? 180_000;
  const stopTimeoutMs = options.stopTimeoutMs ?? 30_000;

  async function status() {
    const devices = await listEmulators();
    for (const device of devices) {
      const avd = await avdName(device.serial);
      if (avd !== TARGET_AVD) continue;
      if (device.adbState !== 'device') {
        return stateResult('booting', device.serial);
      }
      const boot = await execute(adb, ['-s', device.serial, 'shell', 'getprop', 'sys.boot_completed']);
      return stateResult(boot.code === 0 && boot.stdout.trim() === '1' ? 'ready' : 'booting', device.serial);
    }
    return stateResult('stopped', null);
  }

  async function start() {
    const current = await status();
    if (current.state !== 'stopped') return { ...current, started: false };
    await assertAvdExists();
    try {
      await spawn(emulator, ['-avd', TARGET_AVD]);
    } catch {
      throw new EmulatorLifecycleError('start_failed', `无法启动 ${TARGET_AVD}`);
    }
    const ready = await waitForReady(bootTimeoutMs);
    return { ...ready, started: true };
  }

  async function ensureStarted() {
    const current = await status();
    if (current.state === 'ready') return { ...current, started: false };
    if (current.state === 'booting') {
      const ready = await waitForReady(bootTimeoutMs);
      return { ...ready, started: false };
    }
    return start();
  }

  async function stop() {
    const current = await status();
    if (current.state === 'stopped') return { ...current, stopped: false };
    const confirmedName = await avdName(current.serial);
    if (confirmedName !== TARGET_AVD) {
      throw new EmulatorLifecycleError('target_changed', '目标 Emulator 身份校验失败');
    }
    const killed = await execute(adb, ['-s', current.serial, 'emu', 'kill']);
    if (killed.code !== 0) {
      throw new EmulatorLifecycleError('stop_failed', `无法停止 ${TARGET_AVD}`);
    }
    const stopped = await waitForStopped(stopTimeoutMs);
    return { ...stopped, stopped: true };
  }

  async function restart() {
    await stop();
    const started = await ensureStarted();
    return { ...started, restarted: true };
  }

  async function listEmulators() {
    const response = await execute(adb, ['devices']);
    if (response.code !== 0) {
      throw new EmulatorLifecycleError('adb_failed', 'ADB 无法列出设备');
    }
    return response.stdout
      .split(/\r?\n/u)
      .map((line) => line.trim().split(/\s+/u))
      .filter(([serial, adbState]) => serial?.startsWith('emulator-') && adbState)
      .map(([serial, adbState]) => ({ serial, adbState }));
  }

  async function avdName(serial) {
    const response = await execute(adb, ['-s', serial, 'emu', 'avd', 'name']);
    if (response.code !== 0) return null;
    return response.stdout
      .split(/\r?\n/u)
      .map((line) => line.trim())
      .find((line) => line && line !== 'OK') ?? null;
  }

  async function assertAvdExists() {
    const response = await execute(emulator, ['-list-avds']);
    const avds = response.stdout.split(/\r?\n/u).map((line) => line.trim()).filter(Boolean);
    if (response.code !== 0 || !avds.includes(TARGET_AVD)) {
      throw new EmulatorLifecycleError('avd_not_found', `未找到 AVD ${TARGET_AVD}`);
    }
  }

  async function waitForReady(timeoutMs) {
    const deadline = clock() + timeoutMs;
    while (clock() <= deadline) {
      const current = await status();
      if (current.state === 'ready') return current;
      await sleep(pollIntervalMs);
    }
    throw new EmulatorLifecycleError('boot_timeout', `${TARGET_AVD} 启动超时`);
  }

  async function waitForStopped(timeoutMs) {
    const deadline = clock() + timeoutMs;
    while (clock() <= deadline) {
      const current = await status();
      if (current.state === 'stopped') return current;
      await sleep(pollIntervalMs);
    }
    throw new EmulatorLifecycleError('stop_timeout', `${TARGET_AVD} 停止超时`);
  }

  async function execute(command, args) {
    try {
      return await run(command, args);
    } catch {
      return { stdout: '', stderr: '', code: 1 };
    }
  }

  return { status, start, ensureStarted, stop, restart };
}

function stateResult(state, serial) {
  return { avd: TARGET_AVD, serial, state };
}

function resolveAndroidSdkRoot(options) {
  if (options.sdkRoot) return options.sdkRoot;
  const env = options.env ?? process.env;
  const candidates = [
    env.ANDROID_SDK_ROOT,
    env.ANDROID_HOME,
    env.LOCALAPPDATA && path.join(env.LOCALAPPDATA, 'Android', 'Sdk'),
  ].filter(Boolean);
  if (candidates.length === 0) {
    throw new EmulatorLifecycleError('sdk_not_found', '未找到 Android SDK');
  }
  return candidates[0];
}

function executableName(name) {
  return process.platform === 'win32' ? `${name}.exe` : name;
}

function runCommand(command, args) {
  return new Promise((resolve) => {
    execFile(command, args, { windowsHide: true, timeout: 30_000 }, (error, stdout, stderr) => {
      resolve({
        stdout: String(stdout ?? ''),
        stderr: String(stderr ?? ''),
        code: typeof error?.code === 'number' ? error.code : error ? 1 : 0,
      });
    });
  });
}

function spawnDetached(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawnProcess(command, args, {
      detached: true,
      stdio: 'ignore',
      windowsHide: false,
    });
    child.once('error', reject);
    child.once('spawn', () => {
      child.unref();
      resolve();
    });
  });
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
