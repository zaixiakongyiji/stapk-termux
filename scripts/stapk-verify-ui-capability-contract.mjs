#!/usr/bin/env node

import { parse as parseJavaScript } from 'acorn';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import postcss from 'postcss';
import selectorParser from 'postcss-selector-parser';
import { parseHTML } from 'linkedom';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';

const UI_CONTRACT_NAME = 'stapk-ui-capabilities.json';

export function validateUiCapabilityContract({ uiContract, capabilities }) {
  const errors = [];
  const capabilityMap = new Map(
    Array.isArray(capabilities?.capabilities)
      ? capabilities.capabilities.map((capability) => [capability.id, capability])
      : []
  );

  if (uiContract?.schemaVersion !== 1) {
    errors.push('UI capability contract must set schemaVersion: 1');
  }

  const hiddenStylesheets = requireArray(
    uiContract?.hiddenStylesheets,
    'hiddenStylesheets',
    errors
  );
  const implementedActions = requireArray(
    uiContract?.implementedActions,
    'implementedActions',
    errors
  );
  const configuredActions = requireArray(
    uiContract?.configuredActions,
    'configuredActions',
    errors
  );
  const hiddenSelectors = requireArray(
    uiContract?.hiddenSelectors,
    'hiddenSelectors',
    errors
  );

  const stylesheetPaths = new Set();
  for (const [index, stylesheet] of hiddenStylesheets.entries()) {
    const label = `hiddenStylesheets[${index}]`;
    if (!isObject(stylesheet)) {
      errors.push(`${label} must be an object`);
      continue;
    }
    requireStringFields(stylesheet, ['path', 'catalogBefore'], label, errors);
    validateRelativePath(stylesheet.path, `${label}.path`, errors);
    validateSelector(stylesheet.catalogBefore, `${label}.catalogBefore`, errors);
    if (stylesheetPaths.has(stylesheet.path)) {
      errors.push(`${label} duplicates stylesheet ${stylesheet.path}`);
    }
    stylesheetPaths.add(stylesheet.path);
  }

  const actionNames = new Set();
  const actionSelectors = new Set();
  for (const [index, action] of implementedActions.entries()) {
    const label = `implementedActions[${index}]`;
    if (!isObject(action)) {
      errors.push(`${label} must be an object`);
      continue;
    }
    requireStringFields(action, ['name', 'selector', 'capability', 'endpoint'], label, errors);
    validateUnique(action.name, actionNames, `${label}.name`, errors);
    validateUnique(action.selector, actionSelectors, `${label}.selector`, errors);
    validateSelector(action.selector, `${label}.selector`, errors);

    const endpoint = parseEndpointKey(action.endpoint);
    if (!endpoint) {
      errors.push(`${label}.endpoint must use "METHOD /path" format`);
    }

    const capability = capabilityMap.get(action.capability);
    if (!capability) {
      errors.push(`${label} references unknown capability ${action.capability}`);
    } else if (capability.kind !== 'core') {
      errors.push(`${label} must map to an implemented core capability`);
    }

    validateActionSource(action.source, label, action.selector, errors);
  }

  for (const [index, action] of configuredActions.entries()) {
    const label = `configuredActions[${index}]`;
    if (!isObject(action)) {
      errors.push(`${label} must be an object`);
      continue;
    }
    requireStringFields(action, ['name', 'selector', 'capability', 'endpoint'], label, errors);
    validateUnique(action.name, actionNames, `${label}.name`, errors);
    validateUnique(action.selector, actionSelectors, `${label}.selector`, errors);
    validateSelector(action.selector, `${label}.selector`, errors);

    if (!parseEndpointKey(action.endpoint)) {
      errors.push(`${label}.endpoint must use "METHOD /path" format`);
    }

    const capability = capabilityMap.get(action.capability);
    if (!capability) {
      errors.push(`${label} references unknown capability ${action.capability}`);
    } else if (capability.kind !== 'external_optional' || capability.runtimeAvailable !== true) {
      errors.push(`${label} must map to an external_optional capability with runtimeAvailable: true`);
    }

    validateActionSource(action.source, label, action.selector, errors);
  }

  const hiddenSelectorNames = new Set();
  for (const [index, entry] of hiddenSelectors.entries()) {
    const label = `hiddenSelectors[${index}]`;
    if (!isObject(entry)) {
      errors.push(`${label} must be an object`);
      continue;
    }
    requireStringFields(entry, ['selector', 'capability', 'status', 'reason'], label, errors);
    validateUnique(entry.selector, hiddenSelectorNames, `${label}.selector`, errors);
    validateSelector(entry.selector, `${label}.selector`, errors);

    if (entry.status !== 'unsupported_hidden') {
      errors.push(`${label} must set status to unsupported_hidden`);
    }
    if (!isNonEmptyString(entry.reason)) {
      errors.push(`${label} must provide a non-empty reason`);
    }

    const capability = capabilityMap.get(entry.capability);
    if (!capability) {
      errors.push(`${label} references unknown capability ${entry.capability}`);
    } else {
      if (capability.kind !== 'excluded') {
        errors.push(`${label} must map to an excluded capability`);
      }
      if (capability.defaultStatus !== 'unsupported_hidden') {
        errors.push(`${label} capability must default to unsupported_hidden`);
      }
    }
  }

  for (const action of configuredActions) {
    if (hiddenSelectorNames.has(action.selector)) {
      errors.push(`Configured action "${action.name}" must not also be a hidden selector: ${action.selector}`);
    }
  }

  return errors;
}

export async function verifyUiCapabilityContract({
  webRoot,
  uiContractFile = path.join(path.resolve(webRoot), UI_CONTRACT_NAME),
  apiContractFile,
  capabilityFile,
}) {
  const absoluteWebRoot = path.resolve(webRoot);
  const errors = [];
  let uiContract;
  let apiContract;
  let capabilities;

  try {
    [uiContract, apiContract, capabilities] = await Promise.all([
      readJson(uiContractFile),
      readJson(apiContractFile),
      readJson(capabilityFile),
    ]);
  } catch (error) {
    errors.push(error instanceof Error ? error.message : String(error));
    return buildResult({ errors, implementedActions: 0, configuredActions: 0, hiddenSelectors: 0, localStylesheets: 0 });
  }

  errors.push(...validateUiCapabilityContract({ uiContract, capabilities }));
  if (errors.length > 0) {
    return buildResult({
      errors,
      implementedActions: uiContract.implementedActions?.length ?? 0,
      configuredActions: uiContract.configuredActions?.length ?? 0,
      hiddenSelectors: uiContract.hiddenSelectors?.length ?? 0,
      localStylesheets: 0,
    });
  }

  const htmlPath = path.join(absoluteWebRoot, 'index.html');
  let document;
  try {
    const html = await readFile(htmlPath, 'utf8');
    ({ document } = parseHTML(html));
  } catch (error) {
    errors.push(`Unable to parse final HTML ${htmlPath}: ${formatError(error)}`);
    return buildResult({
      errors,
      implementedActions: uiContract.implementedActions.length,
      configuredActions: uiContract.configuredActions.length,
      hiddenSelectors: uiContract.hiddenSelectors.length,
      localStylesheets: 0,
    });
  }

  const stylesheetRecords = await loadLocalStylesheets({
    webRoot: absoluteWebRoot,
    document,
    hiddenStylesheets: uiContract.hiddenStylesheets,
    errors,
  });
  verifyHiddenSelectorCatalog({ uiContract, stylesheetRecords, errors });
  verifyImplementedEndpoints({ uiContract, apiContract, errors });
  verifyHtmlActions({ uiContract, document, stylesheetRecords, errors });
  await verifyJavaScriptActions({
    uiContract,
    webRoot: absoluteWebRoot,
    stylesheetRecords,
    errors,
  });

  return buildResult({
    errors,
    implementedActions: uiContract.implementedActions.length,
    configuredActions: uiContract.configuredActions.length,
    hiddenSelectors: uiContract.hiddenSelectors.length,
    localStylesheets: stylesheetRecords.stylesheets.length,
  });
}

async function loadLocalStylesheets({ webRoot, document, hiddenStylesheets, errors }) {
  const stylesheets = [];
  const hiddenSelectors = [];
  const catalogBoundaries = new Map();
  const visitedStylesheets = new Set();
  const catalogStylesheets = new Map(hiddenStylesheets.map((entry) => [
    entry.path,
    canonicalizeSingleSelector(entry.catalogBefore, 'catalogBefore', errors),
  ]));
  const localOrigin = 'https://stapk.local';

  async function loadStylesheet(relativePath) {
    if (visitedStylesheets.has(relativePath)) return;
    visitedStylesheets.add(relativePath);

    const absolutePath = resolveWithin(webRoot, relativePath, errors, 'Stylesheet');
    if (!absolutePath) return;

    let root;
    try {
      root = postcss.parse(await readFile(absolutePath, 'utf8'), { from: absolutePath });
    } catch (error) {
      errors.push(`Unable to parse stylesheet ${relativePath}: ${formatError(error)}`);
      return;
    }

    stylesheets.push(relativePath);
    let ruleIndex = 0;
    root.walkRules((rule) => {
      const catalogBefore = catalogStylesheets.get(relativePath);
      if (catalogBefore && !catalogBoundaries.has(relativePath)) {
        const selectors = parseSelectorList(
          rule.selector,
          `${relativePath}:${rule.source?.start?.line ?? 0}`,
          errors
        );
        if (selectors.includes(catalogBefore)) {
          catalogBoundaries.set(relativePath, ruleIndex);
        }
      }

      const hides = rule.nodes?.some((node) =>
        node.type === 'decl'
        && node.prop.toLowerCase() === 'display'
        && node.value.trim().toLowerCase() === 'none'
        && node.important
      );
      if (hides) {
        for (const selector of parseSelectorList(rule.selector, `${relativePath}:${rule.source?.start?.line ?? 0}`, errors)) {
          hiddenSelectors.push({ selector, stylesheet: relativePath, ruleIndex });
        }
      }
      ruleIndex += 1;
    });

    const importHrefs = [];
    root.walkAtRules('import', (atRule) => {
      const importHref = readCssImportHref(atRule.params);
      if (!importHref) {
        errors.push(
          `Unable to parse stylesheet import ${relativePath}:${atRule.source?.start?.line ?? 0}`
        );
        return;
      }
      importHrefs.push(importHref);
    });

    for (const importHref of importHrefs) {
      const importedPath = resolveLocalStylesheetHref({
        href: importHref,
        baseHref: `${localOrigin}/${relativePath}`,
        localOrigin,
        errors,
        label: `Stylesheet import from ${relativePath}`,
      });
      if (importedPath) {
        await loadStylesheet(importedPath);
      }
    }
  }

  for (const link of document.querySelectorAll('link[rel~="stylesheet"][href]')) {
    const relativePath = resolveLocalStylesheetHref({
      href: link.getAttribute('href'),
      baseHref: `${localOrigin}/`,
      localOrigin,
      errors,
      label: 'Stylesheet href',
    });
    if (relativePath) {
      await loadStylesheet(relativePath);
    }
  }

  return { stylesheets, hiddenSelectors, catalogBoundaries };
}

function resolveLocalStylesheetHref({ href, baseHref, localOrigin, errors, label }) {
  let parsed;
  try {
    parsed = new URL(href, baseHref);
  } catch (error) {
    errors.push(`Invalid ${label} ${href}: ${formatError(error)}`);
    return null;
  }
  if (parsed.origin !== localOrigin) {
    return null;
  }

  try {
    return decodeURIComponent(parsed.pathname).replace(/^\/+/, '');
  } catch (error) {
    errors.push(`Invalid encoded ${label} ${href}: ${formatError(error)}`);
    return null;
  }
}

function readCssImportHref(params) {
  const value = params.trim();
  if (value.startsWith('"') || value.startsWith("'")) {
    return readQuotedCssValue(value, 0);
  }
  if (!value.toLowerCase().startsWith('url(')) {
    return null;
  }

  let index = 4;
  while (/\s/.test(value[index] ?? '')) index += 1;
  if (value[index] === '"' || value[index] === "'") {
    return readQuotedCssValue(value, index);
  }

  const end = value.indexOf(')', index);
  if (end < 0) return null;
  const href = value.slice(index, end).trim();
  return href || null;
}

function readQuotedCssValue(value, start) {
  const quote = value[start];
  let result = '';
  for (let index = start + 1; index < value.length; index += 1) {
    const character = value[index];
    if (character === quote) return result;
    if (character === '\\' && index + 1 < value.length) {
      index += 1;
      result += value[index];
    } else {
      result += character;
    }
  }
  return null;
}

function verifyHiddenSelectorCatalog({ uiContract, stylesheetRecords, errors }) {
  const declaredStylesheets = new Set(uiContract.hiddenStylesheets.map((entry) => entry.path));
  const linkedStylesheets = new Set(stylesheetRecords.stylesheets);
  for (const stylesheet of declaredStylesheets) {
    if (!linkedStylesheets.has(stylesheet)) {
      errors.push(`Contract hidden stylesheet is not linked from final HTML: ${stylesheet}`);
    } else if (!stylesheetRecords.catalogBoundaries.has(stylesheet)) {
      errors.push(`Contract catalog boundary is missing from stylesheet: ${stylesheet}`);
    }
  }

  const actual = new Set(
    stylesheetRecords.hiddenSelectors
      .filter((entry) =>
        declaredStylesheets.has(entry.stylesheet)
        && entry.ruleIndex < (stylesheetRecords.catalogBoundaries.get(entry.stylesheet) ?? -1)
      )
      .map((entry) => entry.selector)
  );
  const declared = new Set(uiContract.hiddenSelectors.map((entry) =>
    canonicalizeSingleSelector(entry.selector, 'UI contract hidden selector', errors)
  ).filter(Boolean));

  for (const selector of actual) {
    if (!declared.has(selector)) {
      errors.push(`Hidden CSS selector is missing from the formal contract: ${selector}`);
    }
  }
  for (const selector of declared) {
    if (!actual.has(selector)) {
      errors.push(`Formal hidden selector is missing from CSS: ${selector}`);
    }
  }
}

function verifyImplementedEndpoints({ uiContract, apiContract, errors }) {
  const endpoints = new Map(
    Array.isArray(apiContract?.endpoints)
      ? apiContract.endpoints.map((entry) => [`${entry.method} ${entry.path}`, entry])
      : []
  );

  for (const [kind, actions] of [
    ['Implemented', uiContract.implementedActions],
    ['Configured', uiContract.configuredActions],
  ]) {
    for (const action of actions) {
      const endpoint = endpoints.get(action.endpoint);
      if (!endpoint) {
        errors.push(`${kind} action "${action.name}" references missing endpoint ${action.endpoint}`);
        continue;
      }
      if (endpoint.status !== 'implemented') {
        errors.push(`${kind} action "${action.name}" endpoint is ${endpoint.status}`);
      }
      if (endpoint.capability !== action.capability) {
        errors.push(
          `${kind} action "${action.name}" capability drifted: ${endpoint.capability} != ${action.capability}`
        );
      }
    }
  }
}

function verifyHtmlActions({ uiContract, document, stylesheetRecords, errors }) {
  const hiddenMatches = new Map();
  for (const entry of stylesheetRecords.hiddenSelectors) {
    if (hiddenMatches.has(entry.selector)) continue;
    try {
      hiddenMatches.set(entry.selector, [...document.querySelectorAll(entry.selector)]);
    } catch (error) {
      if (!selectorHasPseudoElement(entry.selector)) {
        errors.push(`Selector engine rejected ${entry.selector}: ${formatError(error)}`);
      }
      hiddenMatches.set(entry.selector, []);
    }
  }

  const htmlActions = [
    ...uiContract.implementedActions.map((action) => ({ kind: 'Implemented', action })),
    ...uiContract.configuredActions.map((action) => ({ kind: 'Configured', action })),
  ].filter(({ action }) => action.source.type === 'html');
  for (const { kind, action } of htmlActions) {
    let elements;
    try {
      elements = [...document.querySelectorAll(action.selector)];
    } catch (error) {
      errors.push(`${kind} action "${action.name}" has invalid selector: ${formatError(error)}`);
      continue;
    }
    if (elements.length === 0) {
      errors.push(`${kind} action "${action.name}" is missing from final HTML: ${action.selector}`);
      continue;
    }

    for (const [selector, hiddenElements] of hiddenMatches) {
      const hidesAction = elements.some((actionElement) =>
        hiddenElements.some((hiddenElement) =>
          hiddenElement === actionElement || hiddenElement.contains(actionElement)
        )
      );
      if (hidesAction) {
        errors.push(`${kind} action "${action.name}" is hidden by CSS selector: ${selector}`);
      }
    }
  }
}

async function verifyJavaScriptActions({ uiContract, webRoot, stylesheetRecords, errors }) {
  const actionsByPath = new Map();
  for (const action of [
    ...uiContract.implementedActions,
    ...uiContract.configuredActions,
  ].filter(({ source }) => source.type === 'javascript')) {
    const actions = actionsByPath.get(action.source.path) ?? [];
    actions.push(action);
    actionsByPath.set(action.source.path, actions);
  }

  for (const [relativePath, actions] of actionsByPath) {
    const absolutePath = resolveWithin(webRoot, relativePath, errors, 'JavaScript source');
    if (!absolutePath) continue;

    let ast;
    try {
      ast = parseJavaScript(await readFile(absolutePath, 'utf8'), {
        allowHashBang: true,
        ecmaVersion: 'latest',
        sourceType: 'module',
      });
    } catch (error) {
      errors.push(`Unable to parse JavaScript source ${relativePath}: ${formatError(error)}`);
      continue;
    }

    const index = createJavaScriptIndex(ast);

    for (const action of actions) {
      const runtimeProbe = buildDynamicRuntimeProbe(action);
      verifyDynamicCssReachability({ action, runtimeProbe, stylesheetRecords, errors });
      verifyDynamicAction({ action, index, runtimeProbe, errors });
    }
  }
}

function buildDynamicRuntimeProbe(action) {
  const { document } = parseHTML('<!doctype html><html><body></body></html>');
  let parent = document.body;
  for (const ancestor of action.source.runtimeProbe.ancestors) {
    const element = document.createElement(ancestor.element);
    element.classList.add(...ancestor.classes);
    parent.appendChild(element);
    parent = element;
  }

  const actionElement = document.createElement(action.source.runtimeProbe.element);
  const actionClass = readSingleClassSelector(action.selector);
  actionElement.classList.add(actionClass);
  const visibilityClass = action.source.visibilityTransition?.required
    ? action.source.visibilityTransition.className
    : null;
  if (visibilityClass) {
    actionElement.classList.add(visibilityClass);
  }
  parent.appendChild(actionElement);
  return { actionElement, document };
}

function verifyDynamicCssReachability({ action, runtimeProbe, stylesheetRecords, errors }) {
  const { actionElement, document } = runtimeProbe;
  const visibilityClass = action.source.visibilityTransition?.required
    ? action.source.visibilityTransition.className
    : null;
  for (const { selector } of stylesheetRecords.hiddenSelectors) {
    let hiddenElements;
    try {
      hiddenElements = [...document.querySelectorAll(selector)];
    } catch (error) {
      if (!selectorHasPseudoElement(selector)) {
        errors.push(`Selector engine rejected ${selector}: ${formatError(error)}`);
      }
      continue;
    }
    const hidesAction = hiddenElements.some((hiddenElement) =>
      hiddenElement === actionElement || hiddenElement.contains(actionElement)
    );
    if (!hidesAction) continue;

    const isInitialVisibilityClass = visibilityClass
      && readSingleClassSelector(selector) === visibilityClass;
    if (!isInitialVisibilityClass) {
      errors.push(`Implemented action "${action.name}" is hidden by CSS selector: ${selector}`);
    }
  }
}

function verifyDynamicAction({ action, index, runtimeProbe, errors }) {
  const className = readSingleClassSelector(action.selector);
  if (!className) {
    errors.push(`Dynamic action "${action.name}" selector must be one simple class selector`);
    return;
  }

  verifyRuntimeProbeTopology({ action, index, errors });

  const eventSelector = canonicalizeSingleSelector(
    action.source.eventSelector,
    `${action.name} delegated selector`,
    errors
  );
  if (
    eventSelector
    && !runtimeProbe.actionElement.matches(eventSelector)
  ) {
    errors.push(
      `Dynamic action "${action.name}" delegated selector does not match the runtime action`
    );
  }
  if (
    action.source.delegationReceiver !== 'document'
    || !runtimeProbe.document.documentElement.contains(runtimeProbe.actionElement)
  ) {
    errors.push(
      `Dynamic action "${action.name}" delegation receiver does not contain the runtime action`
    );
  }

  const rawFactoryCalls = index.calls.filter(({ node }) =>
    node.callee.type === 'Identifier'
    && node.callee.name === action.source.factory
    && readStaticString(node.arguments[0]) === className
  );
  const callableFactoryCalls = rawFactoryCalls.filter((call) =>
    ownerFunctionName(call) === action.source.constructionFunction
    && resolvesUniqueCallableBinding(call.node.callee, call, index)
  );
  const factoryCalls = callableFactoryCalls.filter((call) => !isStaticallyUnreachable(call));
  if (factoryCalls.length === 0) {
    if (rawFactoryCalls.some(isStaticallyUnreachable)) {
      errors.push(`Dynamic action "${action.name}" construction is statically unreachable`);
    } else if (rawFactoryCalls.length > 0) {
      errors.push(
        `Dynamic action "${action.name}" factory does not resolve to a unique callable binding`
      );
    } else {
      errors.push(`Dynamic action "${action.name}" is missing ${action.source.factory} construction`);
    }
    return;
  }

  const factoryBindings = new Set(
    index.variables
      .filter(({ node }) => node.id.type === 'Identifier' && factoryCalls.some(({ node: call }) => call === node.init))
      .map(({ node }) => node)
  );
  const actionContainer = action.source.runtimeProbe.ancestors.at(-1)?.binding;
  const rawAppends = index.calls.filter((call) => {
    const { node } = call;
    const method = memberPropertyName(node.callee);
    if (!['append', 'appendChild'].includes(method)) return false;
    if (ownerFunctionName(call) !== action.source.constructionFunction) return false;
    if (
      node.callee.object?.type !== 'Identifier'
      || node.callee.object.name !== actionContainer
    ) {
      return false;
    }
    return node.arguments.some((argument) =>
      factoryCalls.some(({ node: call }) => argument === call)
      || (
        argument.type === 'Identifier'
        && factoryBindings.has(resolveUniqueBinding(argument, call, index)?.declaration.node)
      )
    );
  });
  const appended = rawAppends.some((call) => !isStaticallyUnreachable(call));
  if (!appended) {
    errors.push(rawAppends.some(isStaticallyUnreachable)
      ? `Dynamic action "${action.name}" append is statically unreachable`
      : `Dynamic action "${action.name}" is constructed but never appended to ${actionContainer}`);
  }

  const eventBindings = index.calls.filter((call) => {
    const { node } = call;
    if (memberPropertyName(node.callee) !== 'on') return false;
    if (readStaticString(node.arguments[0]) !== action.source.event) return false;
    const selector = readStaticString(node.arguments[1]);
    const handler = node.arguments[2];
    return Boolean(
      selector
      && canonicalizeSingleSelector(
        selector,
        `${action.name} JavaScript delegated selector`,
        errors
      ) === eventSelector
      && handler?.type === 'Identifier'
      && handler.name === action.source.handler
    );
  });
  const receiverBindings = eventBindings.filter((call) =>
    matchesDelegationReceiver(call.node.callee.object, action.source.delegationReceiver)
  );
  const rawClickBindings = receiverBindings;
  const callableClickBindings = rawClickBindings.filter((call) =>
    ownerFunctionName(call) === action.source.eventFunction
    && resolvesUniqueCallableBinding(call.node.arguments[2], call, index)
  );
  const clickBound = callableClickBindings.some((call) => !isStaticallyUnreachable(call));
  if (!clickBound) {
    if (eventBindings.length > 0 && receiverBindings.length === 0) {
      errors.push(`Dynamic action "${action.name}" delegation receiver does not match the contract`);
    } else if (rawClickBindings.some(isStaticallyUnreachable)) {
      errors.push(`Dynamic action "${action.name}" event binding is statically unreachable`);
    } else if (rawClickBindings.length > 0) {
      errors.push(
        `Dynamic action "${action.name}" handler does not resolve to a unique callable binding`
      );
    } else {
      errors.push(
        `Dynamic action "${action.name}" has no ${action.source.event} binding to ${action.source.handler}`
      );
    }
  }

  if (action.source.visibilityTransition?.required) {
    const visibility = action.source.visibilityTransition;
    const visibilityClass = visibility.className;
    const querySelector = canonicalizeSingleSelector(
      visibility.querySelector,
      `${action.name} visibility query`,
      errors
    );
    const ownerFunction = visibility.ownerFunction;
    const dataFactory = visibility.dataFactory;
    const identityVariable = findOwnedVariable(
      index,
      visibility.recordIdentityBinding,
      ownerFunction
    );
    const identityBinding = resolveBindingRecord(identityVariable, index);
    const dataBindings = index.variables.filter(({ node, ancestors }) =>
      node.id.type === 'Identifier'
      && node.id.name === visibility.condition.object
      && ownerFunctionName({ node, ancestors }) === ownerFunction
      && callExpressionFromValue(node.init)?.callee?.type === 'Identifier'
      && callExpressionFromValue(node.init).callee.name === dataFactory
      && resolvesUniqueCallableBinding(
        callExpressionFromValue(node.init).callee,
        index.records.get(callExpressionFromValue(node.init)),
        index
      )
      && callArgumentResolvesToBinding(
        callExpressionFromValue(node.init),
        0,
        identityBinding,
        index
      )
      && !isStaticallyUnreachable({ node, ancestors })
    );
    const selectorVariables = index.variables.filter((variable) => {
      const { node } = variable;
      const selectorCall = callExpressionFromValue(node.init);
      return node.id.type === 'Identifier'
        && node.id.name === visibility.recordSelectorBinding
        && ownerFunctionName(variable) === ownerFunction
        && selectorCall?.callee?.type === 'Identifier'
        && selectorCall.callee.name === visibility.recordSelectorFactory
        && resolvesUniqueCallableBinding(
          selectorCall.callee,
          index.records.get(selectorCall),
          index
        )
        && callArgumentResolvesToBinding(selectorCall, 0, identityBinding, index)
        && !isStaticallyUnreachable(variable);
    });
    const selectorBindings = new Set(
      selectorVariables
        .map((variable) => resolveBindingRecord(variable, index))
        .filter(Boolean)
    );
    const recordVariables = index.variables.filter((variable) => {
      const { node } = variable;
      const recordQuery = callExpressionFromValue(node.init);
      return node.id.type === 'Identifier'
        && node.id.name === visibility.queryReceiver
        && ownerFunctionName(variable) === ownerFunction
        && recordQuery
        && memberPropertyName(recordQuery.callee) === 'querySelector'
        && recordQuery.callee.object?.type === 'Identifier'
        && recordQuery.callee.object.name === 'document'
        && expressionReferencesAnyBinding(
          recordQuery.arguments[0],
          selectorBindings,
          index.records.get(recordQuery),
          index
        )
        && !isStaticallyUnreachable(variable);
    });
    const recordBindings = new Set(
      recordVariables
        .map((variable) => resolveBindingRecord(variable, index))
        .filter(Boolean)
    );
    const queryBindings = new Set();
    for (const variable of index.variables) {
      const { node } = variable;
      if (ownerFunctionName(variable) !== ownerFunction || isStaticallyUnreachable(variable)) {
        continue;
      }
      if (node.id.type !== 'Identifier' || node.init?.type !== 'CallExpression') continue;
      if (memberPropertyName(node.init.callee) !== 'querySelector') continue;
      if (
        node.init.callee.object?.type !== 'Identifier'
        || !recordBindings.has(resolveUniqueBinding(node.init.callee.object, variable, index))
      ) {
        continue;
      }
      const selector = readStaticString(node.init.arguments[0]);
      if (
        !selector
        || canonicalizeSingleSelector(selector, `${action.name} querySelector`, errors) !== querySelector
      ) {
        continue;
      }
      queryBindings.add(node);
    }

    const hasVisibilityRecord = Boolean(
      identityBinding
      && dataBindings.length > 0
      && selectorBindings.size > 0
      && recordBindings.size > 0
    );
    const hasVisibilityQueryReceiver = queryBindings.size > 0;

    let hasBoundRemoval = false;
    const hasForeignRemoval = index.calls.some((call) =>
      ownerFunctionName(call) !== ownerFunction
      && memberPropertyName(call.node.callee) === 'remove'
      && readStaticString(call.node.arguments[0]) === visibilityClass
    );
    let hasUnreachableTransition = index.calls.some((call) =>
      ownerFunctionName(call) === ownerFunction
      && memberPropertyName(call.node.callee) === 'remove'
      && readStaticString(call.node.arguments[0]) === visibilityClass
      && isStaticallyUnreachable(call)
    );
    const hasTransition = index.calls.some((call) => {
      const { node, ancestors } = call;
      if (ownerFunctionName(call) !== ownerFunction) return false;
      if (memberPropertyName(node.callee) !== 'remove') return false;
      if (readStaticString(node.arguments[0]) !== visibilityClass) return false;
      const classList = node.callee.object;
      if (classList?.type !== 'MemberExpression' || memberPropertyName(classList) !== 'classList') {
        return false;
      }
      if (classList.object.type !== 'Identifier') return false;
      const queryBinding = resolveUniqueBinding(classList.object, call, index)?.declaration.node;
      if (!queryBindings.has(queryBinding)) return false;
      hasBoundRemoval = true;

      const condition = ancestors.findLast((ancestor) => {
        if (ancestor.type !== 'IfStatement') return false;
        const conditionRecord = index.records.get(ancestor);
        return matchesVisibilityCondition({
          node: ancestor.test,
          condition: visibility.condition,
          conditionRecord,
          dataBindings,
          index,
        })
          && subtreeContains(ancestor.consequent, queryBinding)
          && subtreeContains(ancestor.consequent, node);
      });
      if (!condition) return false;
      if (isStaticallyUnreachable(call) || isStaticallyUnreachable(index.records.get(condition))) {
        hasUnreachableTransition = true;
        return false;
      }
      return true;
    });
    if (!hasVisibilityRecord) {
      errors.push(
        `Dynamic action "${action.name}" visibility record dataflow does not resolve the current extension`
      );
    } else if (!hasVisibilityQueryReceiver) {
      if (hasUnreachableTransition) {
        errors.push(`Dynamic action "${action.name}" visibility transition is statically unreachable`);
      } else {
        errors.push(
          `Dynamic action "${action.name}" visibility query receiver dataflow `
          + `does not resolve the current extension record`
        );
      }
    } else if (!hasTransition) {
      if (hasUnreachableTransition) {
        errors.push(`Dynamic action "${action.name}" visibility transition is statically unreachable`);
      } else if (hasBoundRemoval || hasForeignRemoval) {
        errors.push(
          `Dynamic action "${action.name}" does not satisfy the required visibility condition dataflow`
        );
      } else {
        errors.push(`Dynamic action "${action.name}" is missing a conditional visibility transition`);
      }
    }
  }
}

function callArgumentResolvesToBinding(call, argumentIndex, binding, index) {
  const argument = call?.arguments[argumentIndex];
  const reference = index.records.get(call);
  return Boolean(
    binding
    && argument?.type === 'Identifier'
    && resolveUniqueBinding(argument, reference, index) === binding
  );
}

function expressionReferencesAnyBinding(expression, bindings, reference, index) {
  if (!expression || bindings.size === 0 || !reference) return false;
  let matches = false;
  walkAst(expression, [], (node) => {
    if (
      !matches
      && node.type === 'Identifier'
      && bindings.has(resolveUniqueBinding(node, reference, index))
    ) {
      matches = true;
    }
  });
  return matches;
}

function matchesDelegationReceiver(receiver, expected) {
  return expected === 'document'
    && receiver?.type === 'CallExpression'
    && receiver.callee.type === 'Identifier'
    && receiver.callee.name === '$'
    && receiver.arguments.length === 1
    && receiver.arguments[0].type === 'Identifier'
    && receiver.arguments[0].name === 'document';
}

function matchesVisibilityCondition({ node, condition, conditionRecord, dataBindings, index }) {
  if (
    node?.type !== 'BinaryExpression'
    || node.operator !== condition.operator
    || node.left?.type !== 'MemberExpression'
    || node.left.computed
    || node.left.object?.type !== 'Identifier'
    || node.left.object.name !== condition.object
    || node.left.property?.type !== 'Identifier'
    || node.left.property.name !== condition.property
    || node.right?.type !== 'Literal'
    || !Object.is(node.right.value, condition.value)
  ) {
    return false;
  }
  const binding = resolveUniqueBinding(node.left.object, conditionRecord, index);
  return dataBindings.some(({ node: dataBinding }) =>
    dataBinding === binding?.declaration.node
  );
}

function callExpressionFromValue(node) {
  if (node?.type === 'CallExpression') return node;
  if (node?.type === 'AwaitExpression' && node.argument?.type === 'CallExpression') {
    return node.argument;
  }
  return null;
}

function createJavaScriptIndex(ast) {
  const index = {
    ast,
    bindings: [],
    calls: [],
    functions: [],
    records: new Map(),
    variables: [],
  };
  walkAst(ast, [], (node, ancestors) => {
    const record = { node, ancestors };
    index.records.set(node, record);
    if (node.type === 'CallExpression') index.calls.push(record);
    if (node.type === 'VariableDeclarator') index.variables.push(record);
    if (node.type === 'FunctionDeclaration') index.functions.push(record);

    if (node.type === 'VariableDeclarator') {
      const declaration = ancestors.findLast((ancestor) => ancestor.type === 'VariableDeclaration');
      const scope = declaration?.kind === 'var'
        ? nearestFunction(ancestors) ?? ancestors.find((ancestor) => ancestor.type === 'Program')
        : nearestLexicalScope(ancestors);
      addPatternBindings(index.bindings, node.id, {
        declaration: record,
        init: node.init,
        kind: 'variable',
        scope,
      });
    } else if (node.type === 'FunctionDeclaration' && node.id) {
      index.bindings.push({
        callable: true,
        declaration: record,
        identifier: node.id,
        kind: 'function',
        name: node.id.name,
        scope: nearestLexicalScope(ancestors),
      });
    }

    if (isFunctionNode(node)) {
      for (const parameter of node.params) {
        addPatternBindings(index.bindings, parameter, {
          declaration: record,
          init: null,
          kind: 'parameter',
          scope: node,
        });
      }
    } else if (node.type === 'CatchClause' && node.param) {
      addPatternBindings(index.bindings, node.param, {
        declaration: record,
        init: null,
        kind: 'catch',
        scope: node,
      });
    }
  });
  return index;
}

function addPatternBindings(bindings, pattern, metadata) {
  if (!pattern) return;
  if (pattern.type === 'Identifier') {
    bindings.push({
      ...metadata,
      callable: metadata.kind === 'variable'
        && ['ArrowFunctionExpression', 'FunctionExpression'].includes(metadata.init?.type),
      identifier: pattern,
      name: pattern.name,
    });
    return;
  }
  if (pattern.type === 'RestElement') {
    addPatternBindings(bindings, pattern.argument, metadata);
    return;
  }
  if (pattern.type === 'AssignmentPattern') {
    addPatternBindings(bindings, pattern.left, metadata);
    return;
  }
  if (pattern.type === 'ArrayPattern') {
    for (const element of pattern.elements) addPatternBindings(bindings, element, metadata);
    return;
  }
  if (pattern.type === 'ObjectPattern') {
    for (const property of pattern.properties) {
      addPatternBindings(
        bindings,
        property.type === 'Property' ? property.value : property.argument,
        {
          ...metadata,
          propertyName: property.type === 'Property'
            ? memberPropertyNameFromProperty(property)
            : null,
        }
      );
    }
  }
}

function resolveUniqueBinding(identifier, reference, index) {
  if (identifier?.type !== 'Identifier' || !reference) return null;
  const scopes = [...reference.ancestors.filter(isLexicalScope)].reverse();
  for (const scope of scopes) {
    const candidates = index.bindings.filter((binding) =>
      binding.name === identifier.name && binding.scope === scope
    );
    if (candidates.length > 0) {
      return candidates.length === 1 ? candidates[0] : null;
    }
  }
  return null;
}

function resolvesUniqueCallableBinding(identifier, reference, index) {
  const binding = resolveUniqueBinding(identifier, reference, index);
  return Boolean(binding?.callable && !isStaticallyUnreachable(binding.declaration));
}

const STATIC_UNKNOWN = Symbol('static-unknown');

function isStaticallyUnreachable(record) {
  if (!record) return false;
  return record.ancestors.some((ancestor) => {
    if (ancestor.type === 'IfStatement' || ancestor.type === 'ConditionalExpression') {
      const staticValue = readStaticBoolean(ancestor.test);
      if (staticValue === null) return false;
      const inConsequent = recordIsWithin(record, ancestor.consequent);
      const inAlternate = ancestor.alternate && recordIsWithin(record, ancestor.alternate);
      return (staticValue === false && inConsequent) || (staticValue === true && inAlternate);
    }
    if (ancestor.type === 'WhileStatement' || ancestor.type === 'ForStatement') {
      return ancestor.test
        && readStaticBoolean(ancestor.test) === false
        && recordIsWithin(record, ancestor.body);
    }
    if (ancestor.type === 'LogicalExpression' && recordIsWithin(record, ancestor.right)) {
      const left = evaluateStaticValue(ancestor.left);
      if (left === STATIC_UNKNOWN) return false;
      if (ancestor.operator === '&&') return !Boolean(left);
      if (ancestor.operator === '||') return Boolean(left);
      if (ancestor.operator === '??') return left !== null && left !== undefined;
    }
    return false;
  });
}

function recordIsWithin(record, root) {
  return Boolean(root && (root === record.node || record.ancestors.includes(root)));
}

function readStaticBoolean(node) {
  const value = evaluateStaticValue(node);
  return value === STATIC_UNKNOWN ? null : Boolean(value);
}

function evaluateStaticValue(node) {
  if (node?.type === 'Literal') {
    const valueType = typeof node.value;
    if (
      node.value === null
      || ['boolean', 'number', 'string', 'bigint'].includes(valueType)
    ) {
      return node.value;
    }
    return STATIC_UNKNOWN;
  }
  if (node?.type === 'TemplateLiteral' && node.expressions.length === 0) {
    return node.quasis[0]?.value.cooked ?? node.quasis[0]?.value.raw ?? '';
  }
  if (node?.type === 'UnaryExpression' && ['!', 'void', '+', '-', '~'].includes(node.operator)) {
    const argument = evaluateStaticValue(node.argument);
    if (argument === STATIC_UNKNOWN) return STATIC_UNKNOWN;
    try {
      if (node.operator === '!') return !argument;
      if (node.operator === 'void') return undefined;
      if (node.operator === '+') return +argument;
      if (node.operator === '-') return -argument;
      if (node.operator === '~') return ~argument;
    } catch {
      return STATIC_UNKNOWN;
    }
  }
  if (node?.type === 'LogicalExpression' && ['&&', '||', '??'].includes(node.operator)) {
    const left = evaluateStaticValue(node.left);
    if (left === STATIC_UNKNOWN) return STATIC_UNKNOWN;
    if (node.operator === '&&') {
      return Boolean(left) ? evaluateStaticValue(node.right) : left;
    }
    if (node.operator === '||') {
      return Boolean(left) ? left : evaluateStaticValue(node.right);
    }
    return left === null || left === undefined ? evaluateStaticValue(node.right) : left;
  }
  return STATIC_UNKNOWN;
}

function ownerFunctionName(record) {
  return record?.ancestors.findLast((ancestor) =>
    ancestor.type === 'FunctionDeclaration' && ancestor.id?.name
  )?.id.name ?? null;
}

function isFunctionNode(node) {
  return ['FunctionDeclaration', 'FunctionExpression', 'ArrowFunctionExpression'].includes(node.type);
}

function isLexicalScope(node) {
  return node && (
    node.type === 'Program'
    || node.type === 'BlockStatement'
    || node.type === 'CatchClause'
    || isFunctionNode(node)
  );
}

function verifyRuntimeProbeTopology({ action, index, errors }) {
  const probe = action.source.runtimeProbe;
  const topology = probe.topology;
  if (!topology || probe.ancestors.some((ancestor) =>
    !ancestor.binding || !ancestor.ownerFunction
  )) {
    return;
  }

  const ancestorBindings = [];
  for (const ancestor of probe.ancestors) {
    const binding = findOwnedVariable(index, ancestor.binding, ancestor.ownerFunction);
    if (
      !binding
      || isStaticallyUnreachable(binding)
      || !bindingCreatesElement(binding.node.init, ancestor.element)
      || !bindingHasClasses({ binding, classes: ancestor.classes, index })
    ) {
      errors.push(
        `Dynamic action "${action.name}" runtime topology does not prove `
        + `${ancestor.ownerFunction}.${ancestor.binding} as ${ancestor.element}.${ancestor.classes.join('.')}`
      );
      return;
    }
    ancestorBindings.push(binding);
  }

  for (let indexValue = 0; indexValue < ancestorBindings.length - 1; indexValue += 1) {
    const parentDescriptor = probe.ancestors[indexValue];
    const childDescriptor = probe.ancestors[indexValue + 1];
    if (parentDescriptor.ownerFunction !== childDescriptor.ownerFunction) continue;
    if (!hasAppendEdge({
      parent: ancestorBindings[indexValue],
      child: ancestorBindings[indexValue + 1],
      index,
    })) {
      errors.push(
        `Dynamic action "${action.name}" runtime topology is missing append `
        + `${parentDescriptor.binding} -> ${childDescriptor.binding}`
      );
      return;
    }
  }

  const constructionRootIndex = probe.ancestors.findIndex(
    ({ ownerFunction }) => ownerFunction === action.source.constructionFunction
  );
  const listRootIndex = probe.ancestors.findIndex(
    ({ ownerFunction }) => ownerFunction === topology.listFunction
  );
  if (
    constructionRootIndex < 0
    || listRootIndex < 0
    || !verifyCrossFunctionTopology({
      action,
      constructionRoot: ancestorBindings[constructionRootIndex],
      externalContainer: ancestorBindings[listRootIndex + 1],
      errors,
      index,
      topology,
    })
  ) {
    errors.push(
      `Dynamic action "${action.name}" runtime topology does not prove externalContainer `
      + `to ${probe.ancestors[constructionRootIndex]?.binding ?? 'extension root'} dataflow`
    );
  }
}

function verifyCrossFunctionTopology({
  action,
  constructionRoot,
  externalContainer,
  errors,
  index,
  topology,
}) {
  if (!constructionRoot || !externalContainer) return false;
  const constructionFunction = findUniqueFunction(index, action.source.constructionFunction);
  const recordFunction = findUniqueFunction(index, topology.recordFunction);
  const listFunction = findUniqueFunction(index, topology.listFunction);
  if (!constructionFunction || !recordFunction || !listFunction) return false;

  const returnsConstructionRoot = findDescendants(index, constructionFunction.node)
    .some((record) =>
      record.node.type === 'ReturnStatement'
      && record.node.argument?.type === 'Identifier'
      && resolveUniqueBinding(record.node.argument, record, index)?.declaration.node === constructionRoot.node
      && !isStaticallyUnreachable(record)
    );
  if (!returnsConstructionRoot) return false;

  const recordBinding = findOwnedVariable(index, topology.recordBinding, topology.recordFunction);
  const externalFlag = findOwnedVariable(index, topology.externalFlag, topology.recordFunction);
  const recordCall = callExpressionFromValue(recordBinding?.node.init);
  if (
    !recordBinding
    || !externalFlag
    || recordCall?.callee?.type !== 'Identifier'
    || recordCall.callee.name !== action.source.constructionFunction
    || !resolvesUniqueCallableBinding(recordCall.callee, index.records.get(recordCall), index)
  ) {
    return false;
  }
  const constructionFlagIndex = constructionFunction.node.params.findIndex((parameter) =>
    parameter.type === 'Identifier' && parameter.name === topology.constructionFlagParameter
  );
  const constructionFlag = index.bindings.find((binding) =>
    binding.kind === 'parameter'
    && binding.declaration === constructionFunction
    && binding.identifier === constructionFunction.node.params[constructionFlagIndex]
  );
  const externalFlagBinding = resolveBindingRecord(externalFlag, index);
  if (
    constructionFlagIndex < 0
    || !constructionFlag
    || !callArgumentResolvesToBinding(
      recordCall,
      constructionFlagIndex,
      externalFlagBinding,
      index
    )
  ) {
    errors.push(
      `Dynamic action "${action.name}" runtime topology construction flag `
      + `does not flow from ${topology.recordFunction}.${topology.externalFlag}`
    );
    return false;
  }
  const className = readSingleClassSelector(action.selector);
  const guardedConstruction = index.calls.some((call) => {
    if (
      ownerFunctionName(call) !== action.source.constructionFunction
      || call.node.callee.type !== 'Identifier'
      || call.node.callee.name !== action.source.factory
      || readStaticString(call.node.arguments[0]) !== className
      || !resolvesUniqueCallableBinding(call.node.callee, call, index)
      || isStaticallyUnreachable(call)
    ) {
      return false;
    }
    return call.ancestors.some((ancestor) =>
      ancestor.type === 'IfStatement'
      && ancestor.test.type === 'Identifier'
      && resolveUniqueBinding(
        ancestor.test,
        index.records.get(ancestor),
        index
      ) === constructionFlag
      && recordIsWithin(call, ancestor.consequent)
    );
  });
  if (!guardedConstruction) {
    errors.push(
      `Dynamic action "${action.name}" runtime topology construction flag `
      + `does not guard the action factory`
    );
    return false;
  }
  const recordReturn = findDescendants(index, recordFunction.node).find((record) =>
    record.node.type === 'ReturnStatement'
    && record.node.argument?.type === 'ObjectExpression'
    && objectExpressionReturnsBindings(
      record.node.argument,
      [recordBinding.node, externalFlag.node],
      record,
      index
    )
  );
  if (!recordReturn || isStaticallyUnreachable(recordReturn)) return false;

  const listBinding = findOwnedVariable(index, topology.listBinding, topology.listFunction);
  if (!listBinding || !initializerCallsFunction({
    binding: listBinding,
    functionName: topology.recordFunction,
    method: 'map',
    index,
  })) {
    return false;
  }

  const forEachCall = index.calls.find((call) =>
    ownerFunctionName(call) === topology.listFunction
    && memberPropertyName(call.node.callee) === 'forEach'
    && call.node.callee.object?.type === 'Identifier'
    && resolveUniqueBinding(call.node.callee.object, call, index)?.declaration.node === listBinding.node
    && isFunctionNode(call.node.arguments[0])
    && !isStaticallyUnreachable(call)
  );
  if (!forEachCall) return false;
  const callback = forEachCall.node.arguments[0];
  const callbackParameter = callback.params[0];
  if (callbackParameter?.type !== 'Identifier') return false;

  const destructuring = index.variables.find((record) =>
    record.ancestors.includes(callback)
    && record.node.id.type === 'ObjectPattern'
    && record.node.init?.type === 'Identifier'
    && record.node.init.name === callbackParameter.name
  );
  if (!destructuring) return false;
  const destructuredRecord = findObjectPatternBinding(
    destructuring.node.id,
    topology.recordBinding,
    destructuring,
    index
  );
  const destructuredFlag = findObjectPatternBinding(
    destructuring.node.id,
    topology.externalFlag,
    destructuring,
    index
  );
  if (!destructuredRecord || !destructuredFlag) return false;

  const selectedContainer = index.variables.find((record) =>
    record.ancestors.includes(callback)
    && record.node.id.type === 'Identifier'
    && record.node.id.name === topology.selectedContainer
    && record.node.init?.type === 'ConditionalExpression'
  );
  if (!selectedContainer) return false;
  const selection = selectedContainer.node.init;
  if (
    selection.test.type !== 'Identifier'
    || resolveUniqueBinding(selection.test, selectedContainer, index) !== destructuredFlag
  ) {
    return false;
  }
  const externalContainerBinding = resolveBindingRecord(externalContainer, index);
  const trueBranch = selection.consequent.type === 'Identifier'
    ? resolveUniqueBinding(selection.consequent, selectedContainer, index)
    : null;
  const falseBranch = selection.alternate.type === 'Identifier'
    ? resolveUniqueBinding(selection.alternate, selectedContainer, index)
    : null;
  if (trueBranch !== externalContainerBinding || falseBranch === externalContainerBinding) {
    errors.push(
      `Dynamic action "${action.name}" runtime topology true branch `
      + `does not select externalContainer`
    );
    return false;
  }

  return index.calls.some((call) =>
    call.ancestors.includes(callback)
    && ['append', 'appendChild'].includes(memberPropertyName(call.node.callee))
    && call.node.callee.object?.type === 'Identifier'
    && resolveUniqueBinding(call.node.callee.object, call, index)?.declaration.node === selectedContainer.node
    && call.node.arguments.some((argument) =>
      argument.type === 'Identifier'
      && resolveUniqueBinding(argument, call, index) === destructuredRecord
    )
    && !isStaticallyUnreachable(call)
  );
}

function findOwnedVariable(index, name, ownerFunction) {
  const matches = index.variables.filter((record) =>
    record.node.id.type === 'Identifier'
    && record.node.id.name === name
    && ownerFunctionName(record) === ownerFunction
  );
  return matches.length === 1 ? matches[0] : null;
}

function findUniqueFunction(index, name) {
  const matches = index.functions.filter((record) =>
    record.node.id?.name === name && !isStaticallyUnreachable(record)
  );
  return matches.length === 1 ? matches[0] : null;
}

function findDescendants(index, root) {
  return [...index.records.values()].filter((record) => record.ancestors.includes(root));
}

function bindingCreatesElement(init, element) {
  if (
    init?.type === 'CallExpression'
    && memberPropertyName(init.callee) === 'createElement'
    && readStaticString(init.arguments[0]) === element
  ) {
    return true;
  }
  const jqueryCall = findCallInSubtree(init, (call) =>
    call.callee.type === 'Identifier'
    && call.callee.name === '$'
    && typeof readStaticString(call.arguments[0]) === 'string'
  );
  if (!jqueryCall) return false;
  const html = readStaticString(jqueryCall.arguments[0]);
  const { document } = parseHTML(`<!doctype html><html><body>${html}</body></html>`);
  return document.body.firstElementChild?.localName === element;
}

function bindingHasClasses({ binding, classes, index }) {
  const found = new Set();
  for (const call of index.calls) {
    if (
      memberPropertyName(call.node.callee) === 'add'
      && memberPropertyName(call.node.callee.object) === 'classList'
      && call.node.callee.object.object?.type === 'Identifier'
      && resolveUniqueBinding(call.node.callee.object.object, call, index)?.declaration.node === binding.node
      && !isStaticallyUnreachable(call)
    ) {
      for (const argument of call.node.arguments) {
        const className = readStaticString(argument);
        if (className) found.add(className);
      }
    }
  }
  walkAst(binding.node.init, [], (node) => {
    if (node.type !== 'CallExpression' || memberPropertyName(node.callee) !== 'addClass') return;
    for (const argument of node.arguments) {
      const value = readStaticString(argument);
      if (value) {
        for (const className of value.split(/\s+/)) found.add(className);
      }
    }
  });
  return classes.every((className) => found.has(className));
}

function hasAppendEdge({ parent, child, index }) {
  return index.calls.some((call) => {
    if (
      !['append', 'appendChild'].includes(memberPropertyName(call.node.callee))
      || !call.node.arguments.some((argument) =>
        argument.type === 'Identifier'
        && resolveUniqueBinding(argument, call, index)?.declaration.node === child.node
      )
      || isStaticallyUnreachable(call)
    ) {
      return false;
    }
    if (
      call.node.callee.object?.type === 'Identifier'
      && resolveUniqueBinding(call.node.callee.object, call, index)?.declaration.node === parent.node
    ) {
      return true;
    }
    return subtreeContains(parent.node.init, call.node);
  });
}

function objectExpressionReturnsBindings(objectExpression, bindings, reference, index) {
  const returned = objectExpression.properties
    .filter((property) => property.type === 'Property' && property.value.type === 'Identifier')
    .map((property) => resolveUniqueBinding(property.value, reference, index)?.declaration.node);
  return bindings.every((binding) => returned.includes(binding));
}

function initializerCallsFunction({ binding, functionName, method, index }) {
  const call = findCallInSubtree(binding.node.init, (candidate) =>
    memberPropertyName(candidate.callee) === method
    && candidate.arguments[0]?.type === 'Identifier'
    && candidate.arguments[0].name === functionName
  );
  if (!call) return false;
  return resolvesUniqueCallableBinding(
    call.arguments[0],
    index.records.get(call),
    index
  );
}

function findObjectPatternBinding(pattern, propertyName, declaration, index) {
  const property = pattern.properties.find((entry) =>
    entry.type === 'Property' && memberPropertyNameFromProperty(entry) === propertyName
  );
  if (!property || property.value.type !== 'Identifier') return null;
  return resolveUniqueBinding(property.value, declaration, index);
}

function memberPropertyNameFromProperty(property) {
  if (!property.computed && property.key.type === 'Identifier') return property.key.name;
  return readStaticString(property.key);
}

function resolveBindingRecord(variable, index) {
  return index.bindings.find((binding) => binding.declaration === variable) ?? null;
}

function findCallInSubtree(root, predicate) {
  let result = null;
  walkAst(root, [], (node) => {
    if (!result && node.type === 'CallExpression' && predicate(node)) result = node;
  });
  return result;
}

function subtreeContains(root, target) {
  let found = false;
  walkAst(root, [], (node) => {
    if (node === target) found = true;
  });
  return found;
}

function nearestLexicalScope(ancestors) {
  return ancestors.findLast((ancestor) =>
    ['BlockStatement', 'Program'].includes(ancestor.type)
  ) ?? null;
}

function validateActionSource(source, label, selector, errors) {
  if (!isObject(source)) {
    errors.push(`${label}.source must be an object`);
    return;
  }
  if (!['html', 'javascript'].includes(source.type)) {
    errors.push(`${label}.source.type must be html or javascript`);
  }
  if (!isNonEmptyString(source.path)) {
    errors.push(`${label}.source.path must be a non-empty string`);
  } else {
    validateRelativePath(source.path, `${label}.source.path`, errors);
  }

  if (source.type === 'javascript') {
    for (const field of [
      'factory',
      'event',
      'handler',
      'eventSelector',
      'constructionFunction',
      'eventFunction',
    ]) {
      if (!isNonEmptyString(source[field])) {
        errors.push(`${label}.source.${field} must be a non-empty string`);
      }
    }
    if (isNonEmptyString(source.eventSelector)) {
      validateSelector(source.eventSelector, `${label}.source.eventSelector`, errors);
    }
    if (source.delegationReceiver !== 'document') {
      errors.push(`${label}.source.delegationReceiver must equal document`);
    }
    if (!readSingleClassSelector(selector)) {
      errors.push(`${label}.selector must be one simple class selector for JavaScript actions`);
    }
    validateRuntimeProbe(source.runtimeProbe, `${label}.source.runtimeProbe`, errors);
    if (source.visibilityTransition !== undefined) {
      if (!isObject(source.visibilityTransition)) {
        errors.push(`${label}.source.visibilityTransition must be an object`);
      } else {
        if (!isNonEmptyString(source.visibilityTransition.className)) {
          errors.push(`${label}.source.visibilityTransition.className must be a non-empty string`);
        }
        if (source.visibilityTransition.required !== true) {
          errors.push(`${label}.source.visibilityTransition.required must be true`);
        }
        if (!isNonEmptyString(source.visibilityTransition.querySelector)) {
          errors.push(`${label}.source.visibilityTransition.querySelector must be a non-empty string`);
        } else {
          validateSelector(
            source.visibilityTransition.querySelector,
            `${label}.source.visibilityTransition.querySelector`,
            errors
          );
          if (
            canonicalizeSingleSelector(
              source.visibilityTransition.querySelector,
              `${label}.source.visibilityTransition.querySelector`,
              errors
            ) !== canonicalizeSingleSelector(selector, `${label}.selector`, errors)
          ) {
            errors.push(`${label}.source.visibilityTransition.querySelector must equal the action selector`);
          }
        }
        for (const field of [
          'queryReceiver',
          'recordSelectorBinding',
          'recordIdentityBinding',
          'recordSelectorFactory',
          'ownerFunction',
          'dataFactory',
        ]) {
          if (!isNonEmptyString(source.visibilityTransition[field])) {
            errors.push(
              `${label}.source.visibilityTransition.${field} must be a non-empty string`
            );
          }
        }
        validateVisibilityCondition(
          source.visibilityTransition.condition,
          `${label}.source.visibilityTransition.condition`,
          errors
        );
      }
    }
  }
}

function validateRuntimeProbe(runtimeProbe, label, errors) {
  if (!isObject(runtimeProbe)) {
    errors.push(`${label} must be an object`);
    return;
  }
  validateElementName(runtimeProbe.element, `${label}.element`, errors);
  const ancestors = requireArray(runtimeProbe.ancestors, `${label}.ancestors`, errors);
  if (ancestors.length === 0) {
    errors.push(`${label}.ancestors must not be empty`);
  }
  for (const [index, ancestor] of ancestors.entries()) {
    const ancestorLabel = `${label}.ancestors[${index}]`;
    if (!isObject(ancestor)) {
      errors.push(`${ancestorLabel} must be an object`);
      continue;
    }
    validateElementName(ancestor.element, `${ancestorLabel}.element`, errors);
    const classes = requireArray(ancestor.classes, `${ancestorLabel}.classes`, errors);
    if (classes.length === 0 || classes.some((className) => !isNonEmptyString(className))) {
      errors.push(`${ancestorLabel}.classes must contain non-empty class names`);
    }
    for (const field of ['binding', 'ownerFunction']) {
      if (!isNonEmptyString(ancestor[field])) {
        errors.push(`${label} runtime topology requires ${ancestorLabel}.${field}`);
      }
    }
  }
  if (!isObject(runtimeProbe.topology)) {
    errors.push(`${label} runtime topology must be an object`);
  } else {
    for (const field of [
      'recordFunction',
      'recordBinding',
      'listFunction',
      'listBinding',
      'externalFlag',
      'selectedContainer',
      'constructionFlagParameter',
    ]) {
      if (!isNonEmptyString(runtimeProbe.topology[field])) {
        errors.push(`${label}.topology.${field} must be a non-empty string`);
      }
    }
  }
}

function validateElementName(value, label, errors) {
  if (!isNonEmptyString(value) || !/^[a-z][a-z0-9-]*$/i.test(value)) {
    errors.push(`${label} must be a valid element name`);
  }
}

function validateVisibilityCondition(condition, label, errors) {
  if (!isObject(condition)) {
    errors.push(`${label} must be an object`);
    return;
  }
  for (const field of ['object', 'property']) {
    if (!isNonEmptyString(condition[field])) {
      errors.push(`${label}.${field} must be a non-empty string`);
    }
  }
  if (condition.operator !== '===') {
    errors.push(`${label}.operator must be ===`);
  }
  if (!Object.hasOwn(condition, 'value')) {
    errors.push(`${label}.value is required`);
  } else if (
    condition.value !== null
    && !['boolean', 'number', 'string'].includes(typeof condition.value)
  ) {
    errors.push(`${label}.value must be a JSON primitive`);
  }
}

function parseSelectorList(selector, label, errors) {
  try {
    return selectorParser().astSync(selector).nodes.map((node) => node.toString().trim());
  } catch (error) {
    errors.push(`Invalid CSS selector at ${label}: ${formatError(error)}`);
    return [];
  }
}

function canonicalizeSingleSelector(selector, label, errors) {
  const selectors = parseSelectorList(selector, label, errors);
  if (selectors.length !== 1) {
    errors.push(`${label} must contain exactly one selector`);
    return null;
  }
  return selectors[0];
}

function validateSelector(selector, label, errors) {
  if (!isNonEmptyString(selector)) {
    errors.push(`${label} must be a non-empty string`);
    return;
  }
  canonicalizeSingleSelector(selector, label, errors);
}

function readSingleClassSelector(selector) {
  try {
    const ast = selectorParser().astSync(selector);
    if (ast.nodes.length !== 1 || ast.nodes[0].nodes.length !== 1) return null;
    const node = ast.nodes[0].nodes[0];
    return node.type === 'class' ? node.value : null;
  } catch {
    return null;
  }
}

function selectorContainsClass(selector, className) {
  try {
    let found = false;
    selectorParser((root) => {
      root.walkClasses((node) => {
        if (node.value === className) found = true;
      });
    }).processSync(selector);
    return found;
  } catch {
    return false;
  }
}

function selectorHasPseudoElement(selector) {
  try {
    let found = false;
    selectorParser((root) => {
      root.walkPseudos((node) => {
        if (node.value.startsWith('::')) found = true;
      });
    }).processSync(selector);
    return found;
  } catch {
    return false;
  }
}

function walkAst(node, ancestors, visit) {
  if (!node || typeof node !== 'object' || typeof node.type !== 'string') return;
  visit(node, ancestors);
  const nextAncestors = [...ancestors, node];
  for (const [key, value] of Object.entries(node)) {
    if (key === 'loc' || key === 'start' || key === 'end') continue;
    if (Array.isArray(value)) {
      for (const child of value) walkAst(child, nextAncestors, visit);
    } else {
      walkAst(value, nextAncestors, visit);
    }
  }
}

function nearestFunction(ancestors) {
  return ancestors.findLast((ancestor) =>
    ['FunctionDeclaration', 'FunctionExpression', 'ArrowFunctionExpression'].includes(ancestor.type)
  ) ?? null;
}

function memberPropertyName(node) {
  if (node?.type !== 'MemberExpression') return null;
  if (!node.computed && node.property.type === 'Identifier') return node.property.name;
  return readStaticString(node.property);
}

function readStaticString(node) {
  if (node?.type === 'Literal' && typeof node.value === 'string') return node.value;
  if (node?.type === 'TemplateLiteral' && node.expressions.length === 0) {
    return node.quasis[0].value.cooked;
  }
  return null;
}

function parseEndpointKey(endpoint) {
  if (!isNonEmptyString(endpoint)) return null;
  const separator = endpoint.indexOf(' ');
  if (separator <= 0) return null;
  const method = endpoint.slice(0, separator);
  const pathName = endpoint.slice(separator + 1);
  if (!/^[A-Z]+$/.test(method) || !pathName.startsWith('/')) return null;
  return { method, path: pathName };
}

function resolveWithin(root, relativePath, errors, label) {
  const absoluteRoot = path.resolve(root);
  const absolutePath = path.resolve(absoluteRoot, relativePath);
  const relative = path.relative(absoluteRoot, absolutePath);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    errors.push(`${label} escapes Web root: ${relativePath}`);
    return null;
  }
  return absolutePath;
}

function validateRelativePath(value, label, errors) {
  const normalized = path.posix.normalize(value.replaceAll('\\', '/'));
  if (path.posix.isAbsolute(normalized) || normalized === '..' || normalized.startsWith('../')) {
    errors.push(`${label} must stay within the Web root`);
  }
}

function requireArray(value, label, errors) {
  if (!Array.isArray(value)) {
    errors.push(`${label} must be an array`);
    return [];
  }
  return value;
}

function requireStringFields(value, fields, label, errors) {
  for (const field of fields) {
    if (!isNonEmptyString(value[field])) {
      errors.push(`${label}.${field} must be a non-empty string`);
    }
  }
}

function validateUnique(value, values, label, errors) {
  if (!isNonEmptyString(value)) return;
  if (values.has(value)) {
    errors.push(`${label} duplicates ${value}`);
  }
  values.add(value);
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isNonEmptyString(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

function buildResult({ errors, implementedActions, configuredActions = 0, hiddenSelectors, localStylesheets }) {
  return {
    ok: errors.length === 0,
    errors,
    summary: {
      implementedActions,
      configuredActions,
      hiddenSelectors,
      localStylesheets,
    },
  };
}

function formatError(error) {
  return error instanceof Error ? error.message : String(error);
}

async function readJson(file) {
  const absolutePath = path.resolve(file);
  try {
    return JSON.parse(await readFile(absolutePath, 'utf8'));
  } catch (error) {
    throw new Error(`Unable to read JSON ${absolutePath}: ${formatError(error)}`);
  }
}

async function main() {
  const { values } = parseArgs({
    options: {
      'web-root': { type: 'string' },
      'ui-contract': { type: 'string' },
      'api-contract': { type: 'string' },
      capabilities: { type: 'string' },
      report: { type: 'string' },
    },
  });
  if (!values['web-root']) throw new Error('Missing required option: --web-root');
  if (!values['api-contract']) throw new Error('Missing required option: --api-contract');
  if (!values.capabilities) throw new Error('Missing required option: --capabilities');

  const result = await verifyUiCapabilityContract({
    webRoot: values['web-root'],
    uiContractFile: values['ui-contract']
      ?? path.join(values['web-root'], UI_CONTRACT_NAME),
    apiContractFile: values['api-contract'],
    capabilityFile: values.capabilities,
  });
  if (values.report) {
    await writeFile(path.resolve(values.report), `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  }
  console.log(JSON.stringify(result, null, 2));
  if (!result.ok) {
    throw new Error(result.errors.join('\n'));
  }
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((error) => {
    console.error(formatError(error));
    process.exitCode = 1;
  });
}
