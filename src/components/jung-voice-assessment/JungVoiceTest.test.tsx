/**
 * @jest-environment jsdom
 */

import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import JungVoiceTest from './JungVoiceTest';

// モックの作成
jest.mock('hume', () => {
  return {
    HumeClient: jest.fn().mockImplementation(() => {
      return {};
    }),
    Hume: {}
  };
});

// Web Speech API のモック
const mockSpeechRecognition = {
  start: jest.fn(),
  stop: jest.fn(),
  abort: jest.fn(),
  continuous: false,
  interimResults: true,
  lang: 'en-US',
};

Object.defineProperty(global, 'SpeechRecognition', {
  value: jest.fn().mockImplementation(() => mockSpeechRecognition),
  writable: true
});

// fetch のモック
global.fetch = jest.fn().mockImplementation(() =>
  Promise.resolve({
    ok: true,
    json: () => Promise.resolve({
      generations: [{ audio: 'base64encodedaudio' }]
    }),
  })
);

// URL.createObjectURL のモック
URL.createObjectURL = jest.fn().mockReturnValue('blob:test');

// audioのplayメソッドのモック
HTMLMediaElement.prototype.play = jest.fn();

describe('JungVoiceTest コンポーネント', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('重要度: 5 - コンポーネントが正しくレンダリングされること', () => {
    render(<JungVoiceTest apiKey="test-key" />);
    expect(screen.getByText('ユングの言語連想テスト (AIガイド版)')).toBeInTheDocument();
  });

  test('重要度: 4 - テスト開始ボタンが表示されていること', async () => {
    render(<JungVoiceTest apiKey="test-key" />);
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'テスト開始' })).toBeInTheDocument();
    });
  });

  test('重要度: 3 - テスト開始ボタンをクリックするとテストが開始されること', async () => {
    render(<JungVoiceTest apiKey="test-key" numberOfWords={5} />);
    
    // 初期状態の確認
    await waitFor(() => {
      const startButton = screen.getByRole('button', { name: 'テスト開始' });
      expect(startButton).toBeInTheDocument();
      
      // ボタンクリック
      fireEvent.click(startButton);
    });
    
    // テストが開始されたことを確認（最初の単語が表示される）
    await waitFor(() => {
      const progressText = screen.getByText('単語 1 / 5');
      expect(progressText).toBeInTheDocument();
    });
  });

  test('重要度: 4 - APIキーが提供されない場合にエラーが表示されること', async () => {
    render(<JungVoiceTest apiKey="" />);
    
    await waitFor(() => {
      expect(screen.getByText(/API key not provided/i)).toBeInTheDocument();
    });
  });

  test('重要度: 3 - 音声認識ボタンが表示されること', async () => {
    render(<JungVoiceTest apiKey="test-key" />);
    
    // テスト開始
    await waitFor(() => {
      const startButton = screen.getByRole('button', { name: 'テスト開始' });
      fireEvent.click(startButton);
    });
    
    // 音声認識ボタンが表示されることを確認
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '音声で回答' })).toBeInTheDocument();
    });
  });
}); 