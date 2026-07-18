function bridgeFor(environment) {
  const window = environment?.window;
  const bridge = window?.StapkFiles;
  const nonce = window?.stapkBridgeNonce;
  if (!bridge || typeof bridge.saveExport !== 'function' || typeof nonce !== 'string' || !nonce) {
    return null;
  }
  return { bridge, nonce };
}

export function requestStapkResponseExport(response, fileName, mimeType, environment = globalThis) {
  const target = bridgeFor(environment);
  const token = response?.headers?.get?.('X-stAPK-Export-Token');
  if (!target || typeof token !== 'string' || !token) return false;
  target.bridge.saveExport(target.nonce, token, fileName, mimeType);
  return true;
}

export async function stageStapkGeneratedExport(content, fileName, mimeType, environment = globalThis) {
  const target = bridgeFor(environment);
  if (!target) return false;

  const body = new environment.FormData();
  const file = content instanceof environment.Blob
    ? content
    : new environment.Blob([content], { type: mimeType });
  body.append('file', file, fileName);
  const response = await environment.fetch('/api/stapk/exports/create', {
    method: 'POST',
    body,
    headers: { 'X-stAPK-Bridge-Nonce': target.nonce },
  });
  if (!response.ok) throw new Error(`Unable to stage export: ${response.status}`);
  const ticket = await response.json();
  target.bridge.saveExport(target.nonce, ticket.token, ticket.fileName, ticket.mimeType);
  return true;
}

export function requestStapkGeneratedExport(content, fileName, mimeType, environment = globalThis) {
  if (!bridgeFor(environment)) return false;
  void stageStapkGeneratedExport(content, fileName, mimeType, environment).catch((error) => {
    environment.console?.error?.('Unable to export through Android SAF', error);
    environment.toastr?.error?.('Unable to save export.');
  });
  return true;
}
