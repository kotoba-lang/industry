import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import Home from './index'
import { generateGraphData } from './utils/generateGraphData'
import { useAnimation } from './hooks/useAnimation'

// Mock the dynamically imported component
vi.mock("next/dynamic", () => ({
  __esModule: true,
  default: () => {
      const PhysicsGraph = ({ data, frameRate, time, isPlaying, speed, selectedElement, setSelectedElement }: { data: any, frameRate: number, time: number, isPlaying: boolean, speed: number, selectedElement: string, setSelectedElement: (element: string) => void }) => (
      <div data-testid="physics-graph">
        <div>Physics Graph Mock</div>
        <div data-testid="graph-data">{JSON.stringify(data?.nodes?.length || 0)}</div>
        <div data-testid="frame-rate">{frameRate}</div>
        <div data-testid="time">{time}</div>
        <div data-testid="is-playing">{isPlaying ? "playing" : "paused"}</div>
        <div data-testid="speed">{speed}</div>
      </div>
    )
    return PhysicsGraph
  }
}))

// Mock the utils and hooks
vi.mock('./utils/generateGraphData', () => ({
  generateGraphData: vi.fn().mockReturnValue({ 
    nodes: Array(50).fill(0).map((_, i) => ({ id: `node-${i}` })),
    links: []
  })
}))

vi.mock('./hooks/useAnimation', () => ({
  useAnimation: vi.fn().mockReturnValue({
    transitionState: { currentState: 'stable', targetState: null, progress: 0 },
    time: 0,
    isPlaying: false,
    speed: 1,
    handleStateChange: vi.fn(),
    togglePlay: vi.fn(),
    handleSpeedChange: vi.fn()
  })
}))

describe('Kawasaki Model', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('コンポーネントが正しくレンダリングされること', () => {
    render(<Home />)
    
    // タイトルが表示されているか確認
    expect(screen.getByText('Spirit in Physics ( Jung\'s Word Association Test Embedding Model )')).toBeInTheDocument()
    
    // 主要なコンポーネントが描画されているか確認
    expect(screen.getByTestId('physics-graph')).toBeInTheDocument()
  })

  it('子コンポーネントが正しく配置されていること', () => {
    render(<Home />)
    
    // PhysicsStateMachine コンポーネントの存在を確認
    expect(screen.getByText(/Association State:/)).toBeInTheDocument()
    
    // ModelParamsControl コンポーネントの存在を確認
    expect(screen.getByText('Integrated Model Parameters')).toBeInTheDocument()
    
    // 説明テキストの存在を確認
    expect(screen.getByText('About the Integrated Model')).toBeInTheDocument()
    
    // グラフコンポーネントが存在することを確認
    expect(screen.getByTestId('physics-graph')).toBeInTheDocument()
  })

  it('モデルパラメータ変更が正しく処理されること', () => {
    render(<Home />)
    
    // generateGraphData が正しく呼ばれたことを確認
    expect(generateGraphData).toHaveBeenCalledWith(
      50,
      { currentState: 'stable', targetState: null, progress: 0 },
      0,
      expect.objectContaining({
        alpha: expect.any(Number),
        gamma: expect.any(Number),
        eta: expect.any(Number),
        lambda: expect.any(Number)
      })
    )
  })

  it('アニメーション状態が適切に管理されること', () => {
    // useAnimation モックを状態遷移でオーバーライド
    const animationHookMock = {
      transitionState: { currentState: 'stable', targetState: 'excited', progress: 0.5 },
      time: 10,
      isPlaying: true,
      speed: 2,
      handleStateChange: vi.fn(),
      togglePlay: vi.fn(),
      handleSpeedChange: vi.fn(),
      resetTime: vi.fn()
    }
    
    vi.mocked(useAnimation).mockReturnValue(animationHookMock)
    
    render(<Home />)
    
    // PhysicsGraph に正しい状態が渡されているか確認
    expect(screen.getByTestId('time').textContent).toBe('10')
    expect(screen.getByTestId('is-playing').textContent).toBe('playing')
    expect(screen.getByTestId('speed').textContent).toBe('2')
    
    // 遷移状態が子コンポーネントに渡されていることを確認
    expect(screen.getByText(/Stimulus Words → Response Categories/)).toBeInTheDocument()
  })

  it('レスポンシブレイアウトのクラスが正しく適用されていること', () => {
    render(<Home />)
    
    // メインコンテナが適切なレスポンシブクラスを持っていることを確認
    const mainElement = screen.getByRole('main')
    expect(mainElement).toHaveClass('flex')
    expect(mainElement).toHaveClass('flex-col')
    
    // グリッドレイアウトが適切なレスポンシブクラスを持っていることを確認
    const gridElement = document.querySelector('.grid.grid-cols-12')
    expect(gridElement).toBeInTheDocument()
    
    // サイドバーが適切なレスポンシブクラスを持っていることを確認
    const sidebarElement = document.querySelector('.col-span-12.md\\:col-span-3')
    expect(sidebarElement).toBeInTheDocument()
    
    // メイングラフエリアが適切なレスポンシブクラスを持っていることを確認
    const graphElement = document.querySelector('.col-span-12.md\\:col-span-9')
    expect(graphElement).toBeInTheDocument()
  })
}) 