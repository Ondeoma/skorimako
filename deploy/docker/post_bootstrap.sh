#!/bin/sh
# クラスタ初回作成時にプライマリで1度だけ実行される: アプリ用ロール・DBを作る
set -e
psql -h /tmp -U postgres -v ON_ERROR_STOP=1 <<SQL
create role skorimako login password '${SKORIMAKO_DB_PASSWORD:-skorimako}';
create database skorimako owner skorimako;
SQL
