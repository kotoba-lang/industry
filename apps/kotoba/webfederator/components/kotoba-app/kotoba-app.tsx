import React from 'react';
import { Host } from '../host/host';
import { Editor } from '../editor/editor';
import { Graph } from '../graph/graph';

export type KotobaAppProps = {
  /**
   * アプリケーションタイトル
   */
  title?: string;
  /**
   * レイアウトタイプ
   */
  layout?: 'grid' | 'vertical' | 'horizontal';
};

export function KotobaApp({ 
  title = "Kotoba Platform",
  layout = "grid"
}: KotobaAppProps) {
  return (
    <Host
      title={title}
      layout={layout}
      EditorComponent={Editor}
      GraphComponent={Graph}
      isDevelopment={false}
    />
  );
} 