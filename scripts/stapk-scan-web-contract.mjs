#!/usr/bin/env node

import { existsSync } from 'node:fs';
import { mkdir, readFile, readdir, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { parse } from 'acorn';
import { parse as parseHtml } from 'parse5';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';

const SCAN_EXTENSIONS = new Set(['.cjs', '.html', '.js', '.mjs']);
const CLASSIC_JAVASCRIPT_MIME_TYPES = new Set([
  'application/javascript',
  'text/javascript'
]);

export function classifyEndpoint(method, apiPath, allowlist) {
  const normalizedMethod = method.toUpperCase();
  const exactImplemented = allowlist.implemented ?? [];
  const hiddenPrefixes = allowlist.unsupportedHidden ?? [];

  if (exactImplemented.some((entry) => entry.method.toUpperCase() === normalizedMethod && entry.path === apiPath)) {
    return 'implemented';
  }

  if (hiddenPrefixes.some((entry) => apiPath === entry.prefix || apiPath.startsWith(`${entry.prefix}/`))) {
    return 'unsupported_hidden';
  }

  return 'needs_review';
}

export async function scanWebContract({ webRoot, allowlistFile, capabilityFile, upstream }) {
  const absoluteWebRoot = path.resolve(webRoot);
  const allowlist = JSON.parse(await readFile(path.resolve(allowlistFile), 'utf8'));
  const capabilities = capabilityFile
    ? JSON.parse(await readFile(path.resolve(capabilityFile), 'utf8')).capabilities ?? []
    : [];
  const endpointMap = new Map();

  if (!existsSync(absoluteWebRoot)) {
    throw new Error(`Web root does not exist: ${absoluteWebRoot}`);
  }

  for (const file of await listScanFiles(absoluteWebRoot)) {
    const source = await readFile(file, 'utf8');
    const sourceFile = toPosixPath(path.relative(absoluteWebRoot, file));
    const { apiLiterals, fetchRequests } = extractEndpointEvidence(source, sourceFile);

    for (const endpoint of mergeEndpointEvidence(fetchRequests, apiLiterals)) {
      const key = `${endpoint.method} ${endpoint.path}`;
      const capability = findCapability(endpoint.path, capabilities);
      const current = endpointMap.get(key) ?? {
        method: endpoint.method,
        path: endpoint.path,
        status: classifyEndpointWithCapability(endpoint.method, endpoint.path, allowlist, capability),
        capability: capability?.id ?? null,
        exposure: capability?.uiPolicy ?? null,
        inferredMethod: endpoint.inferredMethod,
        dynamic: endpoint.dynamic,
        sourceFiles: new Set(),
        sourceLocations: new Map()
      };
      current.inferredMethod &&= endpoint.inferredMethod;
      current.dynamic ||= endpoint.dynamic;
      for (const location of endpoint.sourceLocations) {
        current.sourceFiles.add(location.file);
        current.sourceLocations.set(locationKey(location), location);
      }
      endpointMap.set(key, current);
    }
  }

  const endpoints = [...endpointMap.values()]
    .map((endpoint) => ({
      ...endpoint,
      sourceFiles: [...endpoint.sourceFiles].sort(),
      sourceLocations: [...endpoint.sourceLocations.values()].sort(compareSourceLocations)
    }))
    .sort((left, right) => `${left.path} ${left.method}`.localeCompare(`${right.path} ${right.method}`));

  return {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    upstream: {
      ref: upstream.ref,
      ...(upstream.commit ? { commit: upstream.commit } : {}),
      ...(upstream.version ? { version: upstream.version } : {})
    },
    webRoot: absoluteWebRoot,
    endpoints,
    summary: summarizeEndpoints(endpoints)
  };
}

export function extractFetchRequests(source) {
  return extractFetchRequestsFromUnits(parseSourceUnits(source, '<source>.js'));
}

export function extractEndpointEvidence(source, sourceFile = '<source>.js') {
  const units = parseSourceUnits(source, sourceFile);
  return {
    apiLiterals: extractApiLiteralsFromUnits(units, sourceFile),
    fetchRequests: extractFetchRequestsFromUnits(units)
  };
}

function extractFetchRequestsFromUnits(units) {
  const requests = [];
  for (const unit of units) {
    const callExpressions = collectNodes(unit.ast, (node) => node.type === 'CallExpression');
    for (const call of callExpressions) {
      if (call.callee.type !== 'Identifier' || call.callee.name !== 'fetch') {
        continue;
      }
      const url = readUrlEvidence(call.arguments[0], unit.source);
      if (url === null) {
        continue;
      }
      const method = evaluateFetchMethod(call.arguments[1]);
      if (method !== null) {
        requests.push({ path: url.normalizedPath ?? url.value, method });
      }
    }
  }
  return requests;
}

function evaluateFetchMethod(options) {
  if (options === undefined) {
    return 'GET';
  }
  if (options.type !== 'ObjectExpression') {
    return null;
  }

  let method = 'GET';
  let methodEvidenceIsCertain = true;
  for (const property of options.properties) {
    if (property.type === 'SpreadElement') {
      method = null;
      methodEvidenceIsCertain = false;
      continue;
    }

    const key = evaluatePropertyKey(property);
    if (key === null) {
      method = null;
      methodEvidenceIsCertain = false;
      continue;
    }
    if (key !== 'method') {
      continue;
    }

    const value = evaluateStaticString(property.value);
    if (value !== null && /^[A-Za-z]+$/.test(value)) {
      method = value.toUpperCase();
      methodEvidenceIsCertain = true;
    } else {
      method = null;
      methodEvidenceIsCertain = false;
    }
  }

  return methodEvidenceIsCertain ? method : null;
}

function evaluatePropertyKey(property) {
  if (!property.computed && property.key.type === 'Identifier') {
    return property.key.name;
  }
  if (!property.computed && property.key.type === 'Literal' && typeof property.key.value === 'string') {
    return property.key.value;
  }
  return property.computed ? evaluateStaticString(property.key) : null;
}

function evaluateStaticString(node) {
  if (!node) {
    return null;
  }
  if (node.type === 'Literal' && typeof node.value === 'string') {
    return node.value;
  }
  if (node.type === 'TemplateLiteral' && node.expressions.length === 0) {
    return node.quasis.map((quasi) => quasi.value.cooked ?? quasi.value.raw).join('');
  }
  if (node.type === 'BinaryExpression' && node.operator === '+') {
    const left = evaluateStaticString(node.left);
    const right = evaluateStaticString(node.right);
    return left === null || right === null ? null : left + right;
  }
  return null;
}

export function extractApiLiterals(source, sourceFile) {
  return extractApiLiteralsFromUnits(parseSourceUnits(source, sourceFile), sourceFile);
}

function extractApiLiteralsFromUnits(units, sourceFile) {
  const literals = [];
  for (const unit of units) {
    const stringNodes = collectNodes(
      unit.ast,
      (node) => (node.type === 'Literal' && typeof node.value === 'string') || node.type === 'TemplateLiteral'
    );
    for (const node of stringNodes) {
      const literal = readUrlEvidence(node, unit.source);
      if (literal?.value.startsWith('/api/') !== true || literal.normalizedPath?.startsWith('/api/') !== true) {
        continue;
      }
      literals.push({
        path: literal.normalizedPath,
        expression: literal.expression,
        line: node.loc.start.line + unit.lineOffset,
        dynamic: literal.dynamic,
        sourceFile
      });
    }
  }
  return literals;
}

function readUrlEvidence(node, source) {
  if (!node) {
    return null;
  }
  const expression = source.slice(node.start + 1, node.end - 1);
  if (node.type === 'Literal' && typeof node.value === 'string') {
    return {
      value: node.value,
      normalizedPath: normalizeApiPath(node.value),
      expression,
      dynamic: false
    };
  }
  if (node.type !== 'TemplateLiteral') {
    return null;
  }

  const value = buildTemplateUrl(node);
  return {
    value,
    normalizedPath: normalizeApiPath(value),
    expression,
    dynamic: node.expressions.length > 0
  };
}

function buildTemplateUrl(node) {
  let value = '';
  for (let index = 0; index < node.quasis.length; index += 1) {
    const quasi = node.quasis[index].value.cooked ?? node.quasis[index].value.raw;
    const delimiterIndex = quasi.search(/[?#]/);
    value += delimiterIndex === -1 ? quasi : quasi.slice(0, delimiterIndex);
    if (delimiterIndex !== -1) {
      break;
    }
    if (index < node.expressions.length) {
      value += '{dynamic}';
    }
  }
  return value;
}

function parseSourceUnits(source, sourceFile = '<source>.js') {
  if (path.extname(sourceFile).toLowerCase() === '.html') {
    return extractInlineScriptUnits(source, sourceFile);
  }
  return [{
    source,
    ast: parseJavaScript(source, sourceFile, sourceTypesForFile(sourceFile)),
    lineOffset: 0,
    columnOffset: 0
  }];
}

function extractInlineScriptUnits(html, sourceFile) {
  const units = [];
  const document = parseHtml(html, { sourceCodeLocationInfo: true });
  const stack = [...(document.childNodes ?? [])].reverse();
  while (stack.length > 0) {
    const node = stack.pop();
    if (node.tagName === 'template') {
      continue;
    }
    if (node.tagName !== 'script') {
      const children = node.childNodes ?? [];
      for (let index = children.length - 1; index >= 0; index -= 1) {
        stack.push(children[index]);
      }
      continue;
    }

    const attributes = node.attrs ?? [];
    if (attributes.some((attribute) => attribute.name === 'src')) {
      continue;
    }
    const scriptType = classifyScriptType(attributes);
    if (scriptType === null) {
      continue;
    }

    const location = node.sourceCodeLocation;
    if (!location?.startTag) {
      continue;
    }
    const contentStart = location.startTag.endOffset;
    const contentEnd = location.endTag?.startOffset ?? location.endOffset;
    const scriptSource = html.slice(contentStart, contentEnd);
    const origin = {
      lineOffset: location.startTag.endLine - 1,
      columnOffset: location.startTag.endCol - 1
    };
    const sourceTypes = [scriptType];
    units.push({
      source: scriptSource,
      ast: parseJavaScript(scriptSource, sourceFile, sourceTypes, origin),
      ...origin
    });
  }
  return units;
}

function classifyScriptType(attributes) {
  const value = (attributes.find((attribute) => attribute.name === 'type')?.value ?? '').trim().toLowerCase();
  if (value === '') {
    return 'script';
  }
  if (value === 'module') {
    return 'module';
  }
  const mimeEssence = value.split(';', 1)[0].trim();
  return CLASSIC_JAVASCRIPT_MIME_TYPES.has(mimeEssence) ? 'script' : null;
}

function sourceTypesForFile(sourceFile) {
  const extension = path.extname(sourceFile).toLowerCase();
  if (extension === '.mjs') return ['module'];
  if (extension === '.cjs') return ['script'];
  return ['module', 'script'];
}

function parseJavaScript(source, sourceFile, sourceTypes, origin = { lineOffset: 0, columnOffset: 0 }) {
  const failures = [];
  for (const sourceType of sourceTypes) {
    try {
      return parse(source, {
        allowHashBang: true,
        ecmaVersion: 'latest',
        locations: true,
        sourceType
      });
    } catch (error) {
      failures.push({ sourceType, error });
    }
  }

  const details = failures.map(({ sourceType, error }) =>
    `  ${sourceType}: ${formatParseFailure(error, origin)}`
  );
  throw new SyntaxError(`Failed to parse ${sourceFile}\n${details.join('\n')}`);
}

function formatParseFailure(error, origin) {
  if (error?.loc) {
    const message = String(error.message).replace(/ \(\d+:\d+\)$/, '');
    const line = error.loc.line + origin.lineOffset;
    const column = error.loc.column + (error.loc.line === 1 ? origin.columnOffset : 0);
    return `${message} (${line}:${column})`;
  }
  return error instanceof Error ? error.message : String(error);
}

function collectNodes(root, predicate) {
  const matches = [];
  for (const node of walkAst(root)) {
    if (predicate(node)) matches.push(node);
  }
  return matches.sort((left, right) => left.start - right.start || left.end - right.end);
}

function* walkAst(root) {
  const stack = [root];
  while (stack.length > 0) {
    const node = stack.pop();
    yield node;
    const children = [];
    for (const value of Object.values(node)) {
      if (isAstNode(value)) {
        children.push(value);
      } else if (Array.isArray(value)) {
        for (const item of value) {
          if (isAstNode(item)) children.push(item);
        }
      }
    }
    for (let index = children.length - 1; index >= 0; index -= 1) {
      stack.push(children[index]);
    }
  }
}

function isAstNode(value) {
  return value !== null && typeof value === 'object' && typeof value.type === 'string';
}

export function mergeEndpointEvidence(fetchRequests, apiLiterals) {
  const fetchMethodsByPath = new Map();
  for (const request of fetchRequests) {
    const apiPath = normalizeApiPath(request.path);
    if (!apiPath || !apiPath.startsWith('/api/')) {
      continue;
    }

    const methods = fetchMethodsByPath.get(apiPath) ?? new Set();
    methods.add(request.method.toUpperCase());
    fetchMethodsByPath.set(apiPath, methods);
  }

  const literalsByPath = new Map();
  for (const literal of apiLiterals) {
    const locations = literalsByPath.get(literal.path) ?? [];
    locations.push({
      file: literal.sourceFile,
      line: literal.line,
      expression: literal.expression,
      dynamic: literal.dynamic
    });
    literalsByPath.set(literal.path, locations);
  }

  const endpoints = [];
  for (const [apiPath, locations] of literalsByPath) {
    const methods = fetchMethodsByPath.get(apiPath) ?? new Set(['POST']);
    for (const method of methods) {
      endpoints.push({
        method,
        path: apiPath,
        inferredMethod: !fetchMethodsByPath.has(apiPath),
        dynamic: locations.some((location) => location.dynamic),
        sourceLocations: locations.map(({ dynamic, ...location }) => location)
      });
    }
  }

  return endpoints.sort((left, right) => `${left.path} ${left.method}`.localeCompare(`${right.path} ${right.method}`));
}

async function listScanFiles(root) {
  const files = [];
  const entries = await readdir(root);

  for (const entry of entries) {
    const absolutePath = path.join(root, entry);
    const entryStat = await stat(absolutePath);

    if (entryStat.isDirectory()) {
      files.push(...await listScanFiles(absolutePath));
      continue;
    }

    if (entryStat.isFile() && SCAN_EXTENSIONS.has(path.extname(entry).toLowerCase())) {
      files.push(absolutePath);
    }
  }

  return files.sort();
}

function normalizeApiPath(rawPath) {
  let apiPath;
  try {
    if (/^https?:\/\//i.test(rawPath)) {
      const url = new URL(rawPath);
      apiPath = stripQueryAndHash(url.pathname);
    }
  } catch {
    return null;
  }

  if (!apiPath && rawPath.startsWith('/')) {
    apiPath = stripQueryAndHash(rawPath);
  }

  return apiPath && !/\s/.test(apiPath) ? apiPath : null;
}

function stripQueryAndHash(value) {
  return value.split(/[?#]/, 1)[0];
}

function locationKey(location) {
  return `${location.file}:${location.line}:${location.expression}`;
}

function compareSourceLocations(left, right) {
  return left.file.localeCompare(right.file)
    || left.line - right.line
    || left.expression.localeCompare(right.expression);
}

function summarizeEndpoints(endpoints) {
  return endpoints.reduce(
    (summary, endpoint) => {
      summary[endpoint.status] += 1;
      return summary;
    },
    {
      implemented: 0,
      external_optional: 0,
      unsupported_hidden: 0,
      needs_review: 0
    }
  );
}

function classifyEndpointWithCapability(method, apiPath, allowlist, capability) {
  const allowlistStatus = classifyEndpoint(method, apiPath, allowlist);
  return allowlistStatus === 'needs_review'
    ? capability?.defaultStatus ?? allowlistStatus
    : allowlistStatus;
}

function findCapability(apiPath, capabilities) {
  const matches = capabilities.filter((capability) =>
    capability.endpointPrefixes.some((prefix) => apiPath === prefix || apiPath.startsWith(`${prefix}/`))
  );
  return matches.length === 1 ? matches[0] : null;
}

function toPosixPath(value) {
  return value.split(path.sep).join('/');
}

async function main() {
  const { values } = parseArgs({
    options: {
      'web-root': { type: 'string' },
      out: { type: 'string' },
      allowlist: { type: 'string' },
      capabilities: { type: 'string' },
      'upstream-ref': { type: 'string' },
      'upstream-commit': { type: 'string' },
      'upstream-version': { type: 'string' }
    }
  });

  if (!values['web-root']) {
    throw new Error('Missing required option: --web-root');
  }
  if (!values.out) {
    throw new Error('Missing required option: --out');
  }

  const contract = await scanWebContract({
    webRoot: values['web-root'],
    allowlistFile: values.allowlist ?? 'transform/no-node/mvp-api-allowlist.json',
    capabilityFile: values.capabilities ?? 'transform/no-node/capabilities.json',
    upstream: {
      ref: values['upstream-ref'] ?? 'unknown',
      commit: values['upstream-commit'],
      version: values['upstream-version']
    }
  });

  const outPath = path.resolve(values.out);
  await mkdir(path.dirname(outPath), { recursive: true });
  await writeFile(outPath, `${JSON.stringify(contract, null, 2)}\n`, 'utf8');
  console.log(`Wrote no-node API contract: ${outPath}`);
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
