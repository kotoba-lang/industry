/**
 * @jest-environment jsdom
 */

import '@testing-library/jest-dom';
import { render, screen, fireEvent, act } from '@testing-library/react';
import JungWordTest from './JungWordTest';

// Mock the Date.now function to control timing for tests
const originalDateNow = Date.now;
let mockDateNow = jest.fn();

beforeEach(() => {
  mockDateNow = jest.fn();
  global.Date.now = mockDateNow;
});

afterEach(() => {
  global.Date.now = originalDateNow;
});

describe('JungWordTest', () => {
  test('初期状態でテスト開始ボタンが表示される', () => {
    render(<JungWordTest numberOfWords={5} />);
    expect(screen.getByText('Begin Test')).toBeInTheDocument();
  });

  test('テスト開始後に最初の単語が表示される', () => {
    // Mock the Date.now to return a fixed value
    mockDateNow.mockReturnValue(1000);
    
    render(<JungWordTest numberOfWords={5} />);
    fireEvent.click(screen.getByText('Begin Test'));
    
    // The first word should be displayed
    expect(screen.getByText('Word 1 of 5')).toBeInTheDocument();
  });

  test('回答が入力されるとNextボタンがクリック可能になる', () => {
    mockDateNow.mockReturnValue(1000);
    
    render(<JungWordTest numberOfWords={5} />);
    fireEvent.click(screen.getByText('Begin Test'));
    
    const nextButton = screen.getByText('Next');
    expect(nextButton).toBeDisabled();
    
    const input = screen.getByPlaceholderText('Type your response...');
    fireEvent.change(input, { target: { value: 'test response' } });
    
    expect(nextButton).not.toBeDisabled();
  });

  test('すべての単語に回答するとテスト完了画面が表示される', () => {
    // Mock onTestComplete callback
    const onTestComplete = jest.fn();
    
    // Setup sequential Date.now values for start time and end times
    mockDateNow
      .mockReturnValueOnce(1000) // Start test
      .mockReturnValueOnce(1000) // First word start
      .mockReturnValueOnce(1500) // First word response
      .mockReturnValueOnce(1500) // Second word start
      .mockReturnValueOnce(2000) // Second word response
      .mockReturnValueOnce(2000) // Third word start
      .mockReturnValueOnce(2500) // Third word response
      .mockReturnValueOnce(2500) // Fourth word start
      .mockReturnValueOnce(3000) // Fourth word response
      .mockReturnValueOnce(3000) // Fifth word start
      .mockReturnValueOnce(3500); // Fifth word response
    
    render(<JungWordTest numberOfWords={5} onTestComplete={onTestComplete} />);
    
    // Start the test
    fireEvent.click(screen.getByText('Begin Test'));
    
    // Complete all 5 words
    for (let i = 0; i < 5; i++) {
      const input = screen.getByPlaceholderText('Type your response...');
      fireEvent.change(input, { target: { value: `response ${i + 1}` } });
      fireEvent.click(screen.getByText('Next'));
    }
    
    // Test should be complete now
    expect(screen.getByText('Test Complete')).toBeInTheDocument();
    expect(onTestComplete).toHaveBeenCalledTimes(1);
    
    // Check that callback received the expected data structure
    const results = onTestComplete.mock.calls[0][0];
    expect(results).toHaveProperty('responses');
    expect(results.responses).toHaveLength(5);
    expect(results).toHaveProperty('averageReactionTimeMs');
    expect(results).toHaveProperty('delayedResponseCount');
    expect(results).toHaveProperty('completedAt');
  });
}); 