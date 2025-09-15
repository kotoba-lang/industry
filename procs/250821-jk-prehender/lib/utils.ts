import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * テキストから最初の1行目を抽出してタイトルとして使用
 */
export function extractTitleFromContent(content: string): string {
  if (!content.trim()) return "Untitled"
  
  const firstLine = content.split('\n')[0].trim()
  if (!firstLine) return "Untitled"
  
  // マークダウンの見出し記号を除去
  const cleanTitle = firstLine.replace(/^#+\s*/, '').trim()
  
  // 長すぎる場合は切り詰める
  return cleanTitle.length > 50 ? cleanTitle.substring(0, 50) + '...' : cleanTitle
}
