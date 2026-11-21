-- 通知先に stui のチャンネル（OAuth の連携で投稿する）とメールを足す
--   stui_channel: url はチャンネルの ID、target_user_id は通知先を作った人（その人の stui の連携のトークンで投稿する）
--   email: url は宛先のアドレス（カンマ区切り）
alter table alert_destination drop constraint if exists alert_destination_kind_check;
alter table alert_destination add constraint alert_destination_kind_check
  check (kind in ('stui', 'slack', 'webhook', 'stui_dm', 'stui_channel', 'email'));
