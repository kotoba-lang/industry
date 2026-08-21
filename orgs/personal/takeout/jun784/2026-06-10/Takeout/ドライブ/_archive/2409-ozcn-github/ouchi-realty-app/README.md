# Ouchi-web3auth-auth0-example

## How to Deploy

### Make `.env` (Based on `.env.ex`)
```sh
- NEXT_PUBLIC_NODE_ENV='development'
## web3_auth_client_id get from https://dashboard.web3auth.io
- NEXT_PUBLIC_WEB3_AUTH_CLIENT_ID='add_your_web3_auth_client_id'
## web3_auth_verifier (name of verifier)
- NEXT_PUBLIC_WEB3_AUTH_VERIFIER='add_your_web3_auth_verifier'
## auth0_client_id get form https://manage.auth0.com
- NEXT_PUBLIC_AUTH0_CLIENT_ID='add_your_auth0_client_id'
- NEXT_PUBLIC_AUTH0_DOMAIN='add_your_auth0_domain'
```

## config web3auth
![Web3Auth-config](https://github.com/openreachtech/ouchi-web3auth-auth0-example/assets/80658278/ec94b132-fcc5-41b4-9d68-26d33ccd1143)

補足:
- web3authのプロジェクトのenvironmentと、web3AuthNetwork が一致しているか確認する
- リダイレクト時にjwtがurlについて渡ってくるので、そちらを取得することで正しくログインできているか確認できる

### Build Source Code

```sh
npm install
npm run build
```

### pm2
```sh
pm2 start ecosystem.config.js
```

### TODO

[ ] UIモックアップ作成
[ ] Project一覧
[ ] プロジェクト追加
[ ] アイテム一覧
[ ] バックエンドを構築
[ ] 二時流通
[ ] データベース追加
[ ] アカウント
[ ] 設定
[ ] Linter

  "rules": {
    "semi": ["error", "never"],
    "react/prop-types": "off"
  }