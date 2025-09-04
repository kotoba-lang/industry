/**
 * AST処理機能
 * テキストからAST（抽象構文木）を生成し、グラフデータに変換する機能
 */

export interface ASTNode {
  id: string;
  label: string;
  type: string;
  group: string;
  level: number;
}

export interface ASTEdge {
  source: string;
  target: string;
  weight: number;
}

export interface ASTData {
  nodes: ASTNode[];
  edges: ASTEdge[];
}

/**
 * テキストからASTを生成するクラス
 */
export class ASTProcessor {
  /**
   * テキストを解析してASTデータを生成
   * @param text 入力テキスト
   * @returns ASTデータ
   */
  static processText(text: string): ASTData {
    const lines = text.split('\n');
    const nodes: ASTNode[] = [];
    const edges: ASTEdge[] = [];
    
    let nodeId = 1;
    
    lines.forEach((line, lineIndex) => {
      const trimmedLine = line.trim();
      if (!trimmedLine) return;
      
      // 行のレベルを判定（インデントやマークダウン記法から）
      const level = this.getLineLevel(line);
      const type = this.getLineType(trimmedLine);
      const label = this.extractLabel(trimmedLine);
      
      const node: ASTNode = {
        id: `node_${nodeId}`,
        label,
        type,
        group: this.getGroupByLevel(level),
        level
      };
      
      nodes.push(node);
      
      // 親子関係のエッジを作成
      if (lineIndex > 0) {
        const parentNode = this.findParentNode(nodes, level, lineIndex);
        if (parentNode) {
          edges.push({
            source: parentNode.id,
            target: node.id,
            weight: 1
          });
        }
      }
      
      nodeId++;
    });
    
    return { nodes, edges };
  }
  
  /**
   * 行のレベルを判定
   */
  private static getLineLevel(line: string): number {
    const indentMatch = line.match(/^(\s*)/);
    const indentLevel = indentMatch ? Math.floor(indentMatch[1].length / 2) : 0;
    
    // マークダウンヘッダーのレベルも考慮
    const headerMatch = line.match(/^(#{1,6})\s/);
    if (headerMatch) {
      return headerMatch[1].length;
    }
    
    return indentLevel;
  }
  
  /**
   * 行のタイプを判定
   */
  private static getLineType(line: string): string {
    if (line.match(/^#{1,6}\s/)) return 'heading';
    if (line.match(/^[-*+]\s/)) return 'list_item';
    if (line.match(/^\d+\.\s/)) return 'ordered_list_item';
    if (line.match(/^```/)) return 'code_block';
    if (line.match(/^>/)) return 'quote';
    return 'paragraph';
  }
  
  /**
   * ラベルを抽出
   */
  private static extractLabel(line: string): string {
    // マークダウン記法を除去
    return line
      .replace(/^#{1,6}\s/, '')
      .replace(/^[-*+]\s/, '')
      .replace(/^\d+\.\s/, '')
      .replace(/^```\w*\s*/, '')
      .replace(/^>\s/, '')
      .trim();
  }
  
  /**
   * レベルに基づいてグループを決定
   */
  private static getGroupByLevel(level: number): string {
    const groups = ['A', 'B', 'C', 'D', 'E'];
    return groups[Math.min(level, groups.length - 1)];
  }
  
  /**
   * 親ノードを見つける
   */
  private static findParentNode(nodes: ASTNode[], currentLevel: number, currentIndex: number): ASTNode | null {
    for (let i = currentIndex - 1; i >= 0; i--) {
      if (nodes[i] && nodes[i].level < currentLevel) {
        return nodes[i];
      }
    }
    return null;
  }
  
  /**
   * ASTデータを検証
   */
  static validateASTData(data: ASTData): boolean {
    if (!data.nodes || !data.edges) return false;
    if (data.nodes.length === 0) return false;
    
    // エッジの参照先ノードが存在するかチェック
    const nodeIds = new Set(data.nodes.map(node => node.id));
    return data.edges.every(edge => 
      nodeIds.has(edge.source) && nodeIds.has(edge.target)
    );
  }
  
  /**
   * ASTデータをJSON形式でエクスポート
   */
  static exportToJSON(data: ASTData): string {
    return JSON.stringify(data, null, 2);
  }
  
  /**
   * JSONからASTデータをインポート
   */
  static importFromJSON(json: string): ASTData {
    try {
      const data = JSON.parse(json);
      if (this.validateASTData(data)) {
        return data;
      }
      throw new Error('Invalid AST data structure');
    } catch (error) {
      throw new Error(`Failed to parse AST JSON: ${error}`);
    }
  }
} 