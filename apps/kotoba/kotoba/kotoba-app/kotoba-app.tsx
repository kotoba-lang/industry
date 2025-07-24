import React from 'react';
import { Host } from '@junkawasaki/kotoba.components.host';
import { Editor } from '@junkawasaki/kotoba.components.editor';
import { Graph } from '@junkawasaki/kotoba.components.graph';

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
