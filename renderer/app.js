const $ = (id) => document.getElementById(id);
const state = { transcript: [], chat: [], recording: false, stream: null, sttFailed: false };
const CHUNK_MS = 6000;
const MAX_LINES = 40;

// Sets the small status line at the bottom of the panel.
function setStatus(msg) { $('status').textContent = msg; }

// Escapes HTML so model output can never inject markup.
function esc(s) { return s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])); }

// Renders text with ``` fenced blocks as <pre>, everything else escaped.
function renderAnswer(text) {
  $('answer').innerHTML = text.split(/```(?:\w+)?\n?/).map((p, i) => (i % 2 ? `<pre>${esc(p)}</pre>` : esc(p))).join('');
}

// Refreshes the transcript and chat boxes from state.
function refresh() {
  $('transcript').textContent = state.transcript.slice(-8).join('\n');
  $('chat').textContent = state.chat.slice(-6).join('\n');
  $('transcript').scrollTop = $('transcript').scrollHeight;
}

// Sends current context to the LLM and shows the suggestion.
async function suggest() {
  setStatus('Thinking…');
  try {
    const text = await window.copilot.ask({
      mode: $('mode').value,
      transcript: state.transcript.slice(-MAX_LINES).join('\n'),
      chat: state.chat.slice(-MAX_LINES).join('\n'),
      question: $('question').value,
    });
    renderAnswer(text || '(empty response)');
    setStatus('Done.');
  } catch (e) {
    renderAnswer(`Error: ${e.message}`);
    setStatus('LLM call failed.');
  }
}

// Adds the clipboard text (copied meeting chat) to the chat context.
async function ingestChat() {
  const text = (await window.copilot.readClipboard()).trim();
  if (!text) return setStatus('Clipboard is empty.');
  state.chat.push(text);
  refresh();
  setStatus('Chat added from clipboard.');
}

// Decodes an audio blob to 16 kHz mono Float32 samples (what Whisper expects).
async function toSamples(blob) {
  const ctx = new AudioContext({ sampleRate: 16000 });
  try {
    return (await ctx.decodeAudioData(await blob.arrayBuffer())).getChannelData(0);
  } finally {
    ctx.close();
  }
}

// Records one CHUNK_MS audio segment, transcribes it, and loops while recording.
function recordLoop() {
  if (!state.recording) return;
  const rec = new MediaRecorder(state.stream, { mimeType: 'audio/webm' });
  const parts = [];
  rec.ondataavailable = (e) => parts.push(e.data);
  rec.onstop = async () => {
    if (state.recording) recordLoop();
    const blob = new Blob(parts, { type: 'audio/webm' });
    if (blob.size < 2000) return;
    try {
      const text = await window.copilot.transcribe(await toSamples(blob));
      if (text) { state.transcript.push(text); refresh(); }
    } catch (e) {
      if (!state.sttFailed) { state.sttFailed = true; setStatus(`Voice-to-text failed: ${e.message}`); }
    }
  };
  rec.start();
  setTimeout(() => rec.state !== 'inactive' && rec.stop(), CHUNK_MS);
}

// Toggles microphone capture on/off.
async function toggleMic() {
  if (state.recording) {
    state.recording = false;
    state.stream.getTracks().forEach((t) => t.stop());
    $('mic').textContent = 'Mic: off';
    $('mic').classList.remove('on');
    return;
  }
  try {
    state.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    state.recording = true;
    state.sttFailed = false;
    $('mic').textContent = 'Mic: on';
    $('mic').classList.add('on');
    recordLoop();
  } catch (e) {
    setStatus(`Mic unavailable: ${e.message}`);
  }
}

$('go').onclick = suggest;
$('mic').onclick = toggleMic;
$('chatBtn').onclick = ingestChat;
$('question').addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) suggest(); });
window.copilot.onHotkey('suggest', suggest);
window.copilot.onHotkey('ingest-chat', ingestChat);
window.copilot.onHotkey('toggle-mic', toggleMic);
window.copilot.onHotkey('clickthrough-on', () => setStatus('Click-through ON (⌘⇧X to interact)'));
window.copilot.onHotkey('clickthrough-off', () => setStatus('Click-through OFF'));
