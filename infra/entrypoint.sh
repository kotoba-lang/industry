#!/bin/bash

# Giteaの設定ディレクトリを作成
mkdir -p /data/gitea/conf

# app.ini設定ファイルを作成
cat > /data/gitea/conf/app.ini << 'EOF'
# Gitea配置設定ファイル

APP_NAME = My Gitea
RUN_MODE = prod
RUN_USER = git

[repository]
ROOT = /data/git/repositories

[repository.local]
LOCAL_COPY_PATH = /data/gitea/tmp/local-repo

[repository.upload]
TEMP_PATH = /data/gitea/uploads

[server]
APP_DATA_PATH = /data/gitea
DOMAIN = ${GITEA__server__DOMAIN}
SSH_DOMAIN = ${GITEA__server__SSH_DOMAIN}
HTTP_PORT = 3000
ROOT_URL = ${GITEA__server__ROOT_URL}
DISABLE_SSH = false
SSH_PORT = 22
SSH_LISTEN_PORT = 22
LFS_START_SERVER = true
LFS_CONTENT_PATH = /data/git/lfs
OFFLINE_MODE = false

[database]
PATH = /data/gitea/gitea.db
DB_TYPE = ${GITEA__database__DB_TYPE}
HOST = ${GITEA__database__HOST}
NAME = ${GITEA__database__NAME}
USER = ${GITEA__database__USER}
PASSWD = ${GITEA__database__PASSWD}
SSL_MODE = require

[indexer]
ISSUE_INDEXER_PATH = /data/gitea/indexers/issues.bleve

[session]
PROVIDER_CONFIG = /data/gitea/sessions
PROVIDER = file

[picture]
AVATAR_UPLOAD_PATH = /data/gitea/avatars
REPOSITORY_AVATAR_UPLOAD_PATH = /data/gitea/repo-avatars

[attachment]
PATH = /data/gitea/attachments

[log]
MODE = console
LEVEL = info
ROOT_PATH = /data/gitea/log

[security]
INSTALL_LOCK = ${GITEA__security__INSTALL_LOCK}
SECRET_KEY = ${GITEA__security__SECRET_KEY}
INTERNAL_TOKEN = ${GITEA__security__INTERNAL_TOKEN}

[service]
DISABLE_REGISTRATION = false
REQUIRE_SIGNIN_VIEW = false
REGISTER_EMAIL_CONFIRM = false
ENABLE_NOTIFY_MAIL = false
ALLOW_ONLY_EXTERNAL_REGISTRATION = false
ENABLE_CAPTCHA = false
DEFAULT_KEEP_EMAIL_PRIVATE = false
DEFAULT_ALLOW_CREATE_ORGANIZATION = true
DEFAULT_ENABLE_TIMETRACKING = true
NO_REPLY_ADDRESS = noreply.${GITEA__server__DOMAIN}

[mailer]
ENABLED = false

[openid]
ENABLE_OPENID_SIGNIN = true
ENABLE_OPENID_SIGNUP = true

[cron.update_checker]
ENABLED = false

[repository.pull-request]
DEFAULT_MERGE_STYLE = merge

[repository.signing]
DEFAULT_TRUST_MODEL = committer

[lfs]
# Tigirisオブジェクトストレージを使用してLFSを設定
STORAGE_TYPE = minio
MINIO_ENDPOINT = ${AWS_ENDPOINT_URL_S3}
MINIO_ACCESS_KEY_ID = ${AWS_ACCESS_KEY_ID}
MINIO_SECRET_ACCESS_KEY = ${AWS_SECRET_ACCESS_KEY}
MINIO_USE_SSL = true
MINIO_BUCKET = ${BUCKET_NAME}

[storage]
# Tigirisをデフォルトストレージとして設定
STORAGE_TYPE = minio
SERVE_DIRECT = true

[storage.minio]
ENDPOINT = ${AWS_ENDPOINT_URL_S3}
ACCESS_KEY_ID = ${AWS_ACCESS_KEY_ID}
SECRET_ACCESS_KEY = ${AWS_SECRET_ACCESS_KEY}
BUCKET = ${BUCKET_NAME}
LOCATION = auto
USE_SSL = true

# アバター設定
[storage.avatars]
STORAGE_TYPE = minio
MINIO_ENDPOINT = ${AWS_ENDPOINT_URL_S3}
MINIO_ACCESS_KEY_ID = ${AWS_ACCESS_KEY_ID}
MINIO_SECRET_ACCESS_KEY = ${AWS_SECRET_ACCESS_KEY}
MINIO_BUCKET = ${BUCKET_NAME}
MINIO_USE_SSL = true

# 添付ファイル設定
[storage.attachments]
STORAGE_TYPE = minio
MINIO_ENDPOINT = ${AWS_ENDPOINT_URL_S3}
MINIO_ACCESS_KEY_ID = ${AWS_ACCESS_KEY_ID}
MINIO_SECRET_ACCESS_KEY = ${AWS_SECRET_ACCESS_KEY}
MINIO_BUCKET = ${BUCKET_NAME}
MINIO_USE_SSL = true

# リポジトリアバター設定
[storage.repo-avatars]
STORAGE_TYPE = minio
MINIO_ENDPOINT = ${AWS_ENDPOINT_URL_S3}
MINIO_ACCESS_KEY_ID = ${AWS_ACCESS_KEY_ID}
MINIO_SECRET_ACCESS_KEY = ${AWS_SECRET_ACCESS_KEY}
MINIO_BUCKET = ${BUCKET_NAME}
MINIO_USE_SSL = true
EOF

# パーミッション設定
chown -R git:git /data

# Giteaを起動
exec /usr/bin/entrypoint 