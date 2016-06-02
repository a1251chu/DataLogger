package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object Adam4017Collector {
  import Adam4017._
  import Protocol.ProtocolParam

  case class PrepareCollect(id: String, com: Int, param: List[Adam4017Param])
  case object Collect

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, param: List[Adam4017Param])(implicit context: ActorContext) = {
    val collector = context.actorOf(Props[Adam4017Collector], name = "Adam4017Collector" + count)
    count += 1
    assert(protocolParam.protocol == Protocol.serial)
    val com = protocolParam.comPort.get
    collector ! PrepareCollect(id, com, param)
    collector
  }
}

class Adam4017Collector extends Actor {
  import Adam4017Collector._
  import Adam4017._
  import java.io.BufferedReader
  import java.io._

  var instId: String = _
  var cancelable: Cancellable = _
  var comm: SerialComm = _
  var paramList: List[Adam4017Param] = _

  def decode(str: String)(param: Adam4017Param) = {
    val ch = str.substring(1).split("(?=[+-])", 8)
    if (ch.length != 8)
      throw new Exception("unexpected format:" + str)

    import DataCollectManager._
    import java.lang._
    val values = ch.map { Double.valueOf(_) }
    val dataList =
      for {
        cfg <- param.ch.zipWithIndex
        chCfg = cfg._1 if chCfg.enable
        idx = cfg._2
      } yield {
        val v = chCfg.mtMin.get + (chCfg.mtMax.get - chCfg.mtMin.get) / (chCfg.max.get - chCfg.min.get) * (values(idx) - chCfg.min.get)
        MonitorTypeData(chCfg.mt.get, v, "010")
      }
    context.parent ! ReportData(dataList.toList)
  }

  import DataCollectManager._
  import scala.concurrent.Future
  import scala.concurrent.blocking

  var collectorState = MonitorStatus.NormalStat
  def receive = {
    case PrepareCollect(id, com, param) =>
      Future {
        blocking {
          try {
            instId = id
            paramList = param
            comm = SerialComm.open(com)
            cancelable = Akka.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              Logger.info("Try again 1 min later...")
              //Try again
              cancelable = Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, PrepareCollect(id, com, param))
          }

        }
      } onFailure futureErrorHandler

    case Collect =>
      Future {
        blocking {
          val os = comm.os
          val is = comm.is
          for (param <- paramList) {
            val readCmd = s"#${param.addr}\r"
            os.write(readCmd.getBytes)
            val str = comm.port.readString()
            if (str != null) {
              decode(str)(param)
            }
          }
          cancelable = Akka.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
        }
      } onFailure futureErrorHandler

    case SetState(id, state) =>
      Logger.info(s"$self => $state")
      collectorState = state
  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()

    if (comm != null)
      SerialComm.close(comm)
  }
}