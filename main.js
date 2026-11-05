const { app, BrowserWindow, globalShortcut, ipcMain, clipboard, screen, systemPreferences } = require('electron');
const path = require('path');
const { complete } = require('./src/llm');
const { transcribeLocal } = require('./src/stt-local');

let win;
let clickThrough = false;

// Creates the always-on-top overlay; content protection hides it from screen share/recording.
function createWindow() {
  const { width } = screen.getPrimaryDisplay().workAreaSize;
  win = new BrowserWindow({
    width: 460,
    height: 640,
    x: width - 480,
    y: 40,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    skipTaskbar: true,
    hasShadow: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true },
  });
  win.setContentProtection(true);
  win.setAlwaysOnTop(true, 'screen-saver');
  win.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
}

// Registers global hotkeys so the app never needs focus (focus changes can be visible to others).
function registerShortcuts() {
  const send = (channel) => win && win.webContents.send(channel);
  globalShortcut.register('CommandOrControl+Shift+Space', () => (win.isVisible() ? win.hide() : win.showInactive()));
  globalShortcut.register('CommandOrControl+Shift+C', () => send('hotkey:ingest-chat'));
  globalShortcut.register('CommandOrControl+Shift+Return', () => send('hotkey:suggest'));
  globalShortcut.register('CommandOrControl+Shift+M', () => send('hotkey:toggle-mic'));
  globalShortcut.register('CommandOrControl+Shift+X', () => {
    clickThrough = !clickThrough;
    win.setIgnoreMouseEvents(clickThrough, { forward: true });
    send(clickThrough ? 'hotkey:clickthrough-on' : 'hotkey:clickthrough-off');
  });
}

ipcMain.handle('clipboard:read', () => clipboard.readText());
ipcMain.handle('llm:ask', (_e, input) => complete(input));
ipcMain.handle('stt:transcribe', (_e, samples) => transcribeLocal(samples));

app.whenReady().then(async () => {
  if (process.platform === 'darwin') {
    app.dock.hide();
    await systemPreferences.askForMediaAccess('microphone');
  }
  createWindow();
  registerShortcuts();
});

app.on('will-quit', () => globalShortcut.unregisterAll());
app.on('window-all-closed', () => app.quit());
