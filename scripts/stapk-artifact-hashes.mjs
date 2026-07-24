import crypto from 'node:crypto';
import { existsSync } from 'node:fs';
import { readdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';

export async function hashDirectory(root) {
  const absoluteRoot = path.resolve(root);
  const hash = crypto.createHash('sha256');
  const files = await listFiles(absoluteRoot);

  for (const file of files) {
    const relativePath = toPosixPath(path.relative(absoluteRoot, file));
    hash.update(relativePath);
    hash.update('\0');
    hash.update(await readFile(file));
    hash.update('\0');
  }

  return hash.digest('hex');
}

export async function inspectPatchQueue(patchQueueDir) {
  const absolutePatchQueueDir = path.resolve(patchQueueDir);
  const seriesPath = path.join(absolutePatchQueueDir, 'series');
  if (!existsSync(seriesPath)) {
    return {
      names: [],
      sha256: crypto.createHash('sha256').update('').digest('hex')
    };
  }

  const series = await readFile(seriesPath, 'utf8');
  const names = series.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const hash = crypto.createHash('sha256');

  for (const name of names) {
    hash.update(name);
    hash.update('\0');
    hash.update(await readFile(path.join(absolutePatchQueueDir, name), 'utf8'));
    hash.update('\0');
  }

  return {
    names,
    sha256: hash.digest('hex')
  };
}

export async function hashPatchQueue(patchQueueDir) {
  return (await inspectPatchQueue(patchQueueDir)).sha256;
}

async function listFiles(root) {
  const result = [];
  const entries = await readdir(root);

  for (const entry of entries) {
    const absolutePath = path.join(root, entry);
    const entryStat = await stat(absolutePath);
    if (entryStat.isDirectory()) {
      result.push(...await listFiles(absolutePath));
    } else if (entryStat.isFile()) {
      result.push(absolutePath);
    }
  }

  return result.sort();
}

function toPosixPath(value) {
  return value.split(path.sep).join('/');
}
