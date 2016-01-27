package controllers
import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Json
import play.api.Play.current
import play.api.data._
import play.api.data.Forms._
import play.api.libs.ws._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import scala.concurrent.Future
import play.api.libs.json._
import com.github.nscala_time.time.Imports._
import Highchart._
import models._

object Query extends Controller {
  def historyTrend = Security.Authenticated {
    implicit request =>
      Ok(views.html.historyTrend())
  }

  def windAvg(sum_sin: Double, sum_cos: Double) = {
    val degree = Math.toDegrees(Math.atan2(sum_sin, sum_cos))
    if (degree >= 0)
      degree
    else
      degree + 360
  }

  def windAvg(windSpeed: Seq[Record], windDir: Seq[Record]):Double = {
    if (windSpeed.length != windDir.length)
      Logger.error(s"windSpeed #=${windSpeed.length} windDir #=${windDir.length}")

    val windRecord = windSpeed.zip(windDir)
    val wind_sin = windRecord.map(v => v._1.value * Math.sin(Math.toRadians(v._2.value))).sum
    val wind_cos = windRecord.map(v => v._1.value * Math.cos(Math.toRadians(v._2.value))).sum
    windAvg(wind_sin, wind_cos)
  }
  
  def getPeriods(start: DateTime, endTime: DateTime, d: Period): List[DateTime] = {
    import scala.collection.mutable.ListBuffer

    val buf = ListBuffer[DateTime]()
    var current = start
    while (current < endTime) {
      buf.append(current)
      current += d
    }

    buf.toList
  }

  def getPeriodReportMap(mt: MonitorType.Value, period: Period, statusFilter:List[String]=List("010"))(start: DateTime, end: DateTime) = {
    val recordList = Record.getRecordList(Record.HourCollection)(mt, start, end)
    def periodSlice(period_start: DateTime, period_end: DateTime) = {
      recordList.dropWhile { _.time < period_start }.takeWhile { _.time < period_end }
    }
    val pairs =
      if (period.getHours ==1) {
        recordList.filter { r=>statusFilter.contains(r.status) }.map { r => r.time -> r.value }
      } else {
        for {
          period_start <- getPeriods(start, end, period)
          records = periodSlice(period_start, period_start + period) if records.length > 0          
        } yield {
          if (mt == MonitorType.withName("WD_HR")) {
            val windDir = records
            val windSpeed = Record.getRecordList(Record.HourCollection)(MonitorType.withName("WS_HR"), period_start, period_start + period)
            period_start -> windAvg(windSpeed, windDir)
          } else{
            val values = records.map { r => r.value }
            period_start -> values.sum / values.length
          }
        }
      }

    Map(pairs: _*)
  }

  def trendHelper(monitorTypes: Array[MonitorType.Value], reportUnit: ReportUnit.Value, start: DateTime, end: DateTime) = {

    val windMtv = MonitorType.withName("WD_HR")
    val period: Period =
      reportUnit match {
        case ReportUnit.Hour =>
          1.hour
        case ReportUnit.Day =>
          1.day
        case ReportUnit.Month =>
          1.month
        case ReportUnit.Quarter =>
          3.month
        case ReportUnit.Year =>
          1.year
      }

    val timeSet = getPeriods(start, end, period)
    val timeSeq = timeSet.toList.sorted.zipWithIndex

    def getSeries() = {

      val mtReportPairs =
        for {
          mt <- monitorTypes
          reportMap = getPeriodReportMap(mt, period)(start, end)
        } yield {
          mt -> reportMap
        }

      val mtReportMap = Map(mtReportPairs: _*)
      for {
        mt <- monitorTypes
        valueMap = mtReportMap(mt)
        timeData = timeSeq.map { t =>
          val time = t._1
          val x = t._2
          if (valueMap.contains(time))
            Seq(Some(time.getMillis.toDouble), Some(valueMap(time).toDouble))
          else
            Seq(Some(time.getMillis.toDouble), None)
        }
      } yield {
        if (monitorTypes.length > 1 && monitorTypes.contains(windMtv)) {
          if (mt != windMtv)
            seqData(MonitorType.map(mt).desp, timeData)
          else
            seqData(MonitorType.map(mt).desp, timeData, 1, Some("scatter"))
        } else {
          seqData(MonitorType.map(mt).desp, timeData)
        }
      }
    }

    val series = getSeries()

    val downloadFileName = {
      val startName = start.toString("YYMMdd")
      val mtNames = monitorTypes.map { MonitorType.map(_).desp }
      startName + mtNames.mkString
    }

    val title =
      reportUnit match {
        case ReportUnit.Hour =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.Day =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Month =>
          s"趨勢圖 (${start.toString("YYYY年MM月")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Quarter =>
          s"趨勢圖 (${start.toString("YYYY年MM月")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Year =>
          s"趨勢圖 (${start.toString("YYYY年")}~${end.toString("YYYY年")})"
      }

    def getAxisLines(mt: MonitorType.Value) = {
      val mtCase = MonitorType.map(mt)
      val std_law_line =
        if (mtCase.std_law.isEmpty)
          None
        else
          Some(AxisLine("#FF0000", 2, mtCase.std_law.get, Some(AxisLineLabel("right", "法規值"))))

      val lines = Seq(std_law_line, None).filter { _.isDefined }.map { _.get }
      if (lines.length > 0)
        Some(lines)
      else
        None
    }

    val xAxis = {
      val duration = new Duration(start, end)
      if (duration.getStandardDays > 2)
        XAxis(None, gridLineWidth = Some(1), None)
      else
        XAxis(None)
    }

    val windMtCase = MonitorType.map(windMtv)
    val windYaxis = YAxis(None, AxisTitle(Some(Some(s"${windMtCase.desp} (${windMtCase.unit})"))), None,
      opposite = true,
      floor = Some(0),
      ceiling = Some(360),
      min = Some(0),
      max = Some(360),
      tickInterval = Some(45),
      gridLineWidth = Some(1),
      gridLineColor = Some("#00D800"))

    val chart =
      if (monitorTypes.length == 1) {
        val mt = monitorTypes(0)
        val mtCase = MonitorType.map(monitorTypes(0))

        HighchartData(
          Map("type" -> "line"),
          Map("text" -> title),
          xAxis,
          if (!monitorTypes.contains(windMtv))
            Seq(YAxis(None, AxisTitle(Some(Some(s"${mtCase.desp} (${mtCase.unit})"))), getAxisLines(mt)))
          else
            Seq(windYaxis),
          series,
          Some(downloadFileName))
      } else {
        val yAxis =
          if (monitorTypes.contains(windMtv)) {
            if (monitorTypes.length == 2) {
              val mt = monitorTypes.filter { _ != windMtv }(0)
              val mtCase = MonitorType.map(monitorTypes.filter { !MonitorType.windDirList.contains(_) }(0))
              Seq(YAxis(None,
                AxisTitle(Some(Some(s"${mtCase.desp} (${mtCase.unit})"))),
                getAxisLines(mt),
                gridLineWidth = Some(0)),
                windYaxis)
            } else {
              Seq(YAxis(None, AxisTitle(Some(None)), None, gridLineWidth = Some(0)),
                windYaxis)
            }
          } else {
            Seq(YAxis(None, AxisTitle(Some(None)), None))
          }

        HighchartData(
          Map("type" -> "line"),
          Map("text" -> title),
          xAxis,
          yAxis,
          series,
          Some(downloadFileName))
      }

    chart
  }

  def historyTrendChart(monitorTypeStr: String, reportUnitStr: String,
                        startStr: String, endStr: String, outputTypeStr: String) = Security.Authenticated {
    implicit request =>
      import scala.collection.JavaConverters._
      val monitorTypeStrArray = monitorTypeStr.split(':')
      val monitorTypes = monitorTypeStrArray.map { MonitorType.withName }
      val reportUnit = ReportUnit.withName(reportUnitStr)
      val (start, end) =
        if (reportUnit == ReportUnit.Hour) {
          (DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm")),
            DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm")))
        } else if (reportUnit == ReportUnit.Day) {
          (DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd")),
            DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd")))
        } else {
          (DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-M")),
            DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-M")))
        }

      val outputType = OutputType.withName(outputTypeStr)

      val chart = trendHelper(monitorTypes, reportUnit, start, end)

      if (outputType == OutputType.excel) {
        import java.nio.file.Files
        val excelFile = ExcelUtility.exportChartData(chart, monitorTypes)
        val downloadFileName =
          if (chart.downloadFileName.isDefined)
            chart.downloadFileName.get
          else
            chart.title("text")


        Ok.sendFile(excelFile, fileName = _ =>
          play.utils.UriEncoding.encodePathSegment(downloadFileName + ".xlsx", "UTF-8"),
          onClose = () => { Files.deleteIfExists(excelFile.toPath()) })
      } else {
        Results.Ok(Json.toJson(chart))
      }
  }

  def history = Security.Authenticated {
    implicit request =>
      Ok(views.html.history())
  }

  def historyReport(monitorTypeStr: String,
                    startStr: String, endStr: String) = Security.Authenticated {
    implicit request =>
      import scala.collection.JavaConverters._
      val monitorTypeStrArray = monitorTypeStr.split(':')
      val monitorTypes = monitorTypeStrArray.map { MonitorType.withName }
      val (start, end) =
        (DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm")),
          DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm")))

      val timeList = getPeriods(start, end, 1.hour)

      val recordMap = Record.getRecordMap(Record.HourCollection)(monitorTypes.toList, start, end)
      val recordTimeMap = recordMap.map{p =>
        val recordSeq = p._2
        val timePair = recordSeq.map { r => r.time -> r }
        p._1 -> Map(timePair:_*)
      }
      
      val explain = monitorTypes.map { t => 
          val mtCase = MonitorType.map(t)
          s"${mtCase.desp}(${mtCase.unit})"
        }.mkString(",")
      val output = views.html.historyReport(monitorTypes, explain, start, end, timeList, recordTimeMap)
      Ok(output)
  }
  
//
//  def windRose() = Security.Authenticated {
//    implicit request =>
//      Ok(views.html.windRose(false))
//  }
//
//  def monitorTypeRose() = Security.Authenticated {
//    implicit request =>
//      Ok(views.html.windRose(true))
//  }
//
//  def windRoseReport(monitorStr: String, monitorTypeStr: String, nWay: Int, startStr: String, endStr: String) = Security.Authenticated {
//    val monitor = EpaMonitor.withName(monitorStr)
//    val monitorType = MonitorType.withName(monitorTypeStr)
//    val start = DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))
//    val end = DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))
//    val mtCase = MonitorType.map(monitorType)
//    assert(nWay == 8 || nWay == 16 || nWay == 32)
//
//    try {
//      val level = List(1f, 2f, 5f, 15f)
//      val windMap = Record.getMtRose(monitor, monitorType, start, end, level, nWay)
//      val nRecord = windMap.values.map { _.length }.sum
//
//      val dirMap =
//        Map(
//          (0 -> "北"), (1 -> "北北東"), (2 -> "東北"), (3 -> "東北東"), (4 -> "東"),
//          (5 -> "東南東"), (6 -> "東南"), (7 -> "南南東"), (8 -> "南"),
//          (9 -> "南南西"), (10 -> "西南"), (11 -> "西西南"), (12 -> "西"),
//          (13 -> "西北西"), (14 -> "西北"), (15 -> "北北西"))
//
//      val dirStrSeq =
//        for {
//          dir <- 0 to nWay - 1
//          dirKey = if (nWay == 8)
//            dir * 2
//          else if (nWay == 32) {
//            if (dir % 2 == 0) {
//              dir / 2
//            } else
//              dir + 16
//          } else
//            dir
//        } yield dirMap.getOrElse(dirKey, "")
//
//      var last = 0f
//      val speedLevel = level.flatMap { l =>
//        if (l == level.head) {
//          last = l
//          List(s"< ${l} ${mtCase.unit}")
//        } else if (l == level.last) {
//          val ret = List(s"${last}~${l} ${mtCase.unit}", s"> ${l} ${mtCase.unit}")
//          last = l
//          ret
//        } else {
//          val ret = List(s"${last}~${l} ${mtCase.unit}")
//          last = l
//          ret
//        }
//      }
//
//      import Highchart._
//
//      val series = for {
//        level <- 0 to level.length
//      } yield {
//        val data =
//          for (dir <- 0 to nWay - 1)
//            yield Seq(Some(dir.toDouble), Some(windMap(dir)(level).toDouble))
//
//        seqData(speedLevel(level), data)
//      }
//
//      val title =
//        if (monitorType == "")
//          "風瑰圖"
//        else {
//          mtCase.desp + "玫瑰圖"
//        }
//
//      val chart = HighchartData(
//        scala.collection.immutable.Map("polar" -> "true", "type" -> "column"),
//        scala.collection.immutable.Map("text" -> title),
//        XAxis(Some(dirStrSeq)),
//        Seq(YAxis(None, AxisTitle(Some(Some(""))), None)),
//        series)
//
//      Results.Ok(Json.toJson(chart))
//    } catch {
//      case e: AssertionError =>
//        Logger.error(e.toString())
//        BadRequest("無資料")
//    }
//  }

}