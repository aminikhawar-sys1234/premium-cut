const test = require('node:test');
const assert = require('node:assert/strict');
const { speechResponseToUtterances, speechLanguageCode } = require('../speechMap');

test('maps word offsets from a real speech response', () => {
  const utterances = speechResponseToUtterances({
    results: [
      {
        alternatives: [
          {
            transcript: 'hello world',
            confidence: 0.92,
            words: [
              { word: 'hello', startTime: { seconds: 0, nanos: 120000000 }, endTime: { seconds: 0, nanos: 400000000 } },
              { word: 'world', startTime: { seconds: 0, nanos: 450000000 }, endTime: { seconds: 0, nanos: 900000000 } },
            ],
          },
        ],
      },
    ],
  });
  assert.equal(utterances.length, 1);
  assert.equal(utterances[0].text, 'hello world');
  assert.equal(utterances[0].words[0].text, 'hello');
  assert.equal(utterances[0].words[0].startUs, 120000);
  assert.equal(utterances[0].words[1].endUs, 900000);
  assert.equal(utterances[0].startUs, 120000);
  assert.equal(utterances[0].endUs, 900000);
});

test('returns no captions when speech recognition found no words', () => {
  assert.deepEqual(speechResponseToUtterances({ results: [] }), []);
  assert.deepEqual(speechResponseToUtterances({
    results: [{ alternatives: [{ transcript: 'guessed line', words: [] }] }],
  }), []);
});

test('does not invent a caption from an empty alternative', () => {
  const utterances = speechResponseToUtterances({
    results: [{ alternatives: [{ transcript: '   ', words: [] }] }],
  });
  assert.equal(utterances.length, 0);
});

test('maps spoken language tags to speech-to-text codes', () => {
  assert.equal(speechLanguageCode('ur'), 'ur-PK');
  assert.equal(speechLanguageCode('ar'), 'ar-SA');
  assert.equal(speechLanguageCode('en-US'), 'en-US');
  assert.equal(speechLanguageCode(''), 'en-US');
});
