import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import ModelParamsControl from './ModelParamsControl'
import type { IntegratedModelParams } from './utils/integratedModel'

describe('ModelParamsControl', () => {
  const defaultParams: IntegratedModelParams = {
    alpha: 1.0,
    gamma: 1.0,
    eta: 1.0,
    lambda: 1.0
  }
  
  const mockOnChange = vi.fn()
  
  beforeEach(() => {
    mockOnChange.mockClear()
  })
  
  it('初期状態で正しいパラメータ値が表示されることを確認する', () => {
    const testParams: IntegratedModelParams = {
      alpha: 1.5,
      gamma: 0.8,
      eta: 2.0,
      lambda: 1.2
    }
    
    render(
      <ModelParamsControl params={testParams} onChange={mockOnChange} />
    )
    
    // 各パラメータの値が正しく表示されているか確認
    expect(screen.getByText(/Reaction Speed \(α\): 1.50/i)).toBeInTheDocument()
    expect(screen.getByText(/Skin Potential \(γ\): 0.80/i)).toBeInTheDocument()
    expect(screen.getByText(/Facial Emotion \(η\): 2.00/i)).toBeInTheDocument()
    expect(screen.getByText(/Scale Factor \(λ\): 1.20/i)).toBeInTheDocument()
    
    // 各スライダーの値が正しく設定されているか確認
    const alphaSlider = screen.getByLabelText('Reaction Speed') as HTMLInputElement
    const gammaSlider = screen.getByLabelText('Skin Potential') as HTMLInputElement
    const etaSlider = screen.getByLabelText('Facial Emotion') as HTMLInputElement
    const lambdaSlider = screen.getByLabelText('Scale Factor') as HTMLInputElement
    
    expect(parseFloat(alphaSlider.value)).toBe(1.5)
    expect(parseFloat(gammaSlider.value)).toBe(0.8)
    expect(parseFloat(etaSlider.value)).toBe(2.0)
    expect(parseFloat(lambdaSlider.value)).toBe(1.2)
  })
  
  it('スライダーを動かすと対応するパラメータが更新されることを確認する', () => {
    render(
      <ModelParamsControl params={defaultParams} onChange={mockOnChange} />
    )
    
    // Alphaスライダーを2.0に変更
    const alphaSlider = screen.getByLabelText('Reaction Speed') as HTMLInputElement
    fireEvent.change(alphaSlider, { target: { value: '2.0' } })
    
    // onChangeが呼ばれることを確認
    expect(mockOnChange).toHaveBeenCalledWith({
      ...defaultParams,
      alpha: 2.0
    })
    
    // 他のスライダーも同様にテスト
    const gammaSlider = screen.getByLabelText('Skin Potential') as HTMLInputElement
    fireEvent.change(gammaSlider, { target: { value: '2.5' } })
    
    expect(mockOnChange).toHaveBeenCalledWith({
      ...defaultParams,
      gamma: 2.5
    })
  })
  
  it('パラメータ変更時にonChangeコールバックが正しい値で呼び出されることを確認する', () => {
    render(
      <ModelParamsControl params={defaultParams} onChange={mockOnChange} />
    )
    
    // 複数のパラメータを変更
    const etaSlider = screen.getByLabelText('Facial Emotion') as HTMLInputElement
    fireEvent.change(etaSlider, { target: { value: '1.7' } })
    
    expect(mockOnChange).toHaveBeenCalledWith({
      ...defaultParams,
      eta: 1.7
    })
    
    const lambdaSlider = screen.getByLabelText('Scale Factor') as HTMLInputElement
    fireEvent.change(lambdaSlider, { target: { value: '2.2' } })
    
    expect(mockOnChange).toHaveBeenCalledWith({
      ...defaultParams,
      lambda: 2.2
    })
    
    // 呼び出し回数の確認
    expect(mockOnChange).toHaveBeenCalledTimes(2)
  })
  
  it('プログレスバーが各パラメータの値に応じて正しく表示されることを確認する', () => {
    const testParams: IntegratedModelParams = {
      alpha: 1.5, // 1.5/3 = 0.5 (50%)
      gamma: 0.3, // 0.3/3 = 0.1 (10%)
      eta: 3.0,   // 3.0/3 = 1.0 (100%)
      lambda: 0.9 // 0.9/3 = 0.3 (30%)
    }
    
    render(
      <ModelParamsControl params={testParams} onChange={mockOnChange} />
    )
    
    // プログレスバーを取得
    const progressBars = document.querySelectorAll('.absolute.top-0.left-0.h-full.bg-gray-800')
    
    // Check progress bar widths
    expect(progressBars[0]?.style?.width).toBe('50%') // alpha
    expect(progressBars[1]?.style?.width).toBe('10%') // gamma
    expect(progressBars[2]?.style?.width).toBe('100%') // eta
    expect(progressBars[3]?.style?.width).toBe('30%') // lambda
  })
}) 