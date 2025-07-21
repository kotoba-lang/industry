'use client';

import { useState } from 'react';

interface TrackpadControlsProps {
  onClose?: () => void;
}

export default function TrackpadControls({ onClose }: TrackpadControlsProps) {
  const [isVisible, setIsVisible] = useState(true);

  if (!isVisible) return null;

  const handleClose = () => {
    setIsVisible(false);
    onClose?.();
  };

  return (
    <div className="fixed top-4 right-4 bg-slate-800/95 backdrop-blur-sm text-white p-4 rounded-lg shadow-xl z-50 max-w-sm">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-lg font-semibold text-cyan-400">🖱️ Trackpad操作</h3>
        <button 
          onClick={handleClose}
          className="text-gray-400 hover:text-white transition-colors"
        >
          ✕
        </button>
      </div>
      
      <div className="space-y-3 text-sm">
        <div className="flex items-start gap-3">
          <div className="text-green-400 mt-0.5">🔍</div>
          <div>
            <div className="font-medium">ズーム</div>
            <div className="text-gray-300">⌘ + スクロール / ピンチ</div>
          </div>
        </div>
        
        <div className="flex items-start gap-3">
          <div className="text-blue-400 mt-0.5">🤏</div>
          <div>
            <div className="font-medium">パン移動</div>
            <div className="text-gray-300">2本指スクロール / ドラッグ</div>
          </div>
        </div>
        
        <div className="flex items-start gap-3">
          <div className="text-purple-400 mt-0.5">🎯</div>
          <div>
            <div className="font-medium">全体表示</div>
            <div className="text-gray-300">ダブルタップ</div>
          </div>
        </div>
        
        <div className="flex items-start gap-3">
          <div className="text-orange-400 mt-0.5">👆</div>
          <div>
            <div className="font-medium">ノード選択</div>
            <div className="text-gray-300">シングルタップ</div>
          </div>
        </div>
        
        <div className="flex items-start gap-3">
          <div className="text-yellow-400 mt-0.5">✨</div>
          <div>
            <div className="font-medium">慣性スクロール</div>
            <div className="text-gray-300">Natural Scrolling対応</div>
          </div>
        </div>
      </div>
      
      <div className="mt-4 pt-3 border-t border-gray-600">
        <div className="text-xs text-gray-400">
          💡 Magic TrackpadとMagic Mouseの両方に最適化済み
        </div>
      </div>
    </div>
  );
} 