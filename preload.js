const { contextBridge, ipcRenderer } = require('electron');

const hotkeys = ['ingest-chat', 'suggest', 'toggle-mic', 'clickthrough-on', 'clickthrough-off'];

// Exposes a minimal, explicit bridge to the renderer (no direct Node access).
contextBridge.exposeInMainWorld('copilot', {
  readClipboard: () => ipcRenderer.invoke('clipboard:read'),
  ask: (input) => ipcRenderer.invoke('llm:ask', input),
  transcribe: (samples) => ipcRenderer.invoke('stt:transcribe', samples),
  onHotkey: (name, cb) => {
    if (hotkeys.includes(name)) ipcRenderer.on(`hotkey:${name}`, () => cb());
  },
});
