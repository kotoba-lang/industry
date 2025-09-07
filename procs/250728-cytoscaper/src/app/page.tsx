'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import dynamic from 'next/dynamic';

// Cytoscapeをクライアントサイドでのみ読み込み
const CytoscapeComponent = dynamic(() => import('@/components/CytoscapeVisualization'), {
  ssr: false,
});

export default function Home() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-900 via-indigo-900 to-purple-900">
      <CytoscapeComponent />
    </div>
  );
}
