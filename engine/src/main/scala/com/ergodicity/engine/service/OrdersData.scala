package com.ergodicity.engine.service

import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.{FSM, Props, LoggingFSM, Actor}
import akka.pattern.ask
import akka.pattern.pipe
import akka.util
import akka.util.duration._
import com.ergodicity.cgate._
import com.ergodicity.cgate.config.Replication.ReplicationMode
import com.ergodicity.cgate.config.Replication.ReplicationMode.Snapshot
import com.ergodicity.cgate.config.Replication.ReplicationParams
import com.ergodicity.core.SessionsTracking.OngoingSession
import com.ergodicity.core.SessionsTracking.OngoingSessionTransition
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions
import com.ergodicity.core.order.OrderBooksTracking.Snapshots
import com.ergodicity.core.order.OrdersSnapshotActor.GetOrdersSnapshot
import com.ergodicity.core.order.OrdersSnapshotActor.OrdersSnapshot
import com.ergodicity.core.order.{OrdersSnapshotActor, OrderBooksTracking}
import com.ergodicity.core.session.SessionActor.AssignedContents
import com.ergodicity.core.session.SessionActor.GetAssignedContents
import com.ergodicity.engine.Listener.{OrdLogListener, FutOrderBookListener, OptOrderBookListener}
import com.ergodicity.engine.service.Service.{Stop, Start}
import com.ergodicity.engine.{Engine, Services}
import ru.micexrts.cgate.{Connection => CGConnection}

object OrdersData {

  implicit case object OrdersData extends ServiceId

}

trait OrdersData {
  this: Services =>

  import OrdersData._

  def engine: Engine with OrdLogListener with FutOrderBookListener with OptOrderBookListener

  lazy val creator = new OrdersDataService(engine.futOrderbookListener, engine.optOrderbookListener, engine.ordLogListener)
  register(Props(creator), dependOn = InstrumentData.InstrumentData :: Nil)
}

protected[service] sealed trait OrdersDataState

object OrdersDataState {

  case object Idle extends OrdersDataState

  case object AssigningInstruments extends OrdersDataState

  case object WaitingSnapshots extends OrdersDataState

  case object StartingOrderBooks extends OrdersDataState

  case object Started extends OrdersDataState

  case object Stopping extends OrdersDataState

}

protected[service] class OrdersDataService(futOrderBook: ListenerBinding, optOrderBook: ListenerBinding, ordLog: ListenerBinding)
                                          (implicit val services: Services, id: ServiceId) extends Actor with LoggingFSM[OrdersDataState, Unit] with Service {

  import OrdersDataState._

  implicit val timeout = util.Timeout(30.second)

  private[this] val instrumentData = services(InstrumentData.InstrumentData)

  // Orders streams
  val OrdLogStream = context.actorOf(Props(new DataStream), "OrdLogStream")

  // Order Log listener
  ordLog.bind(new DataStreamSubscriber(OrdLogStream))
  private[this] val ordLogListener = context.actorOf(Props(new Listener(ordLog.listener)).withDispatcher(Engine.ReplicationDispatcher), "OrdLogListener")

  // OrderBook streams
  val FutOrderBookStream = context.actorOf(Props(new DataStream), "FutOrderBookStream")
  val OptOrderBookStream = context.actorOf(Props(new DataStream), "OptOrderBookStream")

  // OrderBook listeners
  futOrderBook.bind(new DataStreamSubscriber(FutOrderBookStream))
  private[this] val futOrderBookListener = context.actorOf(Props(new Listener(futOrderBook.listener)).withDispatcher(Engine.ReplicationDispatcher), "FutOrderBookListener")

  optOrderBook.bind(new DataStreamSubscriber(OptOrderBookStream))
  private[this] val optOrderBookListener = context.actorOf(Props(new Listener(optOrderBook.listener)).withDispatcher(Engine.ReplicationDispatcher), "OptOrderBookListener")

  // OrderBook snapshots
  val FuturesSnapshot = context.actorOf(Props(new OrdersSnapshotActor(FutOrderBookStream)), "FuturesSnapshot")
  val OptionsSnapshot = context.actorOf(Props(new OrdersSnapshotActor(OptOrderBookStream)), "OptionsSnapshot")

  // OrderBooks
  val OrderBooks = context.actorOf(Props(new OrderBooksTracking(OrdLogStream)), "OrderBooks")

  override def preStart() {
    log.info("Start " + id + " service")
  }

  startWith(Idle, ())

  when(Idle) {
    case Event(Start, _) =>
      instrumentData ! SubscribeOngoingSessions(self)
      goto(AssigningInstruments)
  }

  when(AssigningInstruments, stateTimeout = 30.seconds) {
    case Event(OngoingSession(_, ref), _) =>
      (ref ? GetAssignedContents).mapTo[AssignedContents] pipeTo self
      stay()

    case Event(assigned: AssignedContents, _) =>
      // Forward assigned contents to OrderBooks & TradesTracking
      OrderBooks ! assigned

      // Open orderbook snapshots
      futOrderBookListener ! Listener.Open(ReplicationParams(Snapshot))
      optOrderBookListener ! Listener.Open(ReplicationParams(Snapshot))

      // Get Futures & Options snapshots
      val futSnapshot = (FuturesSnapshot ? GetOrdersSnapshot).mapTo[OrdersSnapshot]
      val optSnapshot = (OptionsSnapshot ? GetOrdersSnapshot).mapTo[OrdersSnapshot]
      (futSnapshot zip optSnapshot).map(tuple => Snapshots(tuple._1, tuple._2)) pipeTo self

      goto(WaitingSnapshots)

    case Event(FSM.StateTimeout, _) => failed("Assigning instruments timed out")
  }

  when(WaitingSnapshots, stateTimeout = 30.seconds) {
    case Event(snapshots@Snapshots(fut, opt), _) =>
      OrderBooks ! snapshots

      // Open orders log from min revision
      val params = ReplicationParams(ReplicationMode.Combined, Map("orders_log" -> scala.math.min(fut.revision, opt.revision)))
      ordLogListener ! Listener.Open(params)

      // And watch for stream state
      OrdLogStream ! SubscribeTransitionCallBack(self)
      goto(StartingOrderBooks)

    case Event(FSM.StateTimeout, _) => failed("Waiting snapshots timed out")
  }

  when(StartingOrderBooks, stateTimeout = 30.seconds) {
    case Event(CurrentState(OrdLogStream, DataStreamState.Online), _) => goto(Started)

    case Event(Transition(OrdLogStream, _, DataStreamState.Online), _) => goto(Started)

    case Event(FSM.StateTimeout, _) => failed("Starting timed out")
  }

  when(Started) {
    case Event(Stop, states) =>
      log.info("Stop " + id + " service")
      ordLogListener ! Listener.Close
      goto(Stopping)
  }

  when(Stopping, stateTimeout = 10.seconds) {
    case Event(Transition(OrdLogStream, _, DataStreamState.Closed), _) => shutDown

    case Event(FSM.StateTimeout, _) => failed("Stopping timed out")
  }

  onTransition {
    case StartingOrderBooks -> Started => services.serviceStarted
  }

  whenUnhandled {
    case Event(OngoingSessionTransition(_, OngoingSession(_, ref)), _) =>
      (ref ? GetAssignedContents).mapTo[AssignedContents] pipeTo OrderBooks
      stay()
  }

  initialize

  private def shutDown: State = {
    ordLogListener ! Listener.Dispose
    futOrderBookListener ! Listener.Dispose
    optOrderBookListener ! Listener.Dispose
    services.serviceStopped
    stop(FSM.Shutdown)
  }
}