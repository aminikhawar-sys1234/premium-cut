/**
 * Maps a Cloud Speech-to-Text response into timed utterances.
 * Utterances without measured word times are dropped so the app never invents caption text or timings.
 */
function toMicros(time) {
  if (time == null) return 0;
  if (typeof time === 'number') return Math.round(time * 1_000_000);
  if (typeof time === 'string') {
    const asNumber = Number(time);
    return Number.isFinite(asNumber) ? Math.round(asNumber * 1_000_000) : 0;
  }
  const secondsRaw = time.seconds;
  const seconds = secondsRaw != null && typeof secondsRaw.toNumber === 'function'
    ? secondsRaw.toNumber()
    : Number(secondsRaw || 0);
  const nanos = Number(time.nanos || 0);
  if (!Number.isFinite(seconds) || !Number.isFinite(nanos)) return 0;
  return Math.round(seconds * 1_000_000 + nanos / 1000);
}

function speechResponseToUtterances(response) {
  const results = Array.isArray(response && response.results) ? response.results : [];
  const utterances = [];
  for (const result of results) {
    const alternative = result && result.alternatives && result.alternatives[0];
    if (!alternative) continue;
    const text = String(alternative.transcript || '').trim();
    if (!text) continue;
    const words = (Array.isArray(alternative.words) ? alternative.words : [])
      .map((word) => {
        const token = String(word && word.word || '').trim();
        const startUs = toMicros(word && word.startTime);
        const endUs = Math.max(toMicros(word && word.endTime), startUs);
        return {
          text: token,
          startUs,
          endUs,
          confidence: Number(alternative.confidence || 0),
        };
      })
      .filter((word) => word.text.length > 0 && word.endUs >= word.startUs);
    if (words.length === 0) continue;
    utterances.push({
      text,
      startUs: words[0].startUs,
      endUs: words[words.length - 1].endUs,
      words,
    });
  }
  return utterances;
}

const SPEECH_LANGUAGE = {
  en: 'en-US',
  ur: 'ur-PK',
  ar: 'ar-SA',
  hi: 'hi-IN',
  es: 'es-ES',
  fr: 'fr-FR',
  de: 'de-DE',
  zh: 'cmn-Hans-CN',
  ja: 'ja-JP',
  ko: 'ko-KR',
  pt: 'pt-BR',
  it: 'it-IT',
};

function speechLanguageCode(tag) {
  const base = String(tag || 'en').toLowerCase().split('-')[0];
  return SPEECH_LANGUAGE[base] || 'en-US';
}

module.exports = { speechResponseToUtterances, speechLanguageCode, toMicros };
