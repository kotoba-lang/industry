'use client'

import { useState, useEffect } from 'react'
import { useTheme } from 'next-themes'
import { Button } from '@/components/ui/button'

/**
 * テーマ切り替えボタンコンポーネント
 * ライトモード・ダークモード・システム設定の切り替えを行う
 */
export function ThemeToggle() {
  const { theme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)

  // ハイドレーション後にマウント状態を更新
  useEffect(() => {
    setMounted(true)
  }, [])

  // サーバーサイドレンダリング時は何も表示しない
  if (!mounted) {
    return (
      <Button variant="outline" size="sm" className="w-[60px] h-9">
        {/* プレースホルダー */}
      </Button>
    )
  }

  /**
   * テーマを循環的に切り替える
   * light → dark → system → light の順で切り替わる
   */
  const toggleTheme = () => {
    if (theme === 'light') {
      setTheme('dark')
    } else if (theme === 'dark') {
      setTheme('system')
    } else {
      setTheme('light')
    }
  }

  /**
   * 現在のテーマに対応するアイコンを取得
   */
  const getThemeIcon = () => {
    switch (theme) {
      case 'light':
        return '☀️'
      case 'dark':
        return '🌙'
      case 'system':
        return '🖥️'
      default:
        return '☀️'
    }
  }

  /**
   * 現在のテーマのラベルを取得
   */
  const getThemeLabel = () => {
    switch (theme) {
      case 'light':
        return 'ライト'
      case 'dark':
        return 'ダーク'
      case 'system':
        return 'システム'
      default:
        return 'ライト'
    }
  }

  return (
    <Button
      variant="outline"
      size="sm"
      onClick={toggleTheme}
      className="w-auto px-3 h-9 gap-2"
      title={`現在のテーマ: ${getThemeLabel()}`}
    >
      <span className="text-sm">{getThemeIcon()}</span>
      <span className="text-xs font-medium">{getThemeLabel()}</span>
    </Button>
  )
} 