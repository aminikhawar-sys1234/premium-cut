const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { speechLanguageCode, speechResponseToUtterances } = require('./speechMap');

const AUDIO_BUCKET = 'gen-lang-client-0291066258.firebasestorage.app';

function assertOwnedPath(uid, storagePath) {
  const prefix = `captions-audio/${uid}/`;
  if (typeof storagePath !== 'string' || !storagePath.startsWith(prefix) || storagePath.includes('..')) {
    throw new HttpsError('permission-denied', 'Audio path is not owned by the signed-in user.');
  }
}

async function recognizeStorageAudio(storagePath, language, durationMs) {
  const admin = require('firebase-admin');
  if (admin.apps.length === 0) admin.initializeApp();
  const bucket = admin.storage().bucket(AUDIO_BUCKET);
  const file = bucket.file(storagePath);
  const [exists] = await file.exists();
  if (!exists) {
    throw new HttpsError('not-found', 'Uploaded audio was not found in Cloud Storage.');
  }

  const speech = require('@google-cloud/speech');
  const client = new speech.SpeechClient();
  const request = {
    audio: { uri: `gs://${AUDIO_BUCKET}/${storagePath}` },
    config: {
      encoding: 'LINEAR16',
      sampleRateHertz: 16000,
      audioChannelCount: 1,
      languageCode: speechLanguageCode(language),
      enableWordTimeOffsets: true,
      enableAutomaticPunctuation: true,
    },
  };

  let response;
  if (Number(durationMs) > 55_000) {
    const [operation] = await client.longRunningRecognize(request);
    [response] = await operation.promise();
  } else {
    [response] = await client.recognize(request);
  }
  return speechResponseToUtterances(response);
}

const transcribeCaptions = onCall(
  { region: 'us-central1', timeoutSeconds: 540, memory: '1GiB' },
  async (request) => {
    if (!request.auth || !request.auth.uid) {
      throw new HttpsError('unauthenticated', 'Sign in before generating captions.');
    }
    const storagePath = request.data && request.data.storagePath;
    const language = request.data && request.data.language;
    const durationMs = request.data && request.data.durationMs;
    assertOwnedPath(request.auth.uid, storagePath);
    try {
      const utterances = await recognizeStorageAudio(storagePath, language, durationMs);
      return {
        providerId: 'firebase-speech',
        language: speechLanguageCode(language),
        utterances,
      };
    } catch (error) {
      if (error instanceof HttpsError) throw error;
      const message = error && error.message ? error.message : String(error);
      throw new HttpsError('failed-precondition', `Speech recognition failed: ${message}`);
    }
  },
);

module.exports = { transcribeCaptions, recognizeStorageAudio, assertOwnedPath };
