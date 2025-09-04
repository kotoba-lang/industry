
# Kotoba Shared Package

BitDevコンポーネントとhostアプリケーションで共有するユーティリティとスタイルのパッケージです。

## 使用方法

### 1. Tailwind CSS設定の共有

```javascript
// 各BitDevコンポーネントのtailwind.config.js
import sharedConfig from '../../../packages/shared/tailwind.config.js';

export default {
  ...sharedConfig,
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
}
```

### 2. 共通CSSのインポート

```css
/* 各BitDevコンポーネントのCSSファイル */
@import '../../../packages/shared/styles/index.css';

/* コンポーネント固有のスタイル */
.component-specific {
  /* カスタムスタイル */
}
```

### 3. 利用可能なCSSクラス

#### ボタン
```jsx
<button className="btn btn-primary">Primary Button</button>
<button className="btn btn-secondary">Secondary Button</button>
<button className="btn btn-danger">Danger Button</button>
```

#### カード
```jsx
<div className="card">
  <div className="card-header">
    <h3>Card Title</h3>
  </div>
  <div className="card-body">
    <p>Card content</p>
  </div>
</div>
```

#### パネル
```jsx
<div className="panel">
  <div className="panel-header">
    <h3>Panel Title</h3>
  </div>
  <div className="panel-body">
    <p>Panel content</p>
  </div>
</div>
```

#### バッジ
```jsx
<span className="badge badge-success">Success</span>
<span className="badge badge-info">Info</span>
<span className="badge badge-warning">Warning</span>
<span className="badge badge-error">Error</span>
```

#### フォーム
```jsx
<label className="form-label">Email</label>
<input type="email" className="form-input" placeholder="Enter email" />
```

#### レイアウト
```jsx
<div className="container-fluid">
  <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
    {/* コンテンツ */}
  </div>
</div>
```

## カスタマイズ

### 新しいコンポーネントクラスの追加

`packages/shared/styles/index.css`の`@layer components`セクションに追加：

```css
@layer components {
  .my-component {
    @apply bg-white p-4 rounded-lg shadow-sm;
  }
}
```

### 新しいユーティリティクラスの追加

`packages/shared/styles/index.css`の`@layer utilities`セクションに追加：

```css
@layer utilities {
  .my-utility {
    /* カスタムユーティリティ */
  }
}
```

## ベストプラクティス

1. **一貫性**: 共通クラスを使用してデザインの一貫性を保つ
2. **拡張性**: 新しいスタイルは既存のクラスを拡張する
3. **保守性**: コンポーネント固有のスタイルは最小限に保つ
4. **パフォーマンス**: 不要なCSSは削除し、バンドルサイズを最適化する

## トラブルシューティング

### Tailwindクラスが適用されない場合

1. `tailwind.config.js`の`content`パスが正しいか確認
2. 共通設定が正しくインポートされているか確認
3. CSSファイルが正しくインポートされているか確認

### スタイルの競合

1. CSSの詳細度を確認
2. `!important`の使用を避ける
3. コンポーネント固有のスタイルは適切にスコープする 