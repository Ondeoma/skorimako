# 開発用の環境変数（source scripts/dev-env.sh）。docker-compose.dev.yml の DB・ScalaS3 を使う
export DATABASE_URL=jdbc:postgresql://localhost:54383/skorimako DATABASE_USER=skorimako DATABASE_PASSWORD=skorimako
export PUBLIC_URL=${PUBLIC_URL:-http://localhost:5181} HTTP_PORT=8101
export DEV_LOGIN=true
export S3_ENDPOINT=http://localhost:3923 S3_BUCKET=skorimako S3_ACCESS_KEY=skorimako S3_SECRET_KEY=skorimakoskorimako
export DATA_DIR=${DATA_DIR:-$PWD/target/dev-data}
# 開発中は早めに書き出し・評価して確かめやすくする
export FLUSH_SECONDS=${FLUSH_SECONDS:-3} DUCKDB_MEMORY=1GB
# OTLP/gRPC（兄弟のサービスの開発環境とぶつからないよう 4319 にする）、ノード間の問い合わせの URL
export GRPC_PORT=${GRPC_PORT:-4319} NODE_URL=${NODE_URL:-http://localhost:8101}
# stui との連携（開発用の値。stui の scripts/dev-env.sh と対になっている）
#   skorimako → stui: 各自の stui の DM にアラートを送る（stui の OAUTH_CLIENTS に skorimako を登録してある）
export STUI_URL=http://localhost:5178 STUI_INTERNAL_URL=http://localhost:8098 STUI_CLIENT_ID=skorimako STUI_CLIENT_SECRET=skorimako-dev-secret-0123456789
#   stui → skorimako: stui のメッセージの skorimako のリンクに名前を出す
export OAUTH_CLIENTS='[{"id":"stui","secret":"stui-dev-secret-0123456789","redirectUri":"http://localhost:5178/api/integrations/skorimako/callback","name":"stui","scopes":["read"]}]'
