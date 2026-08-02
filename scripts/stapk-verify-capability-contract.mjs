#!/usr/bin/env node

import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';

const VALID_STATUSES = new Set([
  'implemented',
  'external_optional',
  'unsupported_hidden',
  'needs_review'
]);
const FIXED_CAPABILITY_IDS = new Set([
  'core.settings',
  'core.personas',
  'core.characters',
  'core.groups',
  'core.chats',
  'core.world_info',
  'core.backgrounds',
  'core.files',
  'core.tokenizers',
  'core.data_management',
  'native.extensions',
  'remote.embeddings',
  'remote.image',
  'remote.tts',
  'remote.stt',
  'remote.caption',
  'remote.translation',
  'excluded.extensions',
  'excluded.local_models',
  'excluded.multiuser'
]);
const CAPABILITY_FIELDS = new Set([
  'id',
  'kind',
  'defaultStatus',
  'endpointPrefixes',
  'uiPolicy',
  'runtimeAvailable',
]);

export function verifyCapabilityContract({ apiContract, capabilities }) {
  const errors = [];
  const visibleNeedsReview = [];
  const unassignedEndpoints = [];
  const summaryByCapability = {};
  const capabilityList = capabilities.capabilities ?? [];
  validateCapabilities(capabilityList, errors);

  for (const endpoint of apiContract.endpoints ?? []) {
    const endpointKey = `${endpoint.method.toUpperCase()} ${endpoint.path}`;
    const matches = capabilityList.filter((capability) => matchesCapability(capability, endpoint.path));

    if (matches.length === 0) {
      unassignedEndpoints.push(endpointKey);
      errors.push(`Endpoint has no capability: ${endpointKey}`);
      continue;
    }
    if (matches.length > 1) {
      errors.push(`Endpoint has multiple capabilities: ${endpointKey} (${matches.map((capability) => capability.id).join(', ')})`);
      continue;
    }

    const capability = matches[0];
    if (endpoint.capability !== capability.id) {
      errors.push(
        `Endpoint capability declaration mismatch: ${endpointKey} (declared ${endpoint.capability}, matched ${capability.id})`
      );
    }
    if (endpoint.exposure !== capability.uiPolicy) {
      errors.push(
        `Endpoint exposure declaration mismatch: ${endpointKey} (declared ${endpoint.exposure}, matched ${capability.uiPolicy})`
      );
    }
    const capabilitySummary = summaryByCapability[capability.id] ?? {};
    capabilitySummary[endpoint.status] = (capabilitySummary[endpoint.status] ?? 0) + 1;
    summaryByCapability[capability.id] = capabilitySummary;

    if (!VALID_STATUSES.has(endpoint.status)) {
      errors.push(`Endpoint has invalid status: ${endpointKey} (${endpoint.status})`);
    }
    if (endpoint.status === 'needs_review' && capability.uiPolicy !== 'hidden') {
      visibleNeedsReview.push(endpointKey);
      errors.push(`Visible endpoint still needs review: ${endpointKey}`);
    }
    if (capability.kind === 'excluded' && endpoint.status === 'implemented') {
      errors.push(`Excluded capability cannot be implemented: ${endpointKey}`);
    }
    if (capability.kind === 'core' && endpoint.status === 'external_optional') {
      errors.push(`Core capability cannot be external_optional: ${endpointKey}`);
    }
    if (capability.uiPolicy === 'visible_when_implemented' && endpoint.status === 'unsupported_hidden') {
      errors.push(`Visible-when-implemented capability cannot be unsupported_hidden: ${endpointKey}`);
    }
  }

  return {
    ok: errors.length === 0,
    errors,
    visibleNeedsReview: visibleNeedsReview.sort(),
    unassignedEndpoints: unassignedEndpoints.sort(),
    summaryByCapability: sortSummary(summaryByCapability)
  };
}

export function isCapabilityVerificationAllowed(result, allowVisibleNeedsReview = false) {
  if (result.ok) {
    return true;
  }
  if (!allowVisibleNeedsReview) {
    return false;
  }

  const allowedErrors = new Set(
    result.visibleNeedsReview.map((endpoint) => `Visible endpoint still needs review: ${endpoint}`)
  );
  return result.errors.length > 0 && result.errors.every((error) => allowedErrors.has(error));
}

function validateCapabilities(capabilities, errors) {
  const seenIds = new Set();
  const prefixes = [];

  for (const capability of capabilities) {
    if (!capability || typeof capability !== 'object' || Array.isArray(capability)) {
      errors.push('Capability must be an object');
      continue;
    }
    for (const field of Object.keys(capability)) {
      if (!CAPABILITY_FIELDS.has(field)) {
        errors.push(`Capability has unknown field: ${capability.id ?? '<missing id>'} (${field})`);
      }
    }
    if (seenIds.has(capability.id)) {
      errors.push(`Duplicate capability id: ${capability.id}`);
    }
    seenIds.add(capability.id);
    if (!FIXED_CAPABILITY_IDS.has(capability.id)) {
      errors.push(`Unknown capability id: ${capability.id}`);
    }

    if (capability.kind === 'core') {
      if (!capability.id?.startsWith('core.') && !capability.id?.startsWith('native.')) {
        errors.push(`Core capability id must use core or native prefix: ${capability.id}`);
      }
      if (capability.uiPolicy !== 'visible_when_implemented') {
        errors.push(`Core capability must use visible_when_implemented: ${capability.id}`);
      }
      if (!['implemented', 'needs_review'].includes(capability.defaultStatus)) {
        errors.push(`Core capability has invalid defaultStatus: ${capability.id} (${capability.defaultStatus})`);
      }
    } else if (capability.kind === 'external_optional') {
      if (!capability.id?.startsWith('remote.')) {
        errors.push(`External optional capability id must use remote prefix: ${capability.id}`);
      }
      if (capability.uiPolicy !== 'visible_when_configured') {
        errors.push(`External optional capability must use visible_when_configured: ${capability.id}`);
      }
      if (capability.defaultStatus !== 'external_optional') {
        errors.push(`External optional capability has invalid defaultStatus: ${capability.id} (${capability.defaultStatus})`);
      }
    } else if (capability.kind === 'excluded') {
      if (!capability.id?.startsWith('excluded.')) {
        errors.push(`Excluded capability id must use excluded prefix: ${capability.id}`);
      }
      if (capability.uiPolicy !== 'hidden') {
        errors.push(`Excluded capability must use hidden: ${capability.id}`);
      }
      if (capability.defaultStatus !== 'unsupported_hidden') {
        errors.push(`Excluded capability has invalid defaultStatus: ${capability.id} (${capability.defaultStatus})`);
      }
    } else {
      errors.push(`Capability has invalid kind: ${capability.id} (${capability.kind})`);
    }

    if (Object.hasOwn(capability, 'runtimeAvailable')) {
      if (typeof capability.runtimeAvailable !== 'boolean') {
        errors.push(`Capability runtimeAvailable must be boolean: ${capability.id}`);
      } else if (capability.kind !== 'external_optional') {
        errors.push(`Capability runtimeAvailable is only allowed for external_optional: ${capability.id}`);
      }
    }

    const seenPrefixes = new Set();
    for (const prefix of capability.endpointPrefixes ?? []) {
      if (seenPrefixes.has(prefix)) {
        errors.push(`Capability has duplicate endpoint prefix: ${capability.id} (${prefix})`);
      }
      seenPrefixes.add(prefix);
      prefixes.push({ capability: capability.id, prefix });
    }
  }

  for (const capabilityId of FIXED_CAPABILITY_IDS) {
    if (!seenIds.has(capabilityId)) {
      errors.push(`Missing capability id: ${capabilityId}`);
    }
  }

  for (let leftIndex = 0; leftIndex < prefixes.length; leftIndex += 1) {
    const left = prefixes[leftIndex];
    for (let rightIndex = leftIndex + 1; rightIndex < prefixes.length; rightIndex += 1) {
      const right = prefixes[rightIndex];
      if (left.capability === right.capability) {
        continue;
      }
      if (matchesApiPrefix(left.prefix, right.prefix) || matchesApiPrefix(right.prefix, left.prefix)) {
        errors.push(
          `Capability prefixes overlap: ${left.capability} ${left.prefix} and ${right.capability} ${right.prefix}`
        );
      }
    }
  }
}

function matchesCapability(capability, apiPath) {
  return capability.endpointPrefixes.some((prefix) => matchesApiPrefix(prefix, apiPath));
}

function matchesApiPrefix(prefix, apiPath) {
  return apiPath === prefix || apiPath.startsWith(`${prefix}/`);
}

function sortSummary(summaryByCapability) {
  return Object.fromEntries(
    Object.entries(summaryByCapability)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([capability, summary]) => [
        capability,
        Object.fromEntries(Object.entries(summary).sort(([left], [right]) => left.localeCompare(right)))
      ])
  );
}

async function main() {
  const { values } = parseArgs({
    options: {
      contract: { type: 'string' },
      capabilities: { type: 'string' },
      report: { type: 'string' },
      'allow-visible-needs-review': { type: 'boolean' }
    }
  });
  if (!values.contract) {
    throw new Error('Missing required option: --contract');
  }
  if (!values.capabilities) {
    throw new Error('Missing required option: --capabilities');
  }

  const [apiContract, capabilities] = await Promise.all([
    readJson(values.contract),
    readJson(values.capabilities)
  ]);
  const result = verifyCapabilityContract({ apiContract, capabilities });
  if (values.report) {
    await writeFile(path.resolve(values.report), `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  }

  console.log(JSON.stringify(result, null, 2));
  if (!isCapabilityVerificationAllowed(result, values['allow-visible-needs-review'])) {
    throw new Error(result.errors.join('\n'));
  }
}

async function readJson(file) {
  return JSON.parse(await readFile(path.resolve(file), 'utf8'));
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
