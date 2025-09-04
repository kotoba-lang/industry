import React from 'react'

interface ContextMenuProps {
  x: number
  y: number
  onAddNode?: () => void
  onAddEdge?: () => void
  onPaste?: () => void
  onSelectAll?: () => void
  onClearSelection?: () => void
  onZoomIn?: () => void
  onZoomOut?: () => void
  onFitToView?: () => void
  onResetView?: () => void
  onClose: () => void
}

export function ContextMenu({
  x,
  y,
  onAddNode,
  onAddEdge,
  onPaste,
  onSelectAll,
  onClearSelection,
  onZoomIn,
  onZoomOut,
  onFitToView,
  onResetView,
  onClose
}: ContextMenuProps) {
  React.useEffect(() => {
    const handleClickOutside = () => onClose()
    document.addEventListener('click', handleClickOutside)
    return () => document.removeEventListener('click', handleClickOutside)
  }, [onClose])

  return (
    <div
      className="context-menu"
      style={{
        left: x,
        top: y
      }}
      onClick={(e) => e.stopPropagation()}
    >
      {onAddNode && (
        <div className="context-menu-item" onClick={onAddNode}>
          Add Node
        </div>
      )}
      
      {onAddEdge && (
        <div className="context-menu-item" onClick={onAddEdge}>
          Add Edge
        </div>
      )}
      
      {(onAddNode || onAddEdge) && <div className="context-menu-separator" />}
      
      {onPaste && (
        <div className="context-menu-item" onClick={onPaste}>
          Paste
        </div>
      )}
      
      {onSelectAll && (
        <div className="context-menu-item" onClick={onSelectAll}>
          Select All
        </div>
      )}
      
      {onClearSelection && (
        <div className="context-menu-item" onClick={onClearSelection}>
          Clear Selection
        </div>
      )}
      
      {(onPaste || onSelectAll || onClearSelection) && <div className="context-menu-separator" />}
      
      {onZoomIn && (
        <div className="context-menu-item" onClick={onZoomIn}>
          Zoom In
        </div>
      )}
      
      {onZoomOut && (
        <div className="context-menu-item" onClick={onZoomOut}>
          Zoom Out
        </div>
      )}
      
      {onFitToView && (
        <div className="context-menu-item" onClick={onFitToView}>
          Fit to View
        </div>
      )}
      
      {onResetView && (
        <div className="context-menu-item" onClick={onResetView}>
          Reset View
        </div>
      )}
    </div>
  )
} 