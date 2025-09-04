"use client";

import ProcessLayoutPattern from "../../components/ProcessLayoutPattern";

/**
 * プロセスレイアウトパターンページ
 * 画像の項目を縦軸とした階層的なプロセスフローを表示
 */
export default function ProcessLayoutPage() {
  return (
    <div className="min-h-screen bg-gray-50 py-8">
      <div className="container mx-auto px-4">
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-gray-900 mb-4">
            プロセスレイアウトパターン
          </h1>
          <p className="text-lg text-gray-600">
            画像の項目を縦軸とした階層的なプロセスフローを表示します。
            各層は異なる色で区別され、プロセス間の関係性を視覚的に表現しています。
          </p>
        </div>

        <div className="bg-white rounded-lg shadow-lg p-6">
          <ProcessLayoutPattern width={1200} height={800} />
        </div>

        <div className="mt-8 bg-white rounded-lg shadow-lg p-6">
          <h2 className="text-2xl font-bold text-gray-900 mb-4">
            プロセスレイアウトの説明
          </h2>
          <div className="grid md:grid-cols-2 gap-6">
            <div>
              <h3 className="text-lg font-semibold text-gray-800 mb-3">
                レイヤー構造
              </h3>
              <ul className="space-y-2 text-gray-600">
                <li>
                  <strong>Layer (層):</strong>{" "}
                  システム全体の層構造を定義する最上位レイヤー
                </li>
                <li>
                  <strong>既存モデル:</strong>{" "}
                  ビジネスモデルとデータモデルを表現
                </li>
                <li>
                  <strong>既存機能:</strong> 現在実装されている機能とサービス
                </li>
                <li>
                  <strong>ISM: LTS:</strong> Long Term
                  Supportからの統合サービス管理
                </li>
                <li>
                  <strong>Web Server:</strong>{" "}
                  Webアプリケーションサーバーとフロントエンド
                </li>
                <li>
                  <strong>API Gateway:</strong> API Gateway、Load
                  Balancer、認証システム
                </li>
              </ul>
            </div>
            <div>
              <h3 className="text-lg font-semibold text-gray-800 mb-3">
                プロセスフロー
              </h3>
              <ul className="space-y-2 text-gray-600">
                <li>
                  <strong>定義:</strong> 層から既存モデルへの定義プロセス
                </li>
                <li>
                  <strong>実装:</strong> 既存モデルから既存機能への実装プロセス
                </li>
                <li>
                  <strong>統合:</strong> 既存機能からISMへの統合プロセス
                </li>
                <li>
                  <strong>配信:</strong> ISMからWeb Serverへの配信プロセス
                </li>
                <li>
                  <strong>保護:</strong> Web ServerからAPI
                  Gatewayへの保護プロセス
                </li>
              </ul>
            </div>
          </div>
        </div>

        <div className="mt-8 bg-white rounded-lg shadow-lg p-6">
          <h2 className="text-2xl font-bold text-gray-900 mb-4">使用方法</h2>
          <div className="space-y-4 text-gray-600">
            <div>
              <h3 className="text-lg font-semibold text-gray-800 mb-2">
                インタラクション
              </h3>
              <ul className="list-disc list-inside space-y-1">
                <li>ノードをクリックすると詳細情報が表示されます</li>
                <li>マウスオーバーでノードがハイライトされます</li>
                <li>ドラッグでノードを移動できます</li>
                <li>マウスホイールでズームイン/アウトできます</li>
              </ul>
            </div>
            <div>
              <h3 className="text-lg font-semibold text-gray-800 mb-2">
                コントロール
              </h3>
              <ul className="list-disc list-inside space-y-1">
                <li>
                  <strong>レイアウト再適用:</strong> ノードの配置を再整理します
                </li>
                <li>
                  <strong>ズームリセット:</strong> ビューを初期状態に戻します
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
