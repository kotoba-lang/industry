import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import PhysicsStateMachine from './PhysicsStateMachine'
import type { TransitionState } from './utils/stateTransition'

describe('PhysicsStateMachine', () => {
  const mockOnStateChange = vi.fn()
  
  beforeEach(() => {
    mockOnStateChange.mockClear()
  })
  
  it('正しい状態名が表示されていることを確認する', () => {
    // Stable state
    const stableState: TransitionState = {
      currentState: 'stable',
      targetState: null,
      progress: 0
    }
    
    const { rerender } = render(
      <PhysicsStateMachine transitionState={stableState} onStateChange={mockOnStateChange} />
    )
    
    expect(screen.getByText(/Stimulus Words/i)).toBeInTheDocument()
    
    // Excited state
    const excitedState: TransitionState = {
      currentState: 'excited',
      targetState: null,
      progress: 0
    }
    
    rerender(
      <PhysicsStateMachine transitionState={excitedState} onStateChange={mockOnStateChange} />
    )
    
    expect(screen.getByText(/Response Categories/i)).toBeInTheDocument()
    
    // Decaying state
    const decayingState: TransitionState = {
      currentState: 'decaying',
      targetState: null,
      progress: 0
    }
    
    rerender(
      <PhysicsStateMachine transitionState={decayingState} onStateChange={mockOnStateChange} />
    )
    
    expect(screen.getByText(/Complex Indicators/i)).toBeInTheDocument()
  })
  
  it('遷移中の進行状況バーが正しく表示されることを確認する', () => {
    // 遷移中の状態
    const transitioningState: TransitionState = {
      currentState: 'stable',
      targetState: 'excited',
      progress: 0.5
    }
    
    render(
      <PhysicsStateMachine transitionState={transitioningState} onStateChange={mockOnStateChange} />
    )
    
    // 進行状況のテキストが表示されていることを確認
    expect(screen.getByText(/Stimulus Words → Response Categories \(50%\)/i)).toBeInTheDocument()
    
    // プログレスバーが存在することを確認
    const progressBar = document.querySelector('.h-full.bg-gray-600')
    expect(progressBar).toBeInTheDocument()
    
    // プログレスバーの幅が正しいことを確認
    expect(progressBar?.style?.width).toBe('50%')
  })
  
  it('現在の状態に基づいて正しいボタンが無効化されることを確認する', () => {
    // Stable state
    const stableState: TransitionState = {
      currentState: 'stable',
      targetState: null,
      progress: 0
    }
    
    const { rerender } = render(
      <PhysicsStateMachine transitionState={stableState} onStateChange={mockOnStateChange} />
    )
    
    // Stable状態では "Show Stimuli" ボタンが無効
    expect(screen.getByText('Show Stimuli')).toBeDisabled()
    expect(screen.getByText('Show Responses')).not.toBeDisabled()
    expect(screen.getByText('Show Complexes')).not.toBeDisabled()
    
    // Excited state
    const excitedState: TransitionState = {
      currentState: 'excited',
      targetState: null,
      progress: 0
    }
    
    rerender(
      <PhysicsStateMachine transitionState={excitedState} onStateChange={mockOnStateChange} />
    )
    
    // Excited状態では "Show Responses" ボタンが無効
    expect(screen.getByText('Show Stimuli')).not.toBeDisabled()
    expect(screen.getByText('Show Responses')).toBeDisabled()
    expect(screen.getByText('Show Complexes')).not.toBeDisabled()
  })
  
  it('ボタンクリック時に正しいアクションが発火されることを確認する', () => {
    const stableState: TransitionState = {
      currentState: 'stable',
      targetState: null,
      progress: 0
    }
    
    render(
      <PhysicsStateMachine transitionState={stableState} onStateChange={mockOnStateChange} />
    )
    
    // "Show Responses" ボタンをクリック
    fireEvent.click(screen.getByText('Show Responses'))
    expect(mockOnStateChange).toHaveBeenCalledWith('excite')
    
    // "Show Complexes" ボタンをクリック
    fireEvent.click(screen.getByText('Show Complexes'))
    expect(mockOnStateChange).toHaveBeenCalledWith('decay')
  })
  
  it('状態遷移中に全てのボタンが無効化されることを確認する', () => {
    // 遷移中の状態
    const transitioningState: TransitionState = {
      currentState: 'stable',
      targetState: 'excited',
      progress: 0.5
    }
    
    render(
      <PhysicsStateMachine transitionState={transitioningState} onStateChange={mockOnStateChange} />
    )
    
    // 全てのボタンが無効化されていることを確認
    expect(screen.getByText('Show Stimuli')).toBeDisabled()
    expect(screen.getByText('Show Responses')).toBeDisabled()
    expect(screen.getByText('Show Complexes')).toBeDisabled()
  })
}) 