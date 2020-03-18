import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Random
import java.util.concurrent.{BlockingDeque, BlockingQueue, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import AddComputer.random
import io.gatling.core.Predef._
import io.gatling.core.controller.inject.open.OpenInjectionStep
import io.gatling.http.Predef._

class BasicSimulation extends Simulation {
  val httpConf = http.baseUrl("http://computer-database.gatling.io")
    .acceptHeader("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    .doNotTrackHeader("1")
    .acceptLanguageHeader("en-US,en;q=0.5")
    .acceptEncodingHeader("gzip, deflate")
    .userAgentHeader("Mozilla/5.0 (Windows NT 5.1; rv:31.0) Gecko/20100101 Firefox/31.0")
    .disableFollowRedirect
    .header("foo", "bar")

  val feederName = tsv("computersName.csv").circular

  val scn = scenario("BasicSimulation").feed(feederName)
    .exec(AddComputer.gotoAdd)
    .exec( session => {
      session.set("name", session("model").as[String]+" tinkoff "+random.nextInt(Integer.MAX_VALUE))
    })
    .exec(AddComputer.add("${name}"))
    .pause("50", TimeUnit.MILLISECONDS)
    .exec(RemoveComputer.removeFirstFoundComputer("${name}"))

  val maxPerf : List[OpenInjectionStep] = List(
    rampUsersPerSec(0).to(10).during(5),
    constantUsersPerSec(10) during(10),
    rampUsersPerSec(10) to (20) during(5),
    constantUsersPerSec(20) during(10),
    rampUsersPerSec(20) to (30) during(5),
    constantUsersPerSec(30) during(10)
  )

  val stable : List[OpenInjectionStep] = List(
    rampUsersPerSec(0).to(10).during(5),
    constantUsersPerSec(10) during(360));

  setUp(
   scn.inject(maxPerf).protocols(httpConf))
   //scenario("BasicSimulation").exec(repeat(50) {RemoveComputer.removeFirstFoundComputer("")}).inject(atOnceUsers(1)).protocols(httpConf))
}

object Search {
  def gotoSearchPage(page: Int , filterComputers: String) =
    exec(
      http("Search")
        .get("/computers")
        .queryParam("f", filterComputers)
        .queryParam("p", page)
        .check(status.is(200))
        .check(regex("No computers found").find.notExists)
        .check(regex("Nothing to display").find.notExists)
        .check(regex("<a\\s+?href=\\\"(/computers/\\d+)\\\"" ).findAll.saveAs("computerURLs"))
        .check(
          checkIf(page == 0)
          (
            regex("<section id=\"main\">\\s*?<h1>([\\d,]+?) computers found</h1>").find.transform(
              string => string.replace(",", "")
            ).saveAs("numberComputers")
          )
        )
    )

  def getAllFoundComputers(filterComputers: String) =
    gotoSearchPage(0, filterComputers)
    .doIf(session => session("numberComputers").as[Int] > 10) {
      exec(session => {
        session.set("numberPage", 1 until session("numberComputers").as[Int]/10)
        session.set("allFound", session("computerURLs").as[List[String]])
      })
      .foreach("${numberPage.size()}", "counter") {
        exec(
          gotoSearchPage(1, filterComputers)
        )
        .exec(session =>
          session.set("allFound", session("computerURLs").as[List[String]])
        )
      }
    }
}

object BrowsePage {
  def gotoPage(page: Int) =
    exec(
      addCookie(Cookie("status", " You shall not pass!"))
    )
    .exec(
      http("Page " + page)
        .get("/computers?p=" + page)
        .check(status is 200)
    )
}

object AddComputer {
  val cal = Calendar.getInstance()
  val dateFormat = new SimpleDateFormat("yyyy-MM-dd")
  val random = new Random()
  val gotoAdd =
    exec(
      http("GoToNewComputer")
        .get("/computers/new")
        .check(status.is(200))
    )
  def add (name : String) =
    exec(
      http("AddComputer")
        .post("/computers")
        .formParam("name", name)
        .formParam("introduced", dateFormat.format(cal.getTime()))
        .formParam("discontinued", dateFormat.format({
          cal.add(Calendar.YEAR, 1)
          cal.getTime()
        }))
        .formParam("company", random.nextInt(43))
        .check(status.is(303))
    )
}

object RemoveComputer {
  val queue = new LinkedBlockingQueue[String]()

  def remove(urlPath: String) =
    exec(
      http("GotoPageRemove")
        .get(urlPath)
        .check(status.is(200))
    )
    .exec(
      http("Remove")
        .post(urlPath+"/delete")
        .check(status.is(303))
    )

  def removeFirstFoundComputer(filterComputers: String) =
    exec(
      Search.gotoSearchPage(0, filterComputers)
    )
    .exec(session  => {
      session.set("removeElement", session("computerURLs").as[List[String]].head)
    })
    .exec(
      remove("${removeElement}")
    )

  def removeAllFoundComputers(filterComputers: String) =
    exec(
      Search.getAllFoundComputers(filterComputers)
    )
    .foreach("${allFound}", "removeElement") {
      exec(
        remove("${removeElement}")
      )
    }
}