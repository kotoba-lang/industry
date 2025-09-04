import React from 'react'

interface FloatingToolbarProps {
  x: number
  y: number
  onDelete?: () => void
  onEdit?: () => void
  onDuplicate?: () => void
  onColorChange?: () => void
  onZoomTo?: () => void
  onExpand?: () => void
  type: 'node' | 'edge'
}

export function FloatingToolbar({
  x,
  y,
  onDelete,
  onEdit,
  onDuplicate,
  onColorChange,
  onZoomTo,
  onExpand,
  type
}: FloatingToolbarProps) {
  return (
    <div
      className="floating-toolbar"
      style={{
        left: x + 10,
        top: y - 50,
        transform: 'translateY(-50%)'
      }}
    >
      {onDelete && (
        <button
          onClick={onDelete}
          title="Delete"
          className="hover:bg-red-500/20 hover:text-red-400"
        >
          <svg width="16" height="16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
          </svg>
        </button>
      )}
      
      {onColorChange && (
        <button
          onClick={onColorChange}
          title="Change Color"
          className="hover:bg-blue-500/20 hover:text-blue-400"
        >
          <svg width="16" height="16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 21a4 4 0 01-4-4V5a2 2 0 012-2h4a2 2 0 012 2v12a4 4 0 01-4 4zm0 0h12a2 2 0 002-2v-4a2 2 0 00-2-2h-2.343M11 7.343l1.657-1.657a2 2 0 012.828 0l2.829 2.829a2 2 0 010 2.828l-8.486 8.485M7 17h.01" />
          </svg>
        </button>
      )}
      
      {onZoomTo && (
        <button
          onClick={onZoomTo}
          title="Zoom to Selection"
          className="hover:bg-green-500/20 hover:text-green-400"
        >
          <svg width="16" height="16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0zM10 7v3m0 0v3m0-3h3m-3 0H7" />
          </svg>
        </button>
      )}
      
      {onExpand && (
        <button
          onClick={onExpand}
          title="Expand"
          className="hover:bg-purple-500/20 hover:text-purple-400"
        >
          <svg width="16" height="16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4" />
          </svg>
        </button>
      )}
      
      {onEdit && (
        <button
          onClick={onEdit}
          title="Edit"
          className="hover:bg-yellow-500/20 hover:text-yellow-400"
        >
          <svg width="16" height="16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
          </svg>
        </button>
      )}
      
      {type === 'node' && onDuplicate && (
        <button
          onClick={onDuplicate}
          title="Duplicate"
          className="hover:bg-indigo-500/20 hover:text-indigo-400"
        >
          <svg width="16" height="16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
          </svg>
        </button>
      )}
    </div>
  )
} 