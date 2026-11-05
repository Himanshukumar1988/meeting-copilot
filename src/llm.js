const { getConfig } = require('./config');

const PROMPTS = {
  reply:
    'You are a discreet real-time meeting copilot. Given the meeting transcript and chat, ' +
    'write the reply the user should say next: 1-3 short, natural, first-person sentences. ' +
    'No preamble, no markdown headings.',
  code:
    'You are a senior software engineer helping live. Solve the coding problem described. ' +
    'Give: 1) one-line approach, 2) complete runnable code in a fenced block, 3) time/space complexity. Be concise.',
  design:
    'You are a staff engineer helping with a live system-design discussion. Give: requirements/assumptions, ' +
    'high-level components, data model, key trade-offs, and scaling/failure points as tight bullets. Be concise.',
};

// Builds the chat messages for a given mode from transcript, chat and an optional explicit question.
function buildMessages({ mode = 'reply', transcript = '', chat = '', question = '' }) {
  const system = PROMPTS[mode] || PROMPTS.reply;
  const parts = [];
  if (chat.trim()) parts.push(`# Meeting chat\n${chat.trim()}`);
  if (transcript.trim()) parts.push(`# Live transcript (most recent last)\n${transcript.trim()}`);
  if (question.trim()) parts.push(`# User request\n${question.trim()}`);
  if (!parts.length) parts.push('# User request\nNo context yet. Say what you need.');
  return [
    { role: 'system', content: system },
    { role: 'user', content: parts.join('\n\n') },
  ];
}

// Calls the Paytm inference gateway and returns the assistant text.
async function complete(input) {
  const cfg = getConfig();
  const res = await fetch(`${cfg.baseUrl}/ai/playground`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${cfg.apiKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: cfg.model,
      messages: buildMessages(input),
      max_tokens: 4096,
      stream: false,
    }),
  });
  if (!res.ok) throw new Error(`LLM ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const json = await res.json();
  return json.choices?.[0]?.message?.content ?? '';
}

// Transcribes an audio buffer via an OpenAI-compatible /audio/transcriptions endpoint.
async function transcribe(buffer, mimeType = 'audio/webm') {
  const cfg = getConfig();
  const form = new FormData();
  form.append('file', new Blob([buffer], { type: mimeType }), 'chunk.webm');
  form.append('model', cfg.sttModel);
  const res = await fetch(cfg.sttUrl, {
    method: 'POST',
    headers: { Authorization: `Bearer ${cfg.apiKey}` },
    body: form,
  });
  if (!res.ok) throw new Error(`STT ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return ((await res.json()).text || '').trim();
}

module.exports = { complete, transcribe, buildMessages };
