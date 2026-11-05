// Standalone sample: call the Paytm inference gateway. Run: npm run sample -- "your question"
const { loadEnv } = require('../src/config');
loadEnv();

const BASE_URL = process.env.LLM_BASE_URL || 'https://api.inference.paytm.com/v1';
const API_KEY = process.env.LLM_API_KEY;
if (!API_KEY) throw new Error('Set LLM_API_KEY in .env');

// Sends one non-streaming chat request and prints the assistant's reply.
async function main() {
  const res = await fetch(`${BASE_URL}/ai/playground`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${API_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: process.env.LLM_MODEL || 'al-agentic-gpt-oss-120b',
      messages: [
        { role: 'system', content: 'You are a helpful assistant.' },
        { role: 'user', content: process.argv.slice(2).join(' ') || 'Say hello in one line.' },
      ],
      max_tokens: 4096,
      stream: false,
    }),
  });
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  const json = await res.json();
  console.log(json.choices[0].message.content);
}

main().catch((e) => { console.error(e.message); process.exit(1); });
