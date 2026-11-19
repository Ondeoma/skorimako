package skorimako.core

import skorimako.shared.FieldType

/** 列の型の決め方。 */
object FieldTypes {

  /** すでにある型 a に新しい値の型 b が来たときの型（整数と小数は小数、それ以外の食い違いは文字列）。 */
  def widen(
    a: String,
    b: String
  ): String =
    if a == b then a
    else if Set(a, b) == Set(FieldType.Int64, FieldType.Float64) then FieldType.Float64
    else FieldType.Utf8

  def sqlType(
    t: String
  ): String = t match {
    case FieldType.Int64 => "BIGINT"
    case FieldType.Float64 => "DOUBLE"
    case FieldType.Boolean => "BOOLEAN"
    case _ => "VARCHAR"
  }

  /** DuckDB の型の名前から。 */
  def fromSql(
    t: String
  ): String = {
    val u = t.toUpperCase
    if Set(
        "BIGINT",
        "INTEGER",
        "SMALLINT",
        "TINYINT",
        "HUGEINT",
        "UBIGINT",
        "UINTEGER",
        "USMALLINT",
        "UTINYINT"
      ).contains(u)
    then FieldType.Int64
    else if u == "DOUBLE" || u == "FLOAT" || u == "REAL" || u.startsWith("DECIMAL") then FieldType.Float64
    else if u == "BOOLEAN" then FieldType.Boolean
    else FieldType.Utf8
  }
}
