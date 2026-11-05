const path = require('path');

const MODEL = process.env.STT_LOCAL_MODEL || 'Xenova/whisper-base.en';
let pipePromise;

// Lazily loads the on-device Whisper pipeline (downloads the model once into .models/).
function getPipeline() {
  if (!pipePromise) {
    pipePromise = import('@huggingface/transformers').then(({ pipeline, env }) => {
      env.cacheDir = path.join(__dirname, '..', '.models');
      return pipeline('automatic-speech-recognition', MODEL);
    });
  }
  return pipePromise;
}

// Transcribes 16 kHz mono Float32 samples locally; returns trimmed text ('' for silence).
async function transcribeLocal(samples) {
  const asr = await getPipeline();
  const out = await asr(samples, { chunk_length_s: 30 });
  const text = (out.text || '').trim();
  // Whisper emits these tags for silence/noise; they are not speech.
  return /^\[.*\]$|^\(.*\)$/.test(text) ? '' : text;
}

module.exports = { transcribeLocal };
