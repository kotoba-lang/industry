FROM gitea/gitea:1.21.3

USER root

# 必要なパッケージをインストール
RUN apk add --no-cache git openssh-server

# Giteaの設定ディレクトリを作成
RUN mkdir -p /data/gitea/conf

# カスタム設定ファイルをコピー
COPY app.ini /data/gitea/conf/app.ini

# パーミッション設定
RUN chown -R git:git /data

# Gitea用ユーザーに切り替え
USER git

# ポート公開
EXPOSE 3000 22

# エントリーポイント
ENTRYPOINT ["/usr/bin/entrypoint"]
CMD ["/bin/s6-svscan", "/etc/s6"] 