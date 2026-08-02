import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { parse } from 'acorn';

const PROJECT_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const WEB_ROOT = path.join(PROJECT_ROOT, 'mobile/app/src/main/assets/sillytavern-web');
const USER_INPUT_ROOTS = new Map([
    ['transform output', path.join(PROJECT_ROOT, 'build/no-node-payload/sillytavern-web')],
    ['Android app assets', path.join(PROJECT_ROOT, 'mobile/app/src/main/assets/sillytavern-web')],
]);

const FILE_RULES = new Map([
    ['scripts/extensions/vectors/index.js', new Set([
        'activatedEntries',
        'chunks',
        'entry',
        'file',
        'fileName',
        'fileText',
        'fileUrl',
        'queriedText',
        'queryResults',
        'queryText',
        'textResult',
        'world',
    ])],
    ['scripts/power-user.js', new Set(['params'])],
]);

function walk(node, visit, parent = null) {
    if (!node || typeof node !== 'object') {
        return;
    }
    visit(node, parent);
    for (const value of Object.values(node)) {
        if (Array.isArray(value)) {
            value.forEach(child => walk(child, visit, node));
        } else if (value && typeof value === 'object') {
            walk(value, visit, node);
        }
    }
}

function consoleMethod(node) {
    if (
        node?.type !== 'CallExpression' ||
        node.callee?.type !== 'MemberExpression' ||
        node.callee.object?.type !== 'Identifier' ||
        node.callee.object.name !== 'console'
    ) {
        return null;
    }
    return node.callee.computed
        ? node.callee.property?.value
        : node.callee.property?.name;
}

test('shipped vector UI never forwards user text carriers to Chromium console', async () => {
    const violations = [];

    for (const [relativePath, forbiddenNames] of FILE_RULES) {
        const source = await readFile(path.join(WEB_ROOT, relativePath), 'utf8');
        const ast = parse(source, {
            ecmaVersion: 'latest',
            locations: true,
            sourceType: 'module',
        });

        walk(ast, node => {
            const method = consoleMethod(node);
            if (!method) {
                return;
            }
            const referenced = new Set();
            node.arguments.forEach(argument => walk(argument, (child, parent) => {
                const lengthOnly = parent?.type === 'MemberExpression' &&
                    parent.object === child &&
                    !parent.computed &&
                    parent.property?.name === 'length';
                if (child.type === 'Identifier' && forbiddenNames.has(child.name) && !lengthOnly) {
                    referenced.add(child.name);
                }
            }));
            if (referenced.size > 0) {
                violations.push({
                    file: relativePath,
                    line: node.loc.start.line,
                    method,
                    names: [...referenced].sort(),
                });
            }
        });
    }

    assert.deepEqual(violations, []);
});

test('generated and Android app assets never forward user input to Chromium console', async () => {
    const violations = [];

    for (const [rootName, webRoot] of USER_INPUT_ROOTS) {
        const source = await readFile(path.join(webRoot, 'scripts/RossAscends-mods.js'), 'utf8');
        const ast = parse(source, {
            ecmaVersion: 'latest',
            locations: true,
            sourceType: 'module',
        });

        walk(ast, node => {
            const method = consoleMethod(node);
            if (!method) {
                return;
            }
            const forwardsUserInput = node.arguments.some(argument => {
                let found = false;
                walk(argument, child => {
                    found ||= child.type === 'Identifier' && child.name === 'userInput';
                });
                return found;
            });
            if (forwardsUserInput) {
                violations.push({ root: rootName, line: node.loc.start.line, method });
            }
        });
    }

    assert.deepEqual(violations, []);
});
