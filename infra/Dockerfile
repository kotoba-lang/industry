FROM gitea/gitea:1.21.3

USER root

# エントリーポイントスクリプトをコピー
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER git

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"] 