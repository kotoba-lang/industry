import fs from 'fs';
import path from 'path';
import OpenAI from 'openai';
import { JUNG_STIMULUS_WORDS, JUNG_TEST_WELCOME_MESSAGE } from '../src/components/jung-voice-assessment/constants';
import 'dotenv/config';

/**
 * @todo OpenAI APIキーが設定されていない場合のエラーハンドリングを強化する。
 * @todo 音声生成のモデルやボイスを選択できるように引数で指定可能にする。
 */

const outputDir = path.resolve(__dirname, '../public/audio/jung-voice-assessment');

/**
 * 指定されたテキストを音声に変換し、ファイルとして保存します。
 * @param openai OpenAI client instance
 * @param text 音声に変換するテキスト。
 * @param fileName 保存するファイル名（拡張子なし）。
 */
async function textToSpeech(openai: OpenAI, text: string, fileName:string) {
  if (!fs.existsSync(outputDir)) {
    fs.mkdirSync(outputDir, { recursive: true });
  }

  const filePath = path.join(outputDir, `${fileName}.mp3`);

  if (fs.existsSync(filePath)) {
    console.log(`Skipping: ${fileName}.mp3 already exists.`);
    return;
  }

  console.log(`Generating audio for: ${text.substring(0, 20)}...`);

  try {
    const mp3 = await openai.audio.speech.create({
      model: 'gpt-4o-mini-tts',
      voice: 'alloy',
      input: text,
    });

    const buffer = Buffer.from(await mp3.arrayBuffer());
    await fs.promises.writeFile(filePath, buffer);
    console.log(`Successfully created: ${fileName}.mp3`);
  } catch (error) {
    console.error(`Error generating audio for "${text.substring(0, 20)}...":`, error);
  }
}

/**
 * メイン処理。定数で定義された全ての単語とウェルカムメッセージの音声を生成します。
 */
async function main() {
  const apiKey = process.env.OPENAI_API_KEY || process.env.OPENAI_API;
  if (!apiKey) {
    console.error('Error: OPENAI_API_KEY or OPENAI_API is not set in your environment variables.');
    console.error('Please ensure the variable is set in your .env or .envrc file and is loaded correctly.');
    process.exit(1);
  }
  
  const openai = new OpenAI({
    apiKey,
  });

  // ウェルカムメッセージの音声を生成
  await textToSpeech(openai, JUNG_TEST_WELCOME_MESSAGE.trim(), 'welcome_message');

  // 各単語の音声を生成
  for (const key of Object.keys(JUNG_STIMULUS_WORDS)) {
    const word = JUNG_STIMULUS_WORDS[key as unknown as keyof typeof JUNG_STIMULUS_WORDS];
    await textToSpeech(openai, word.pronunciation, key);
    // APIのリクエスト制限を避けるために短い待機時間を設ける
    await new Promise(resolve => setTimeout(resolve, 200));
  }

  console.log('All audio files generation process completed.');
}

main().catch(console.error); 