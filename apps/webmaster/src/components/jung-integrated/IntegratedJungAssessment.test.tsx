import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, expect, vi } from 'vitest';
import IntegratedJungAssessment from './IntegratedJungAssessment';
import JungWordTest from '@/components/jung-word-assessment/JungWordTest';
import JungVoiceTest from '@/components/jung-voice-assessment/JungVoiceTest';
import { type TestResults as WordTestResults } from '@/components/jung-word-assessment/types';
import { type TestResults as VoiceTestResults } from '@/components/jung-voice-assessment/types';

// Mock the dynamic import components
vi.mock('next/dynamic', () => ({
  __esModule: true,
  default: (func: () => any) => {
    const Component = func();
    Component.displayName = 'DynamicComponent';
    return Component;
  }
}));

// Mock the sub-components
vi.mock('@/components/jung-word-assessment/JungWordTest', () => ({
  __esModule: true,
  default: vi.fn((props) => (
    <div data-testid="mock-word-test">
      Mock Word Test
      <button onClick={() => props.onComplete({
        responses: [
          { stimulus: 'water', response: 'ocean', reactionTimeMs: 800, isDelayed: false },
          { stimulus: 'fire', response: 'hot', reactionTimeMs: 600, isDelayed: false }
        ],
        averageReactionTimeMs: 700,
        delayedResponseCount: 0,
        completedAt: new Date()
      })}>
        Complete Word Test
      </button>
    </div>
  ))
}));

vi.mock('@/components/jung-voice-assessment/JungVoiceTest', () => ({
  __esModule: true,
  default: vi.fn((props) => (
    <div data-testid="mock-voice-test">
      Mock Voice Test
      <button onClick={() => props.onComplete({
        responses: [
          { stimulusWord: 'water', responseWord: 'ocean', reactionTimeMs: 1000 },
          { stimulusWord: 'fire', responseWord: 'hot', reactionTimeMs: 800 }
        ],
        averageReactionTimeMs: 900,
        delayedResponsesCount: 0
      })}>
        Complete Voice Test
      </button>
    </div>
  ))
}));

vi.mock('@/components/jung-word-assessment/ResultAnalysis', () => ({
  __esModule: true,
  default: vi.fn(() => <div data-testid="mock-result-analysis">Mock Result Analysis</div>)
}));

vi.mock('@/components/kawasaki-model/ModelParamsControl', () => ({
  __esModule: true,
  default: vi.fn(() => <div data-testid="mock-model-params">Mock Model Params</div>)
}));

vi.mock('@/components/kawasaki-model/PhysicsStateMachine', () => ({
  __esModule: true,
  default: vi.fn(() => <div data-testid="mock-state-machine">Mock State Machine</div>)
}));

vi.mock('@/components/kawasaki-model/PhysicsGraph', () => ({
  __esModule: true,
  default: vi.fn(() => <div data-testid="mock-physics-graph">Mock Physics Graph</div>)
}));

vi.mock('@/components/kawasaki-model/hooks/useAnimation', () => ({
  useAnimation: () => ({
    transitionState: { currentState: 'stable', targetState: null, progress: 0, transitionDuration: 1000 },
    time: 0,
    isPlaying: false,
    speed: 1,
    handleStateChange: vi.fn(),
    togglePlay: vi.fn(),
    handleSpeedChange: vi.fn()
  })
}));

vi.mock('./utils/generateGraphDataFromAssessment', () => ({
  generateGraphDataFromWordAssessment: vi.fn(() => ({ nodes: [], links: [] }))
}));

vi.mock('./utils/generateGraphDataFromVoiceAssessment', () => ({
  generateGraphDataFromVoiceAssessment: vi.fn(() => ({ nodes: [], links: [] }))
}));

vi.mock('./utils/mergeGraphData', () => ({
  mergeGraphData: vi.fn((a, b) => ({ nodes: [...a.nodes, ...b.nodes], links: [...a.links, ...b.links] }))
}));

/**
 * 重要度: 5
 * このコンポーネントはJungの言語連想テストとボイステストを統合し、
 * 視覚化する中核機能を担っています。正確なフロー制御とデータ処理が必須です。
 */
describe('IntegratedJungAssessment', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });
  
  it('初期状態ではテスト選択画面が表示されること', () => {
    render(<IntegratedJungAssessment />);
    
    // Test selection should be visible
    expect(screen.getByText(/choose your assessment type/i)).toBeInTheDocument();
    expect(screen.getByText(/word association test/i)).toBeInTheDocument();
    expect(screen.getByText(/voice association test/i)).toBeInTheDocument();
    expect(screen.getByText(/both tests/i)).toBeInTheDocument();
  });
  
  it('ワードテストを選択すると適切なコンポーネントが表示されること', () => {
    render(<IntegratedJungAssessment />);
    
    // Select word test
    fireEvent.click(screen.getByText(/word association test/i));
    
    // Word test should be visible, voice test should not
    expect(screen.getByTestId('mock-word-test')).toBeInTheDocument();
    expect(screen.queryByTestId('mock-voice-test')).not.toBeInTheDocument();
  });
  
  it('ボイステストを選択すると適切なコンポーネントが表示されること', () => {
    render(<IntegratedJungAssessment />);
    
    // Select voice test
    fireEvent.click(screen.getByText(/voice association test/i));
    
    // Voice test should be visible, word test should not
    expect(screen.getByTestId('mock-voice-test')).toBeInTheDocument();
    expect(screen.queryByTestId('mock-word-test')).not.toBeInTheDocument();
  });
  
  it('両方のテストを選択すると順番に実行されること', async () => {
    render(<IntegratedJungAssessment />);
    
    // Select both tests
    fireEvent.click(screen.getByText(/both tests/i));
    
    // Word test should appear first
    expect(screen.getByTestId('mock-word-test')).toBeInTheDocument();
    
    // Complete word test
    fireEvent.click(screen.getByText('Complete Word Test'));
    
    // Wait for voice test to appear (after animation delay)
    await waitFor(() => {
      expect(screen.getByTestId('mock-voice-test')).toBeInTheDocument();
    });
    
    // Complete voice test
    fireEvent.click(screen.getByText('Complete Voice Test'));
    
    // Results analysis should appear
    await waitFor(() => {
      expect(screen.getByTestId('mock-result-analysis')).toBeInTheDocument();
    });
  });
  
  it('結果分析からモデル表示に切り替えられること', async () => {
    // Provide initial test results
    const mockWordResults: WordTestResults = {
      responses: [
        { stimulus: 'water', response: 'ocean', reactionTimeMs: 800, isDelayed: false }
      ],
      averageReactionTimeMs: 800,
      delayedResponseCount: 0,
      completedAt: new Date()
    };
    
    render(<IntegratedJungAssessment wordTestResults={mockWordResults} />);
    
    // Results analysis should be shown by default when results are provided
    expect(screen.getByTestId('mock-result-analysis')).toBeInTheDocument();
    
    // Click to show model
    fireEvent.click(screen.getByText(/view physics model/i));
    
    // Physics model should appear
    await waitFor(() => {
      expect(screen.getByTestId('mock-physics-graph')).toBeInTheDocument();
      expect(screen.getByTestId('mock-model-params')).toBeInTheDocument();
    });
  });
  
  it('外部から提供されたテスト結果が使用されること', () => {
    const mockWordResults: WordTestResults = {
      responses: [
        { stimulus: 'test', response: 'result', reactionTimeMs: 1000, isDelayed: false }
      ],
      averageReactionTimeMs: 1000,
      delayedResponseCount: 0,
      completedAt: new Date()
    };
    
    const mockVoiceResults: VoiceTestResults = {
      responses: [
        { stimulusWord: 'water', responseWord: 'drink', reactionTimeMs: 900 },
        { stimulusWord: 'fire', responseWord: 'hot', reactionTimeMs: 1100 }
      ],
      averageReactionTimeMs: 1000,
      delayedResponsesCount: 0,
      totalWords: 2
    };
    
    const mockSetWordResults = vi.fn();
    const mockSetVoiceResults = vi.fn();
    
    render(
      <IntegratedJungAssessment 
        wordTestResults={mockWordResults}
        voiceTestResults={mockVoiceResults}
        setWordTestResults={mockSetWordResults}
        setVoiceTestResults={mockSetVoiceResults}
      />
    );
    
    // Should directly show analysis with both results
    expect(screen.getByTestId('mock-result-analysis')).toBeInTheDocument();
    expect(screen.queryByTestId('mock-word-test')).not.toBeInTheDocument();
    expect(screen.queryByTestId('mock-voice-test')).not.toBeInTheDocument();
  });
  
  it('テストの再実施が可能なこと', async () => {
    const mockWordResults: WordTestResults = {
      responses: [
        { stimulus: 'water', response: 'ocean', reactionTimeMs: 800, isDelayed: false }
      ],
      averageReactionTimeMs: 800,
      delayedResponseCount: 0,
      completedAt: new Date()
    };
    
    const mockSetWordResults = vi.fn();
    
    render(
      <IntegratedJungAssessment 
        wordTestResults={mockWordResults}
        setWordTestResults={mockSetWordResults}
      />
    );
    
    // Results should be visible initially
    expect(screen.getByTestId('mock-result-analysis')).toBeInTheDocument();
    
    // Click retake button
    fireEvent.click(screen.getByText(/retake tests/i));
    
    // Wait for test selection to appear
    await waitFor(() => {
      expect(screen.getByText(/choose your assessment type/i)).toBeInTheDocument();
    });
    
    // External state setter should have been called with null to reset results
    expect(mockSetWordResults).toHaveBeenCalledWith(null);
  });
}); 