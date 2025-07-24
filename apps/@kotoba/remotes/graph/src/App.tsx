import { useState, useEffect, useRef } from 'react'
import './App.css'

/**
 * グラフ表示コンポーネント
 * Module Federationでホストアプリケーションから利用される
 */
function App() {
  const [graphType, setGraphType] = useState<'bar' | 'line' | 'pie'>('bar')
  const [data, setData] = useState([
    { label: 'Jan', value: 65 },
    { label: 'Feb', value: 59 },
    { label: 'Mar', value: 80 },
    { label: 'Apr', value: 81 },
    { label: 'May', value: 56 },
    { label: 'Jun', value: 55 }
  ])
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    console.log('Remote Graph: App component mounted.');
    drawGraph()
  }, [graphType, data])

  const drawGraph = () => {
    const canvas = canvasRef.current
    if (!canvas) return

    const ctx = canvas.getContext('2d')
    if (!ctx) return

    // キャンバスをクリア
    ctx.clearRect(0, 0, canvas.width, canvas.height)

    const width = canvas.width
    const height = canvas.height
    const padding = 40
    const chartWidth = width - 2 * padding
    const chartHeight = height - 2 * padding

    // データの最大値を取得
    const maxValue = Math.max(...data.map(d => d.value))

    // 背景を描画
    ctx.fillStyle = '#f8f9fa'
    ctx.fillRect(0, 0, width, height)

    // グリッドを描画
    ctx.strokeStyle = '#e9ecef'
    ctx.lineWidth = 1
    for (let i = 0; i <= 5; i++) {
      const y = padding + (chartHeight / 5) * i
      ctx.beginPath()
      ctx.moveTo(padding, y)
      ctx.lineTo(width - padding, y)
      ctx.stroke()
    }

    if (graphType === 'bar') {
      drawBarChart(ctx, padding, chartWidth, chartHeight, maxValue)
    } else if (graphType === 'line') {
      drawLineChart(ctx, padding, chartWidth, chartHeight, maxValue)
    } else if (graphType === 'pie') {
      drawPieChart(ctx, width / 2, height / 2, Math.min(chartWidth, chartHeight) / 2)
    }
  }

  const drawBarChart = (ctx: CanvasRenderingContext2D, padding: number, chartWidth: number, chartHeight: number, maxValue: number) => {
    const barWidth = chartWidth / data.length * 0.8
    const barSpacing = chartWidth / data.length * 0.2

    data.forEach((item, index) => {
      const x = padding + index * (barWidth + barSpacing) + barSpacing / 2
      const barHeight = (item.value / maxValue) * chartHeight
      const y = padding + chartHeight - barHeight

      // バーを描画
      ctx.fillStyle = '#007bff'
      ctx.fillRect(x, y, barWidth, barHeight)

      // ラベルを描画
      ctx.fillStyle = '#495057'
      ctx.font = '12px Arial'
      ctx.textAlign = 'center'
      ctx.fillText(item.label, x + barWidth / 2, padding + chartHeight + 20)
      ctx.fillText(item.value.toString(), x + barWidth / 2, y - 5)
    })
  }

  const drawLineChart = (ctx: CanvasRenderingContext2D, padding: number, chartWidth: number, chartHeight: number, maxValue: number) => {
    ctx.strokeStyle = '#007bff'
    ctx.lineWidth = 3
    ctx.beginPath()

    data.forEach((item, index) => {
      const x = padding + (index / (data.length - 1)) * chartWidth
      const y = padding + chartHeight - (item.value / maxValue) * chartHeight

      if (index === 0) {
        ctx.moveTo(x, y)
      } else {
        ctx.lineTo(x, y)
      }
    })

    ctx.stroke()

    // ポイントを描画
    data.forEach((item, index) => {
      const x = padding + (index / (data.length - 1)) * chartWidth
      const y = padding + chartHeight - (item.value / maxValue) * chartHeight

      ctx.fillStyle = '#007bff'
      ctx.beginPath()
      ctx.arc(x, y, 4, 0, 2 * Math.PI)
      ctx.fill()

      // ラベルを描画
      ctx.fillStyle = '#495057'
      ctx.font = '12px Arial'
      ctx.textAlign = 'center'
      ctx.fillText(item.label, x, padding + chartHeight + 20)
    })
  }

  const drawPieChart = (ctx: CanvasRenderingContext2D, centerX: number, centerY: number, radius: number) => {
    const total = data.reduce((sum, item) => sum + item.value, 0)
    const colors = ['#007bff', '#28a745', '#ffc107', '#dc3545', '#6f42c1', '#fd7e14']

    let currentAngle = 0
    data.forEach((item, index) => {
      const sliceAngle = (item.value / total) * 2 * Math.PI

      ctx.fillStyle = colors[index % colors.length]
      ctx.beginPath()
      ctx.moveTo(centerX, centerY)
      ctx.arc(centerX, centerY, radius, currentAngle, currentAngle + sliceAngle)
      ctx.closePath()
      ctx.fill()

      // ラベルを描画
      const labelAngle = currentAngle + sliceAngle / 2
      const labelRadius = radius * 0.7
      const labelX = centerX + Math.cos(labelAngle) * labelRadius
      const labelY = centerY + Math.sin(labelAngle) * labelRadius

      ctx.fillStyle = '#fff'
      ctx.font = '12px Arial'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillText(item.label, labelX, labelY)

      currentAngle += sliceAngle
    })
  }

  const addDataPoint = () => {
    const newLabel = String.fromCharCode(65 + data.length) // A, B, C, ...
    const newValue = Math.floor(Math.random() * 100) + 20
    setData([...data, { label: newLabel, value: newValue }])
  }

  const removeDataPoint = () => {
    if (data.length > 1) {
      setData(data.slice(0, -1))
    }
  }

  return (
    <div className="graph-container">
      <div className="graph-header">
        <h3>Kotoba Graph Viewer</h3>
        <div className="graph-controls">
          <select 
            value={graphType} 
            onChange={(e) => setGraphType(e.target.value as 'bar' | 'line' | 'pie')}
            className="graph-type-select"
          >
            <option value="bar">Bar Chart</option>
            <option value="line">Line Chart</option>
            <option value="pie">Pie Chart</option>
          </select>
          <button onClick={addDataPoint} className="btn btn-primary">
            Add Data
          </button>
          <button onClick={removeDataPoint} className="btn btn-secondary" disabled={data.length <= 1}>
            Remove
          </button>
        </div>
      </div>
      
      <div className="graph-content">
        <canvas
          ref={canvasRef}
          width={400}
          height={300}
          className="graph-canvas"
        />
      </div>
      
      <div className="graph-footer">
        <span className="status-text">
          {graphType} chart • {data.length} data points
        </span>
      </div>
    </div>
  )
}

export default App
