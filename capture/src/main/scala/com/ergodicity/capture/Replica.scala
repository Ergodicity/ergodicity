package com.ergodicity.capture

import com.ergodicity.cgate.scheme._

case class Replica(replID: Long, replRev: Long, replAct: Long)

trait ReplicaExtractor[T] {
  def apply(in: T): Replica = repl(in)

  def repl(in: T): Replica
}

object ReplicaExtractor {
  type ReplicaRecord = {def get_replID(): Long; def get_replRev(): Long; def get_replAct(): Long}

  private def replica(in: ReplicaRecord) = Replica(in.get_replID(), in.get_replRev(), in.get_replAct())

  implicit val FutInfoSessionExtractor = new ReplicaExtractor[FutInfo.session] {
    def repl(in: FutInfo.session) = replica(in)
  }

  implicit val FutInfoSessionContentsExtractor = new ReplicaExtractor[FutInfo.fut_sess_contents] {
    def repl(in: FutInfo.fut_sess_contents) = replica(in)
  }

  implicit val FutInfoSysEventsExtractor = new ReplicaExtractor[FutInfo.sys_events] {
    def repl(in: FutInfo.sys_events) =
      replica(in)
  }

  implicit val OptInfoSessionContentsExtractor = new ReplicaExtractor[OptInfo.opt_sess_contents] {
    def repl(in: OptInfo.opt_sess_contents) = replica(in)
  }

  implicit val OptInfoSysEventsExtractor = new ReplicaExtractor[OptInfo.sys_events] {
    def repl(in: OptInfo.sys_events) =
      replica(in)
  }

  implicit val PosPositionsExtractor = new ReplicaExtractor[Pos.position] {
    def repl(in: Pos.position) = replica(in)
  }

  implicit val OrdLogOrdersExtractor = new ReplicaExtractor[OrdLog.orders_log] {
    def repl(in: OrdLog.orders_log) = replica(in)
  }

  implicit val FutTradeDealsExtractor = new ReplicaExtractor[FutTrade.deal] {
    def repl(in: FutTrade.deal) = replica(in)
  }

  implicit val FutTradeOrdersExtractor = new ReplicaExtractor[FutOrder.orders_log] {
    def repl(in: FutOrder.orders_log) = replica(in)
  }

  implicit val OptTradeDealsExtractor = new ReplicaExtractor[OptTrade.deal] {
    def repl(in: OptTrade.deal) = replica(in)
  }

  implicit val OptTradeOrdersExtractor = new ReplicaExtractor[OptOrder.orders_log] {
    def repl(in: OptOrder.orders_log) = replica(in)
  }

  implicit val OrderBookOrdersExtractor = new ReplicaExtractor[OrdBook.orders] {
    def repl(in: OrdBook.orders) = replica(in)
  }

  implicit val OrderBookInfoExtractor = new ReplicaExtractor[OrdBook.info] {
    def repl(in: OrdBook.info) = replica(in)
  }
}