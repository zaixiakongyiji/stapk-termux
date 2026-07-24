import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

const capabilities = {
  schemaVersion: 1,
  capabilities: [
    {
      id: 'core.fixture',
      kind: 'core',
      defaultStatus: 'implemented',
      endpointPrefixes: ['/api/action'],
    },
    {
      id: 'excluded.fixture',
      kind: 'excluded',
      defaultStatus: 'unsupported_hidden',
      endpointPrefixes: ['/api/unsupported'],
    },
  ],
};

function htmlActionContract() {
  return {
    schemaVersion: 1,
    hiddenStylesheets: [
      {
        path: 'css/main.css',
        catalogBefore: '#catalog-end',
      },
    ],
    implementedActions: [
      {
        name: 'Fixture action',
        selector: '#action',
        capability: 'core.fixture',
        endpoint: 'POST /api/action',
        source: { type: 'html', path: 'index.html' },
      },
    ],
    hiddenSelectors: [
      {
        selector: '.unsupported',
        capability: 'excluded.fixture',
        status: 'unsupported_hidden',
        reason: 'The fixture capability is intentionally unsupported.',
      },
    ],
  };
}

function dynamicActionContract({ verifiedTopology = true } = {}) {
  const runtimeProbe = {
    element: 'button',
    ancestors: [
      {
        element: 'div',
        classes: ['extensions_info'],
        ...(verifiedTopology
          ? { binding: 'extensionsMenu', ownerFunction: 'showExtensionsDetails' }
          : {}),
      },
      ...(verifiedTopology
        ? [{
            element: 'div',
            classes: ['marginBot10'],
            binding: 'externalContainer',
            ownerFunction: 'showExtensionsDetails',
          }]
        : []),
      {
        element: 'div',
        classes: ['extension_block'],
        ...(verifiedTopology
          ? { binding: 'block', ownerFunction: 'generateExtensionElement' }
          : {}),
      },
      {
        element: 'div',
        classes: ['extension_actions'],
        ...(verifiedTopology
          ? { binding: 'actionsDiv', ownerFunction: 'generateExtensionElement' }
          : {}),
      },
    ],
    ...(verifiedTopology
      ? {
          topology: {
            recordFunction: 'getExtensionData',
            recordBinding: 'extensionElement',
            listFunction: 'showExtensionsDetails',
            listBinding: 'extensions',
            externalFlag: 'isExternal',
            selectedContainer: 'container',
            constructionFlagParameter: 'isExternal',
          },
        }
      : {}),
  };
  return {
    ...htmlActionContract(),
    implementedActions: [
      {
        name: 'Dynamic update',
        selector: '.btn_update',
        capability: 'core.fixture',
        endpoint: 'POST /api/action',
        source: {
          type: 'javascript',
          path: 'scripts/extensions.js',
          factory: 'makeActionButton',
          event: 'click',
          handler: 'onUpdateClick',
          eventSelector: '.extensions_info .extension_block .btn_update',
          delegationReceiver: 'document',
          constructionFunction: 'generateExtensionElement',
          eventFunction: 'initExtensions',
          runtimeProbe,
          visibilityTransition: {
            className: 'displayNone',
            required: true,
            querySelector: '.btn_update',
            queryReceiver: 'extensionBlock',
            recordSelectorBinding: 'selector',
            recordIdentityBinding: 'externalId',
            recordSelectorFactory: 'getNameSelector',
            ownerFunction: 'checkForUpdatesManual',
            dataFactory: 'getExtensionVersion',
            condition: {
              object: 'data',
              property: 'isUpToDate',
              operator: '===',
              value: false,
            },
          },
        },
      },
    ],
  };
}

function dynamicDeleteActionContract(options) {
  const contract = dynamicActionContract(options);
  contract.implementedActions[0] = {
    name: 'Dynamic delete',
    selector: '.btn_delete',
    capability: 'core.fixture',
    endpoint: 'POST /api/action',
    source: {
      type: 'javascript',
      path: 'scripts/extensions.js',
      factory: 'makeActionButton',
      event: 'click',
      handler: 'onDeleteClick',
      eventSelector: '.extensions_info .extension_block .btn_delete',
      delegationReceiver: 'document',
      constructionFunction: 'generateExtensionElement',
      eventFunction: 'initExtensions',
      runtimeProbe: contract.implementedActions[0].source.runtimeProbe,
    },
  };
  return contract;
}

function dynamicScript({
  actionClass = 'btn_update',
  handler = 'onUpdateClick',
  appendInSameFunction = true,
  bindingHandler = handler,
  condition = 'data.isUpToDate === false',
  includeVisibilityTransition = true,
  detachedAppend = false,
  detachedVisibilityRecord = false,
  wrongVisibilityReceiver = false,
  shadowFactory = false,
  shadowHandler = false,
  wrapAllEvidenceInFalse = false,
  visibilityInFalse = false,
  unrelatedVisibilityHelper = false,
  eventSelector = `.extensions_info .extension_block .${actionClass}`,
  delegationReceiver = 'document',
  constructionGuard = 'isExternal',
  constructionFlagArgument = 'isExternal',
  swappedExternalBranch = false,
  constructionLoop = false,
} = {}) {
  const lines = [
    'function generateExtensionElement(isExternal) {',
    "  const block = document.createElement('div');",
    "  block.classList.add('extension_block');",
    "  const actionsDiv = document.createElement('div');",
    "  actionsDiv.classList.add('extension_actions');",
    '  function makeActionButton(cls) {',
    "    const button = document.createElement('button');",
    '    button.classList.add(cls);',
    '    return button;',
    '  }',
    `  ${constructionLoop ? 'while (false)' : `if (${constructionGuard})`} {`,
    ...(shadowFactory ? ['    const makeActionButton = false;'] : []),
    `    const actionBtn = makeActionButton('${actionClass}');`,
    ...(actionClass === 'btn_update' ? ["    actionBtn.classList.add('displayNone');"] : []),
    ...(appendInSameFunction ? ['    actionsDiv.appendChild(actionBtn);'] : []),
    '  }',
    '  block.appendChild(actionsDiv);',
    '  return block;',
    '}',
    ...(!appendInSameFunction ? [
      'function appendUnrelatedAction(actionsDiv) {',
      "  const actionBtn = document.createElement('button');",
      '  actionsDiv.appendChild(actionBtn);',
      '}',
    ] : []),
    'function getExtensionData() {',
    '  const isExternal = true;',
    `  const extensionElement = generateExtensionElement(${constructionFlagArgument});`,
    '  return { isExternal, extensionElement };',
    '}',
    'function showExtensionsDetails() {',
    "  const defaultContainer = document.createElement('div');",
    "  defaultContainer.classList.add('marginBot10');",
    "  const externalContainer = document.createElement('div');",
    "  externalContainer.classList.add('marginBot10');",
    "  const detachedContainer = document.createElement('div');",
    '  const extensions = [].map(getExtensionData);',
    '  extensions.forEach(value => {',
    '    const { isExternal, extensionElement } = value;',
    `    const container = isExternal ? ${swappedExternalBranch ? 'defaultContainer' : 'externalContainer'} : ${swappedExternalBranch ? 'externalContainer' : 'defaultContainer'};`,
    `    ${detachedAppend ? 'detachedContainer' : 'container'}.appendChild(extensionElement);`,
    '  });',
    "  const extensionsMenu = $('<div></div>')",
    "    .addClass('extensions_info')",
    '    .append(defaultContainer)',
    '    .append(externalContainer);',
    '  return extensionsMenu;',
    '}',
    'async function getExtensionVersion() { return {}; }',
    'function getNameSelector(value) { return value; }',
    'async function checkForUpdatesManual() {',
    "  const externalId = 'fixture-extension';",
    '  const data = await getExtensionVersion(externalId);',
    '  const selector = getNameSelector(externalId);',
    ...(detachedVisibilityRecord
      ? ["  const extensionBlock = document.createElement('div');"]
      : ['  const extensionBlock = document.querySelector(`.extension_block[data-name="${selector}"]`);']),
    ...(wrongVisibilityReceiver
      ? ["  const detachedBlock = document.createElement('div');"]
      : []),
    ...(visibilityInFalse ? ['  if (false) {'] : []),
    ...(!unrelatedVisibilityHelper ? [
      `  ${visibilityInFalse ? '  ' : ''}if (${condition}) {`,
      `  ${visibilityInFalse ? '  ' : ''}  const buttonElement = ${wrongVisibilityReceiver ? 'detachedBlock' : 'extensionBlock'}.querySelector('.btn_update');`,
      `  ${visibilityInFalse ? '  ' : ''}  if (buttonElement) {`,
      ...(includeVisibilityTransition
        ? [`  ${visibilityInFalse ? '  ' : ''}    buttonElement.classList.remove('displayNone');`]
        : []),
      `  ${visibilityInFalse ? '  ' : ''}  }`,
      `  ${visibilityInFalse ? '  ' : ''}}`,
    ] : []),
    ...(visibilityInFalse ? ['  }'] : []),
    '}',
    ...(unrelatedVisibilityHelper ? [
      'function unrelatedVisibility(extensionBlock, data) {',
      `  if (${condition}) {`,
      "    const buttonElement = extensionBlock.querySelector('.btn_update');",
      '    if (buttonElement) {',
      ...(includeVisibilityTransition
        ? ["      buttonElement.classList.remove('displayNone');"]
        : []),
      '    }',
      '    }',
      '  }',
    ] : []),
    `function ${handler}() {}`,
    'function unrelatedHandler() {}',
    'function initExtensions() {',
    ...(shadowHandler ? [`  const ${handler} = false;`] : []),
    `  ${delegationReceiver === 'document' ? '$(document)' : "$('.wrong-root')"}.on('click', '${eventSelector}', ${bindingHandler});`,
    '}',
  ];
  if (!wrapAllEvidenceInFalse) {
    return lines.join('\n');
  }
  return ['if (false) {', ...lines.map((line) => `  ${line}`), '}'].join('\n');
}

function dynamicDeleteScript(options = {}) {
  return dynamicScript({
    actionClass: 'btn_delete',
    handler: 'onDeleteClick',
    ...options,
  });
}

async function withFixture({
  contract = htmlActionContract(),
  css = [
    '.unsupported { display: none !important; }',
    '#catalog-end { display: block !important; }',
  ].join('\n'),
  html = '<!doctype html><link rel="stylesheet" href="css/main.css"><div class="panel" data-mode="x"><div class="wrapper"><button id="action"></button></div></div>',
  script = '',
  extraFiles = {},
}, fn) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'stapk-ui-contract-'));
  const webRoot = path.join(root, 'web');
  const uiContractFile = path.join(webRoot, 'stapk-ui-capabilities.json');
  const apiContractFile = path.join(root, 'api-contract.json');
  const capabilityFile = path.join(root, 'capabilities.json');

  try {
    await mkdir(path.join(webRoot, 'css'), { recursive: true });
    await mkdir(path.join(webRoot, 'scripts'), { recursive: true });
    await Promise.all([
      writeFile(path.join(webRoot, 'index.html'), html, 'utf8'),
      writeFile(path.join(webRoot, 'css', 'main.css'), css, 'utf8'),
      writeFile(path.join(webRoot, 'scripts', 'extensions.js'), script, 'utf8'),
      writeFile(uiContractFile, JSON.stringify(contract), 'utf8'),
      writeFile(capabilityFile, JSON.stringify(capabilities), 'utf8'),
      writeFile(apiContractFile, JSON.stringify({
        schemaVersion: 1,
        endpoints: [
          {
            method: 'POST',
            path: '/api/action',
            status: 'implemented',
            capability: 'core.fixture',
          },
        ],
      }), 'utf8'),
      ...Object.entries(extraFiles).map(async ([relativePath, content]) => {
        const absolutePath = path.join(webRoot, relativePath);
        await mkdir(path.dirname(absolutePath), { recursive: true });
        await writeFile(absolutePath, content, 'utf8');
      }),
    ]);
    await fn({ webRoot, uiContractFile, apiContractFile, capabilityFile });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

async function verifyFixture(paths) {
  const { verifyUiCapabilityContract } = await import(
    '../../scripts/stapk-verify-ui-capability-contract.mjs'
  );
  return verifyUiCapabilityContract(paths);
}

test('CSS parser detects display none important when a rule has extra declarations', async () => {
  await withFixture({
    css: [
      '.unsupported { display: none !important; }',
      '#catalog-end { display: block !important; }',
      '.panel #action { color: red; display: none !important; padding: 1rem; }',
    ].join('\n'),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('.panel #action')));
    assert.ok(result.errors.some((error) => error.includes('Fixture action')));
  });
});

test('recursive local CSS imports hide dynamic actions and terminate across duplicate cycles', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript(),
    css: [
      '@import "./nested/imported.css";',
      '@import url("./nested/imported.css");',
      '.unsupported { display: none !important; }',
      '#catalog-end { display: block !important; }',
    ].join('\n'),
    extraFiles: {
      'css/nested/imported.css': [
        '@import "../cycle.css";',
        '.imported-marker { color: red; }',
      ].join('\n'),
      'css/cycle.css': [
        '@import "./nested/imported.css";',
        '.btn_delete { display: none !important; }',
      ].join('\n'),
    },
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.equal(result.summary.localStylesheets, 3);
    assert.ok(result.errors.some((error) =>
      error.includes('Dynamic delete') && error.includes('.btn_delete')
    ));
  });
});

test('selector engine detects an implemented action hidden by a compound ancestor selector', async () => {
  await withFixture({
    css: [
      '.unsupported { display: none !important; }',
      '#catalog-end { display: block !important; }',
      '.panel[data-mode="x"] > .wrapper { display: none !important; }',
    ].join('\n'),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('.panel[data-mode="x"] > .wrapper')));
    assert.ok(result.errors.some((error) => error.includes('Fixture action')));
  });
});

test('formal hidden selector entries reject a core capability and an empty reason', async () => {
  const contract = htmlActionContract();
  contract.hiddenSelectors[0] = {
    ...contract.hiddenSelectors[0],
    capability: 'core.fixture',
    reason: '   ',
  };

  await withFixture({ contract }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('non-empty reason')));
    assert.ok(result.errors.some((error) => error.includes('excluded capability')));
  });
});

test('functional hidden rules after the formal catalog boundary remain outside the catalog', async () => {
  await withFixture({
    css: [
      '.unsupported { display: none !important; }',
      '#catalog-end { display: block !important; }',
      '#runtime-only[hidden] { color: red; display: none !important; }',
    ].join('\n'),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css"><button id="action"></button><div id="runtime-only"></div>',
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, true, result.errors.join('\n'));
  });
});

test('Acorn verifier rejects a dynamic update action without a visibility transition', async () => {
  const contract = dynamicActionContract();
  const fixture = {
    contract,
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicScript(),
  };

  await withFixture(fixture, async (paths) => {
    const result = await verifyFixture(paths);
    assert.equal(result.ok, true, result.errors.join('\n'));
  });

  await withFixture({
    ...fixture,
    script: dynamicScript({ includeVisibilityTransition: false }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(
      result.errors.some((error) => error.includes('visibility transition')),
      result.errors.join('\n')
    );
  });
});

test('dynamic runtime probe rejects a delete action hidden directly by CSS', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript(),
    css: [
      '.unsupported { display: none !important; }',
      '#catalog-end { display: block !important; }',
      '.btn_delete { display: none !important; }',
    ].join('\n'),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) =>
      error.includes('Dynamic delete') && error.includes('.btn_delete')
    ));
  });
});

test('dynamic runtime probe rejects an action inside a hidden compound ancestor', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript(),
    css: [
      '.unsupported { display: none !important; }',
      '#catalog-end { display: block !important; }',
      '.extensions_info > .marginBot10 > .extension_block { display: none !important; }',
    ].join('\n'),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) =>
      error.includes('Dynamic delete')
      && error.includes('.extensions_info > .marginBot10 > .extension_block')
    ));
  });
});

test('delegated click rejects a full selector that cannot match the runtime action', async () => {
  const contract = dynamicDeleteActionContract();
  contract.implementedActions[0].source.eventSelector = '.never .btn_delete';
  await withFixture({
    contract,
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript({ eventSelector: '.never .btn_delete' }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('delegated selector')));
  });
});

test('delegated click rejects a receiver that does not contain the runtime action', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript({ delegationReceiver: 'wrong-root' }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('delegation receiver')));
  });
});

test('runtime topology rejects a probe that omits the real marginBot10 ancestor', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract({ verifiedTopology: false }),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript(),
    css: [
      '.unsupported { display: none !important; }',
      '#catalog-end { display: block !important; }',
      '.extensions_info > .marginBot10 > .extension_block { display: none !important; }',
    ].join('\n'),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('runtime topology')));
  });
});

test('runtime topology rejects an extension element appended to a detached container', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript({ detachedAppend: true }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) =>
      error.includes('runtime topology') && error.includes('externalContainer')
    ));
  });
});

test('visibility rejects a detached element as the current extension record', async () => {
  await withFixture({
    contract: dynamicActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicScript({ detachedVisibilityRecord: true }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) =>
      error.includes('visibility') && error.includes('record')
    ));
  });
});

test('visibility rejects an action query made against the wrong receiver', async () => {
  await withFixture({
    contract: dynamicActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicScript({ wrongVisibilityReceiver: true }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) =>
      error.includes('visibility') && error.includes('query receiver')
    ));
  });
});

test('Acorn verifier rejects an append of a same-name variable from another function', async () => {
  await withFixture({
    contract: dynamicActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicScript({ appendInSameFunction: false }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('never appended')));
  });
});

test('Acorn verifier applies ToBoolean to numeric zero guards', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript({ constructionGuard: '0' }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('statically unreachable')));
  });
});

test('Acorn verifier applies ToBoolean to null guards', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript({ constructionGuard: 'null' }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('statically unreachable')));
  });
});

test('Acorn verifier evaluates static logical guards without executing source', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript({ constructionGuard: 'true && false' }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('statically unreachable')));
  });
});

test('Acorn verifier rejects evidence inside a statically false while loop', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript({ constructionLoop: true }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('statically unreachable')));
  });
});

test('Acorn verifier rejects a factory identifier shadowed by a non-callable binding', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript({ shadowFactory: true }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) =>
      error.includes('factory') && error.includes('callable binding')
    ));
  });
});

test('runtime topology rejects a literal false construction flag argument', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript({ constructionFlagArgument: 'false' }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) =>
      error.includes('runtime topology') && error.includes('construction flag')
    ));
  });
});

test('runtime topology requires the true flag branch to select externalContainer', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript({ swappedExternalBranch: true }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) =>
      error.includes('runtime topology') && error.includes('true branch')
    ));
  });
});

test('Acorn verifier rejects a delegated click binding with the wrong handler', async () => {
  await withFixture({
    contract: dynamicActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicScript({ bindingHandler: 'unrelatedHandler' }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('onUpdateClick')));
  });
});

test('Acorn verifier rejects a handler identifier shadowed by a non-callable binding', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript({ shadowHandler: true }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) =>
      error.includes('handler') && error.includes('callable binding')
    ));
  });
});

test('Acorn verifier rejects dynamic evidence inside an outer if false branch', async () => {
  await withFixture({
    contract: dynamicDeleteActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicDeleteScript({ wrapAllEvidenceInFalse: true }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('statically unreachable')));
  });
});

test('Acorn verifier rejects a visibility transition guarded by an unrelated condition', async () => {
  await withFixture({
    contract: dynamicActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicScript({ condition: 'data.other === false' }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(
      result.errors.some((error) => error.includes('visibility condition')),
      result.errors.join('\n')
    );
  });
});

test('Acorn verifier rejects a visibility transition inside an outer if false branch', async () => {
  await withFixture({
    contract: dynamicActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicScript({ visibilityInFalse: true }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('statically unreachable')));
  });
});

test('Acorn verifier rejects an exact visibility condition in an unrelated helper dataflow', async () => {
  await withFixture({
    contract: dynamicActionContract(),
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicScript({ unrelatedVisibilityHelper: true }),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) =>
      error.includes('visibility') && error.includes('dataflow')
    ));
  });
});

test('contract validator requires dynamic runtime, handler, query and condition metadata', async () => {
  const contract = dynamicActionContract();
  delete contract.implementedActions[0].source.handler;
  delete contract.implementedActions[0].source.eventSelector;
  delete contract.implementedActions[0].source.delegationReceiver;
  delete contract.implementedActions[0].source.runtimeProbe;
  delete contract.implementedActions[0].source.visibilityTransition.querySelector;
  delete contract.implementedActions[0].source.visibilityTransition.queryReceiver;
  delete contract.implementedActions[0].source.visibilityTransition.recordSelectorBinding;
  delete contract.implementedActions[0].source.visibilityTransition.recordIdentityBinding;
  delete contract.implementedActions[0].source.visibilityTransition.recordSelectorFactory;
  delete contract.implementedActions[0].source.visibilityTransition.condition;

  await withFixture({
    contract,
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicScript(),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('source.handler')));
    assert.ok(result.errors.some((error) => error.includes('source.eventSelector')));
    assert.ok(result.errors.some((error) => error.includes('source.delegationReceiver')));
    assert.ok(result.errors.some((error) => error.includes('source.runtimeProbe')));
    assert.ok(result.errors.some((error) => error.includes('querySelector')));
    assert.ok(result.errors.some((error) => error.includes('queryReceiver')));
    assert.ok(result.errors.some((error) => error.includes('recordSelectorBinding')));
    assert.ok(result.errors.some((error) => error.includes('recordIdentityBinding')));
    assert.ok(result.errors.some((error) => error.includes('recordSelectorFactory')));
    assert.ok(result.errors.some((error) => error.includes('condition')));
  });
});

test('contract validator requires the construction flag parameter metadata', async () => {
  const contract = dynamicActionContract();
  delete contract.implementedActions[0].source.runtimeProbe.topology.constructionFlagParameter;

  await withFixture({
    contract,
    html: '<!doctype html><link rel="stylesheet" href="css/main.css">',
    script: dynamicScript(),
  }, async (paths) => {
    const result = await verifyFixture(paths);

    assert.equal(result.ok, false);
    assert.ok(result.errors.some((error) => error.includes('constructionFlagParameter')));
  });
});
