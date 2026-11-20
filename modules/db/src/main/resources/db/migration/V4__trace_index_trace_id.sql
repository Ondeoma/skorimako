-- トレース ID だけで引く検索（トレースの表示・リンクの要約）が、trace_index を全部読まないようにする
-- （主キーは (stream_id, trace_id) で、trace_id だけの条件には使えない）
create index trace_index_trace_id_idx on trace_index (trace_id);
