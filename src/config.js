const fs = require('fs');
const path = require('path');

// Loads KEY=VALUE pairs from .env into process.env (no dependency, never overrides real env vars).
function loadEnv(file = path.join(__dirname, '..', '.env')) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!m || line.trim().startsWith('#')) continue;
    const value = m[2].replace(/^(['"])(.*)\1$/, '$2');
    if (process.env[m[1]] === undefined) process.env[m[1]] = value;
  }
}

// Returns validated runtime config; fails loudly when the API key is missing.
function getConfig() {
  loadEnv();
  const apiKey = process.env.LLM_API_KEY;
  if (!apiKey) throw new Error('LLM_API_KEY is not set (copy .env.example to .env)');
  const baseUrl = (process.env.LLM_BASE_URL || 'https://api.inference.paytm.com/v1').replace(/\/$/, '');
  return {
    apiKey,
    baseUrl,
    model: process.env.LLM_MODEL || 'al-agentic-gpt-oss-120b',
    sttUrl: process.env.STT_URL || `${baseUrl}/audio/transcriptions`,
    sttModel: process.env.STT_MODEL || 'whisper-1',
  };
}

module.exports = { loadEnv, getConfig };
