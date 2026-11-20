package skorimako.api

import cats.effect.*
import cats.syntax.all.*
import skorimako.core.*
import skorimako.db.*
import skorimako.engine.*
import skorimako.shared.*

import java.util.UUID

/**
 * アラートのルールの閲覧権限。ルールは、対象のストリームを見られる人だけが見られる
 * （一覧・履歴・確認済み・ミュートも同じ。ルールの名前・値・SQL から、見られないストリームの中身が分からないように）。
 */
final class AlertAccess(store: AlertStore, engine: QueryEngine) {

  /**
   * SQL のルールが参照するストリームを、その人がすべて見られるか。
   * 時刻が来たときの評価はサーバーの権限で行うので、保存するときに確かめる（確かめないと、見る権限のないストリームの値をルール経由で読める）。
   */
  def sqlVisible(viewer: Viewer, r: AlertRuleReq): IO[Boolean] =
    SqlText.checkQuery(r.sql.getOrElse("")) match {
      case Left(_) => IO.pure(false)
      case Right((_, names)) => engine.allVisible(viewer, r.streamType, names)
    }

  /** ルールの対象のストリームを見られるか（SQL のルールは、SQL が参照するストリームすべて）。 */
  def canSee(viewer: Viewer, r: AlertRuleReq): IO[Boolean] =
    if r.mode == AlertMode.Sql then sqlVisible(viewer, r) else engine.acl.visible(viewer, r.streamType, r.stream)

  /** 保存してあるルールを見られるか（作るとき・変えるときと同じ判定）。 */
  def visibleRule(viewer: Viewer, rule: RuleRow): IO[Boolean] =
    if viewer.admin then IO.pure(true) else canSee(viewer, rule.toReq)

  def filter(viewer: Viewer, rules: List[RuleRow]): IO[List[RuleRow]] =
    if viewer.admin then IO.pure(rules) else rules.filterA(visibleRule(viewer, _))

  /** 見られるルール。見られないものは、ないものと同じ（None）。 */
  def rule(viewer: Viewer, id: UUID): IO[Option[RuleRow]] =
    store.rule(id).flatMap(_.filterA(visibleRule(viewer, _)))

  /** 見られるルールの ID（None はすべて: 管理者）。 */
  def visibleIds(viewer: Viewer): IO[Option[Set[UUID]]] =
    if viewer.admin then IO.pure(None) else store.rules.flatMap(filter(viewer, _)).map(rs => Some(rs.map(_.id).toSet))

  /** 履歴（見られるルールのものだけ）。 */
  def events(viewer: Viewer, ruleId: Option[UUID], limit: Int): IO[List[AlertEventDto]] =
    visibleIds(viewer).flatMap(store.events(ruleId, limit, _))
}
