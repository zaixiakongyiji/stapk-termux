(function initializeStapkCapabilities() {
  const remoteCapabilityIds = [
    'remote.embeddings',
    'remote.image',
    'remote.tts',
    'remote.stt',
    'remote.caption',
    'remote.translation',
  ];
  const capabilities = Object.create(null);
  window.stapkCapabilities = capabilities;
  window.isStapkCapabilityAvailable = function isStapkCapabilityAvailable(id) {
    return window.stapkCapabilities[id] === true;
  };
  function updateExternalCapabilityNote() {
    if (typeof document === 'undefined') return;
    const update = () => {
      const note = document.getElementById('stapk-external-capabilities-note');
      if (note) {
        note.hidden = remoteCapabilityIds.every(window.isStapkCapabilityAvailable);
      }
    };
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', update, { once: true });
    } else {
      update();
    }
  }
  window.stapkCapabilitiesReady = fetch('/stapk-capabilities.json', { cache: 'no-store' })
    .then((response) => {
      if (!response.ok) throw new Error(`Capability request failed: ${response.status}`);
      return response.json();
    })
    .then((payload) => {
      if (payload?.schemaVersion !== 1 || typeof payload.capabilities !== 'object') {
        throw new Error('Invalid capability runtime');
      }
      for (const [id, available] of Object.entries(payload.capabilities)) {
        capabilities[id] = available === true;
      }
      updateExternalCapabilityNote();
      return capabilities;
    })
    .catch((error) => {
      console.error('Unable to load stAPK capabilities', error);
      updateExternalCapabilityNote();
      return capabilities;
    });
})();
