import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { parseArgs } from 'node:util';
import { parse as parseYaml } from 'yaml';
import crypto from 'node:crypto';

const { values } = parseArgs({
  options: {
    repo: { type: 'string', default: 'https://github.com/SillyTavern/SillyTavern.git' },
    ref: { type: 'string' },
    runtime: { type: 'string' },
    out: { type: 'string', default: 'mobile/app/src/main/assets' },
    clean: { type: 'boolean', default: false },
    'allow-engine-mismatch': { type: 'boolean', default: false },
    'allow-native-addon': { type: 'boolean', default: false },
  },
  strict: true
});

if (!values.ref || !values.runtime) {
  console.error("Error: --ref and --runtime are required");
  process.exit(1);
}

const BUILD_DIR = path.resolve('build/stapk-transform');
const UPSTREAM_DIR = path.join(BUILD_DIR, 'upstream');
const PATCHED_DIR = path.join(BUILD_DIR, 'patched');
const PACKAGE_DIR = path.join(BUILD_DIR, 'package');
const REPORTS_DIR = path.join(BUILD_DIR, 'reports');

async function ensureDir(dir) {
  await fs.mkdir(dir, { recursive: true });
}

function runCmd(cmd, cwd, options = {}) {
  return execSync(cmd, { cwd, encoding: 'utf-8', ...options }).trim();
}

async function getToolVersion(tool, args = '--version') {
  try {
    return runCmd(`${tool} ${args}`);
  } catch (e) {
    return 'unknown';
  }
}

// Extract the minimum required major version from an engines.node range
// like ">=20", ">=20.0.0", "^20.5.0 || >=22". Returns the lowest major found,
// or null if it cannot be parsed.
function minRequiredMajor(engineRange) {
  if (!engineRange || typeof engineRange !== 'string') return null;
  const majors = [...engineRange.matchAll(/(\d+)(?:\.\d+)*/g)].map(m => parseInt(m[1], 10));
  if (majors.length === 0) return null;
  return Math.min(...majors);
}

// Extract the major version number from a version string like "v20.5.1" or "20".
function majorOf(versionStr) {
  const m = String(versionStr).match(/v?(\d+)/);
  return m ? parseInt(m[1], 10) : null;
}

// Collect all leaf key-paths from a parsed YAML/JSON object as dotted strings,
// e.g. { listenAddress: { ipv4: "127.0.0.1" } } -> ["listenAddress.ipv4"].
// Arrays and primitives are treated as leaves.
function collectKeyPaths(obj, prefix = '') {
  if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) {
    return prefix ? [prefix] : [];
  }
  const paths = [];
  for (const key of Object.keys(obj)) {
    const next = prefix ? `${prefix}.${key}` : key;
    const child = collectKeyPaths(obj[key], next);
    if (child.length === 0) {
      paths.push(next);
    } else {
      paths.push(...child);
    }
  }
  return paths;
}

async function main() {
  console.log('--- Phase 1: Init & Fetch ---');
  if (values.clean) {
    console.log(`Cleaning ${BUILD_DIR}...`);
    await fs.rm(BUILD_DIR, { recursive: true, force: true });
  }

  await ensureDir(UPSTREAM_DIR);
  await ensureDir(PATCHED_DIR);
  await ensureDir(PACKAGE_DIR);
  await ensureDir(REPORTS_DIR);

  const tools = {
    git: await getToolVersion('git'),
    node: process.version,
    npm: await getToolVersion('npm'),
    tar: await getToolVersion('tar')
  };

  if (!existsSync(path.join(UPSTREAM_DIR, '.git'))) {
    runCmd('git init', UPSTREAM_DIR);
    runCmd(`git remote add origin ${values.repo}`, UPSTREAM_DIR);
  }

  console.log(`Fetching ${values.ref}...`);
  try {
    runCmd(`git fetch --depth=1 origin ${values.ref}`, UPSTREAM_DIR);
  } catch (e) {
    console.error(`Error: Ref ${values.ref} not found in repo ${values.repo}`);
    process.exit(1);
  }

  runCmd('git checkout --detach FETCH_HEAD', UPSTREAM_DIR);
  const resolvedCommit = runCmd('git rev-parse HEAD', UPSTREAM_DIR);

  // Read package.json & package-lock.json
  const pkgPath = path.join(UPSTREAM_DIR, 'package.json');
  const lockPath = path.join(UPSTREAM_DIR, 'package-lock.json');

  if (!existsSync(lockPath)) {
    console.error("Error: package-lock.json not found in upstream.");
    process.exit(1);
  }

  const pkgStr = await fs.readFile(pkgPath, 'utf-8');
  const pkg = JSON.parse(pkgStr);
  const lockStr = await fs.readFile(lockPath, 'utf-8');
  const lockSha256 = crypto.createHash('sha256').update(lockStr).digest('hex');

  // Verify server.js and src/command-line.js
  if (!existsSync(path.join(UPSTREAM_DIR, 'server.js'))) {
    console.error("Error: server.js not found.");
    process.exit(1);
  }
  const cmdLinePath = path.join(UPSTREAM_DIR, 'src/command-line.js');
  if (existsSync(cmdLinePath)) {
    const cmdLine = await fs.readFile(cmdLinePath, 'utf-8');
    if (!cmdLine.includes('configPath') || !cmdLine.includes('dataRoot') || !cmdLine.includes('port')) {
      console.warn("Warning: src/command-line.js missing expected parameters.");
    }
  }

  console.log('--- Phase 2: Engine Check & Patch ---');
  let runtimeManifestStr;
  try {
    // If --runtime is a directory or points to the manifest directly, read it.
    // If it is an archive, extract runtime-manifest.json to a temp location.
    if (values.runtime.endsWith('.json')) {
      runtimeManifestStr = await fs.readFile(values.runtime, 'utf-8');
    } else {
      const tmpManifestPath = path.join(BUILD_DIR, 'runtime-manifest-tmp.json');
      if (existsSync(tmpManifestPath)) await fs.rm(tmpManifestPath, { force: true });
      // tar on Windows supports zip files but not always the -O flag correctly. Extract to disk instead.
      runCmd(`tar -xf "${values.runtime}" runtime-manifest.json`);
      // Windows tar extracts to current directory if not specified otherwise
      if (existsSync('runtime-manifest.json')) {
        await fs.rename('runtime-manifest.json', tmpManifestPath);
      } else {
        throw new Error('Not found in archive');
      }
      runtimeManifestStr = await fs.readFile(tmpManifestPath, 'utf-8');
      await fs.rm(tmpManifestPath, { force: true });
    }
  } catch (e) {
    console.error(`Error reading runtime-manifest.json from ${values.runtime}: ${e.message}`);
    process.exit(1);
  }

  const runtimeManifest = JSON.parse(runtimeManifestStr);
  const runtimeNodeVersion = runtimeManifest.node_version;
  if (!runtimeNodeVersion) {
    console.error("Error: runtime manifest missing node_version");
    process.exit(1);
  }

  // Validate both build-machine Node and Android runtime Node against the
  // upstream-declared engines.node, instead of a hardcoded major.
  const requiredEngine = pkg.engines?.node || '>=20';
  const requiredMajor = minRequiredMajor(requiredEngine);
  if (requiredMajor === null) {
    console.error(`Error: cannot parse engines.node ("${requiredEngine}") from upstream package.json.`);
    process.exit(1);
  }
  const buildMajor = majorOf(process.version);
  const runtimeMajor = majorOf(runtimeNodeVersion);
  const buildNodeValid = buildMajor !== null && buildMajor >= requiredMajor;
  const runtimeNodeValid = runtimeMajor !== null && runtimeMajor >= requiredMajor;

  const engineCheckPassed = buildNodeValid && runtimeNodeValid;
  let engineCheckOverride = false;

  if (!engineCheckPassed) {
    if (values['allow-engine-mismatch']) {
      console.warn("Warning: Engine mismatch allowed via override.");
      engineCheckOverride = true;
    } else {
      console.error("Error: Node engine mismatch and override not provided.");
      process.exit(1);
    }
  }

  if (existsSync(PATCHED_DIR)) await fs.rm(PATCHED_DIR, { recursive: true, force: true });
  await fs.cp(UPSTREAM_DIR, PATCHED_DIR, {
    recursive: true,
    filter: (src) => !src.includes('.git')
  });

  // Apply patches
  runCmd('git init', PATCHED_DIR);
  runCmd('git config user.name "stapk"', PATCHED_DIR);
  runCmd('git config user.email "stapk@localhost"', PATCHED_DIR);
  runCmd('git add .', PATCHED_DIR);
  runCmd('git commit -m "baseline"', PATCHED_DIR);

  const patchesSeries = path.resolve('patches/sillytavern/series');
  let patchQueueHash = crypto.createHash('sha256').update('').digest('hex');
  if (existsSync(patchesSeries)) {
    const series = await fs.readFile(patchesSeries, 'utf-8');
    const patches = series.split('\n').map(p => p.trim()).filter(p => p);

    let combinedPatches = '';
    for (const patch of patches) {
      const patchPath = path.resolve('patches/sillytavern', patch);
      try {
        runCmd(`git apply --3way ${patchPath}`, PATCHED_DIR);
        combinedPatches += await fs.readFile(patchPath, 'utf-8');
      } catch (e) {
        console.error(`Error: Patch conflict applying ${patch}.`);
        // Collect the files git reports as failing/conflicting. git apply emits
        // several formats depending on the failure, so match all known variants:
        //   error: patch failed: <file>:<line>
        //   error: <file>: patch does not apply
        //   error: <file>: does not exist in index
        //   U <file>            (unmerged, from --3way partial application)
        const raw = (e.stderr || '') + '\n' + (e.stdout || '') + '\n' + (e.message || '');
        const files = new Set();
        for (const m of raw.matchAll(/error: patch failed: ([^\n:]+):/g)) files.add(m[1].trim());
        for (const m of raw.matchAll(/error: ([^\n:]+): patch does not apply/g)) files.add(m[1].trim());
        for (const m of raw.matchAll(/error: ([^\n:]+): does not exist in index/g)) files.add(m[1].trim());
        for (const m of raw.matchAll(/^U\s+(.+)$/gm)) files.add(m[1].trim());
        // Fall back to unmerged files left in the working tree.
        if (files.size === 0) {
          try {
            const status = runCmd('git diff --name-only --diff-filter=U', PATCHED_DIR);
            if (status) status.split('\n').map(l => l.trim()).filter(Boolean).forEach(f => files.add(f));
          } catch (_) {}
        }
        const conflictFiles = [...files];
        await fs.writeFile(path.join(REPORTS_DIR, 'patch-report.json'), JSON.stringify({
          failed_patch: patch,
          target_commit: resolvedCommit,
          conflict_files: conflictFiles,
          // Keep raw git output so an empty conflict_files list is still diagnosable.
          git_output: raw.trim()
        }, null, 2));
        process.exit(1);
      }
    }
    patchQueueHash = crypto.createHash('sha256').update(combinedPatches).digest('hex');
  }

  console.log('--- Phase 3: Install, Scan, Package ---');
  if (existsSync(PACKAGE_DIR)) await fs.rm(PACKAGE_DIR, { recursive: true, force: true });
  await fs.cp(PATCHED_DIR, PACKAGE_DIR, {
    recursive: true,
    filter: (src) => !src.includes('.git')
  });

  try {
    runCmd('npm ci --omit=dev --ignore-scripts --no-audit --no-fund --loglevel=error --no-progress', PACKAGE_DIR);
  } catch (e) {
    const npmLog = (e.stdout || '') + (e.stderr || '');
    await fs.writeFile(path.join(REPORTS_DIR, 'npm-ci-error.log'), npmLog || String(e.message || e));
    console.error("Error: npm ci failed. If upstream now requires install scripts, the failing package/script is in reports/npm-ci-error.log.");
    process.exit(1);
  }

  // Native Addon Scan
  const nativeAddons = [];
  const findNativeCmd = process.platform === 'win32'
    ? 'Get-ChildItem -Recurse -Filter *.node | Select-Object -ExpandProperty FullName'
    : 'find node_modules -name "*.node"';

  try {
    let output = '';
    if (process.platform === 'win32') {
      output = runCmd(`powershell -Command "${findNativeCmd}"`, PACKAGE_DIR);
    } else {
      output = runCmd(findNativeCmd, PACKAGE_DIR);
    }
    if (output) nativeAddons.push(...output.split('\n').map(l => l.trim()).filter(l => l));
  } catch (e) {
    // Ignore error if nothing found
  }

  const pkgJsonFiles = [];
  try {
    let output = '';
    if (process.platform === 'win32') {
      output = runCmd(`powershell -Command "Get-ChildItem -Path node_modules -Filter package.json -Recurse | Select-Object -ExpandProperty FullName"`, PACKAGE_DIR);
    } else {
      output = runCmd('find node_modules -name "package.json"', PACKAGE_DIR);
    }
    if (output) pkgJsonFiles.push(...output.split('\n').map(l => l.trim()).filter(l => l));
  } catch (e) {}

  const incompatibleDeps = [];
  // Layer ③: build-intent signals (warning only, never blocks).
  // Native-build helper packages, matched as whole dependency names.
  const NATIVE_BUILD_DEPS = new Set([
    'node-gyp', 'node-pre-gyp', '@mapbox/node-pre-gyp', 'prebuild', 'prebuild-install',
    'prebuildify', 'node-gyp-build', 'bindings', 'nan', 'node-addon-api', 'cmake-js'
  ]);
  // Tools that, when invoked by an install-phase script, indicate a real native build.
  const NATIVE_BUILD_TOOL_RE = /\b(node-gyp|node-pre-gyp|prebuild-install|node-gyp-build|cmake-js|node-addon-api)\b/;
  const INSTALL_PHASE_SCRIPTS = ['preinstall', 'install', 'postinstall', 'rebuild'];
  const buildIntentSignals = [];
  for (const pkgFile of pkgJsonFiles) {
    const pStr = await fs.readFile(pkgFile, 'utf-8');
    try {
      const p = JSON.parse(pStr);
      if (p.os && p.os.length > 0 && !p.os.includes('android') && !p.os.includes('any')) {
        incompatibleDeps.push({ name: p.name, field: 'os', value: p.os });
      }
      if (p.cpu && p.cpu.length > 0 && !p.cpu.includes('arm64') && !p.cpu.includes('any')) {
        incompatibleDeps.push({ name: p.name, field: 'cpu', value: p.cpu });
      }
      // libc is glibc/musl on Linux; Android uses bionic, so any libc constraint
      // signals a platform-specific (non-Android) artifact.
      if (p.libc && p.libc.length > 0) {
        incompatibleDeps.push({ name: p.name, field: 'libc', value: p.libc });
      }
      // ③ build-intent: only count an install-phase script that actually invokes a
      // native build tool (avoids false positives from common "prebuild"/"build"
      // script names on pure-JS packages), plus deps on native-build helpers.
      const allDeps = { ...(p.dependencies || {}), ...(p.optionalDependencies || {}) };
      const hits = [];
      if (p.scripts) {
        for (const phase of INSTALL_PHASE_SCRIPTS) {
          if (p.scripts[phase] && NATIVE_BUILD_TOOL_RE.test(p.scripts[phase])) {
            hits.push(`script:${phase}`);
          }
        }
      }
      for (const dep of Object.keys(allDeps)) {
        if (NATIVE_BUILD_DEPS.has(dep)) hits.push(`dep:${dep}`);
      }
      if (existsSync(path.join(path.dirname(pkgFile), 'binding.gyp'))) {
        hits.push('binding.gyp');
      }
      if (hits.length > 0) {
        buildIntentSignals.push({ name: p.name, signals: hits });
      }
    } catch (e) {}
  }
  if (buildIntentSignals.length > 0) {
    console.warn(`Build-intent signals (warning only): ${buildIntentSignals.length} package(s).`);
    await fs.writeFile(
      path.join(REPORTS_DIR, 'build-intent-signals.json'),
      JSON.stringify(buildIntentSignals, null, 2)
    );
  }

  let nativeScanOverride = false;
  if (nativeAddons.length > 0 || incompatibleDeps.length > 0) {
    console.warn(`Native addons found: ${nativeAddons.length}, incompatible dependencies: ${incompatibleDeps.length}`);
    if (values['allow-native-addon']) {
      nativeScanOverride = true;
    } else {
      console.error("Error: Platform specific artifacts found and override not provided.");
      process.exit(1);
    }
  }

  // Android Config Review
  const defaultConfPath = path.join(UPSTREAM_DIR, 'default/config.yaml');
  const androidConfPath = path.resolve('transform/config/config.android.yaml');

  if (existsSync(defaultConfPath) && existsSync(androidConfPath)) {
    const defaultConfStr = await fs.readFile(defaultConfPath, 'utf-8');
    const androidConfStr = await fs.readFile(androidConfPath, 'utf-8');
    const defaultConf = parseYaml(defaultConfStr) || {};
    const androidConf = parseYaml(androidConfStr) || {};

    // Recursive key-path diff so nested fields (e.g. listenAddress.ipv4) are
    // compared, not just top-level keys.
    const upstreamPaths = collectKeyPaths(defaultConf);
    const androidPaths = new Set(collectKeyPaths(androidConf));
    const androidPathSetUpstream = new Set(upstreamPaths);

    const diff = {
      // Upstream fields the Android template does not cover (drift risk).
      missingInAndroid: upstreamPaths.filter(p => !androidPaths.has(p)),
      // Android-only fields not present upstream (intentional overrides, recorded for review).
      extraInAndroid: [...androidPaths].filter(p => !androidPathSetUpstream.has(p))
    };
    await fs.writeFile(path.join(REPORTS_DIR, 'config-diff.json'), JSON.stringify(diff, null, 2));

    // The Android template is a partial override (only Android-required fields),
    // so it legitimately omits most upstream fields. We therefore do NOT hard-fail
    // on every missing field — that would make a partial template unusable.
    // Instead we surface drift for human review (design Step 9: block only when an
    // upstream-added *required* field is missing — required-field detection is a
    // follow-up; for now drift is reported as a warning).
    if (diff.missingInAndroid.length > 0) {
      console.warn(
        `Config drift: ${diff.missingInAndroid.length} upstream field(s) not covered by config.android.yaml. ` +
        `See reports/config-diff.json for review.`
      );
    }
  }

  // Package
  console.log(`Packaging to ${values.out}...`);
  await ensureDir(path.resolve(values.out));

  const payloadPath = path.join(values.out, 'payload.tgz');
  // Pack with the stable top-level path name "SillyTavern/" (plan Step 10).
  // Rename the build's package dir to SillyTavern, then tar once.
  const sillyTavernDir = path.join(BUILD_DIR, 'SillyTavern');
  if (existsSync(sillyTavernDir)) await fs.rm(sillyTavernDir, { recursive: true, force: true });
  await fs.rename(PACKAGE_DIR, sillyTavernDir);
  runCmd(`tar -czf ${payloadPath} -C ${BUILD_DIR} --exclude=".git" SillyTavern`, process.cwd());

  const payloadStat = await fs.stat(payloadPath);
  const payloadArchiveSize = payloadStat.size;
  // Estimate unpacked size (tar -xzf and du or just sum file sizes)
  // For now just use a simple heuristic or compute size of sillyTavernDir
  let unpackedSize = 0;
  async function computeSize(dir) {
    const entries = await fs.readdir(dir, { withFileTypes: true });
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        await computeSize(full);
      } else {
        const s = await fs.stat(full);
        unpackedSize += s.size;
      }
    }
  }
  await computeSize(sillyTavernDir);

  if (existsSync(androidConfPath)) {
    await fs.cp(androidConfPath, path.join(values.out, 'config.android.yaml'));
  }

  // Generate Manifest
  const manifest = {
    tools,
    sillytavern: {
      repo: values.repo,
      requested_ref: values.ref,
      resolved_commit: resolvedCommit,
      version: pkg.version
    },
    lockfile_sha256: lockSha256,
    runtime: {
      node_version: runtimeNodeVersion
    },
    patch_queue_hash: patchQueueHash,
    engine_check: {
      required: requiredEngine,
      required_major: requiredMajor,
      build_node: process.version,
      runtime_node: runtimeNodeVersion,
      passed: engineCheckPassed,
      override: engineCheckOverride
    },
    native_addon_scan: {
      passed: !nativeScanOverride && nativeAddons.length === 0 && incompatibleDeps.length === 0,
      override: nativeScanOverride,
      native_addons: nativeAddons,
      incompatible_deps: incompatibleDeps,
      build_intent_signals: buildIntentSignals
    },
    payload: {
      archive_size_bytes: payloadArchiveSize,
      unpacked_size_bytes: unpackedSize,
      required_free_bytes: unpackedSize + Math.floor(unpackedSize * 0.2), // add 20% margin
      sha256: crypto.createHash('sha256').update(await fs.readFile(payloadPath)).digest('hex')
    }
  };

  await fs.writeFile(path.join(values.out, 'payload-manifest.json'), JSON.stringify(manifest, null, 2));
  console.log("Transformation completed successfully.");
}

main().catch(e => {
  console.error("Unhandled error:", e);
  process.exit(1);
});
