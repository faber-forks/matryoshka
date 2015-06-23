package slamdata.engine.api

import slamdata.engine._
import slamdata.engine.analysis.fixplate.{Term}
import slamdata.engine.config._
import slamdata.engine.fp._
import slamdata.engine.fs._

import scala.collection.immutable.ListMap
import scalaz._
import scalaz.concurrent._
import scalaz.stream._
import Scalaz._

import org.specs2.mutable._
import slamdata.specs2._

import argonaut._, Argonaut._

import dispatch._
import com.ning.http.client.{Response}

sealed trait Action
object Action {
  final case class Save(path: Path, rows: List[Data]) extends Action
  final case class Append(path: Path, rows: List[Data]) extends Action
  final case class Reload(cfg: Config) extends Action
}

class ApiSpecs extends Specification with DisjunctionMatchers with PendingWithAccurateCoverage {
  sequential  // Each test binds the same port
  args.report(showtimes = true)

  val port = 8888

  var historyBuff = collection.mutable.ListBuffer[Action]()
  def history = historyBuff.toList

 /**
  Start a server, with the given backend, execute something, and then tear
  down the server.
  */
  def withServer[A](backend: Backend, config: Config)(body: => A): A = {
    val srv = Server.run(port, FileSystemApi(backend, ".", config, cfg => Task.delay {
      historyBuff += Action.Reload(cfg)
      ()
    })).run

    try {
      body
    }
    finally {
      ignore(srv.shutdown.run)
      historyBuff.clear
    }
  }

  object Stub {
    case class Plan(description: String)
    implicit val PlanRenderTree = new RenderTree[Plan] {
      def render(v: Plan) = Terminal(List("Stub.Plan"), None)
    }

    lazy val planner = new Planner[Plan] {
      def plan(logical: Term[LogicalPlan]) = \/- (Plan("logical: " + logical.toString))
    }
    lazy val evaluator: Evaluator[Plan] = new Evaluator[Plan] {
      def execute(physical: Plan) = Task.now(ResultPath.Temp(Path("tmp/out")))
      def compile(physical: Plan) = "Stub" -> Cord(physical.toString)
      def checkCompatibility = ???
    }
    def showNative(plan: Plan): String = plan.toString

    def backend(files: Map[Path, List[Data]]): Backend = new PlannerBackend[Plan] {
      val planner = Stub.planner
      val evaluator = Stub.evaluator
      val RP = PlanRenderTree

      def scan0(path: Path, offset: Option[Long], limit: Option[Long]) =
        files.get(path).fold(
          Process.eval[Backend.PathTask, Data](EitherT.left(Task.now(NonexistentPathError(path, Some("no backend"))))))(
          Process.emitAll(_)
            .drop(offset.fold(0)(_.toInt))
            .take(limit.fold(Int.MaxValue)(_.toInt)))

      def count(path: Path) =
        EitherT(Task.now[PathError \/ List[Data]](files.get(path) \/> NonexistentPathError(path, Some("no backend")))).map(_.length.toLong)

      def save(path: Path, values: Process[Task, Data]) =
        if (path.pathname.contains("pathError"))
          EitherT.left(Task.now(InvalidPathError("simulated (client) error")))
        else if (path.pathname.contains("valueError"))
          Backend.liftP(Task.fail(WriteError(Data.Str(""), Some("simulated (value) error"))))
        else Backend.liftP(values.runLog.map { rows =>
          historyBuff += Action.Save(path, rows.toList)
          ()
        })

      def append(path: Path, values: Process[Task, Data]) =
        if (path.pathname.contains("pathError"))
          Process.eval[Backend.PathTask, WriteError](EitherT.left(Task.now(InvalidPathError("simulated (client) error"))))
        else if (path.pathname.contains("valueError"))
          Process.eval(WriteError(Data.Str(""), Some("simulated (value) error")).point[Backend.PathTask])
        else Process.eval_(Backend.liftP(values.runLog.map { rows =>
          historyBuff += Action.Append(path, rows.toList)
            ()
        }))

      def delete(path: Path) = ().point[Backend.PathTask]

      def move(src: Path, dst: Path) = ().point[Backend.PathTask]

      def ls(dir: Path): Backend.PathTask[Set[Backend.FilesystemNode]] = {
        val childrenOpt = files.keys.toList.map(_.rebase(dir).map(p => Backend.FilesystemNode(p.head, Backend.Plain))).sequenceU
        childrenOpt.fold(e => EitherT.left(Task.now(e)), _.toSet.point[Backend.PathTask])
      }

      def defaultPath = Path(".")
    }
  }

  /** Handler for response bodies containing newline-separated JSON documents, for use with Dispatch. */
  object asJson extends (Response => String \/ (String, List[Json])) {
    private def sequenceStrs[A](vs: Seq[String \/ A]): String \/ List[A] =
      vs.toList.map(_.validation.toValidationNel).sequenceU.leftMap(_.list.mkString("; ")).disjunction

    private def parseJsonLines(str: String): String \/ List[Json] =
      if (str == "") \/-(Nil)
      else sequenceStrs(str.split("\n").map(Parse.parse(_)))

    def apply(r: Response) =
      (dispatch.as.String andThen parseJsonLines)(r).map((r.getContentType, _))
  }

  def asLines(r: Response): (String, List[String]) = (r.getContentType, dispatch.as.String(r).split("\r\n").toList)


  /** Handlers for use with Dispatch. */
  val code: Response => Int = _.getStatusCode
  def header(name: String): Response => Option[String] = r => Option(r.getHeader(name))
  def commaSep: Option[String] => List[String] = _.fold(List[String]())(_.split(", ").toList)

  val svc = dispatch.host("localhost", port)

  val files1 = ListMap(
    Path("bar") -> List(
      Data.Obj(ListMap("a" -> Data.Int(1))),
      Data.Obj(ListMap("b" -> Data.Int(2))),
      Data.Obj(ListMap("c" -> Data.Set(List(Data.Int(3)))))),
    Path("dir/baz") -> List(),
    Path("tmp/out") -> List(Data.Obj(ListMap("0" -> Data.Str("ok")))),
    Path("tmp/dup") -> List(Data.Obj(ListMap("4" -> Data.Str("ok")))),
    Path("a file") -> List(Data.Obj(ListMap("1" -> Data.Str("ok")))),
    Path("quoting") -> List(
      Data.Obj(ListMap(
        "a" -> Data.Str("\"Hey\""),
        "b" -> Data.Str("a, b, c")))),
    Path("empty") -> List())
  val noBackends = NestedBackend(Map())
  val backends1 = NestedBackend(ListMap(
    Path("/empty/") -> Stub.backend(ListMap()),
    Path("/foo/") -> Stub.backend(files1),
    Path("/non/root/mounting/") -> Stub.backend(files1),
    Path("badPath1/") -> Stub.backend(ListMap()),
    Path("/badPath2") -> Stub.backend(ListMap())))

  val config1 = Config(SDServerConfig(Some(port)), ListMap(
    Path("/foo/") -> MongoDbConfig("mongodb://localhost/foo")))

  val corsMethods = header("Access-Control-Allow-Methods") andThen commaSep
  val corsHeaders = header("Access-Control-Allow-Headers") andThen commaSep

  "OPTIONS" should {
    val optionsRoot = svc.OPTIONS

    "advertise GET and POST for /query path" in {
      withServer(noBackends, config1) {
        val methods = Http(optionsRoot / "query" / "fs" / "" > corsMethods)

        methods() must contain(allOf("GET", "POST"))
      }
    }

    "advertise Destination header for /query path and method POST" in {
      withServer(noBackends, config1) {
        val headers = Http((optionsRoot / "query" / "fs" / "").setHeader("Access-Control-Request-Method", "POST") > corsHeaders)

        headers() must contain(allOf("Destination"))
      }
    }

    "advertise GET, PUT, POST, DELETE, and MOVE for /data path" in {
      withServer(noBackends, config1) {
        val methods = Http(optionsRoot / "data" / "fs" / "" > corsMethods)

        methods() must contain(allOf("GET", "PUT", "POST", "DELETE", "MOVE"))
      }
    }

    "advertise Destination header for /data path and method MOVE" in {
      withServer(noBackends, config1) {
        val headers = Http((optionsRoot / "data" / "fs" / "").setHeader("Access-Control-Request-Method", "MOVE") > corsHeaders)

        headers() must contain(allOf("Destination"))
      }
    }
  }

  val jsonContentType = "application/json"

  val preciseContentType = "application/ldjson; mode=\"precise\"; charset=UTF-8"
  val readableContentType = "application/ldjson; mode=\"readable\"; charset=UTF-8"
  val arrayContentType = "application/json; mode=\"readable\"; charset=UTF-8"
  val csvContentType = "text/csv"
  val charsetParam = "; charset=UTF-8"
  val csvResponseContentType = csvContentType + "; columnDelimiter=\",\"; rowDelimiter=\"\\\\r\\\\n\"; quoteChar=\"\\\"\"; escapeChar=\"\\\"\"" + charsetParam

  "/metadata/fs" should {
    val root = svc / "metadata" / "fs" / ""  // Note: trailing slash required

    "return no filesystems" in {
      withServer(noBackends, config1) {
        val meta = Http(root OK asJson)

        meta() must beRightDisj((jsonContentType, List(Json("children" := List[Json]()))))
      }
    }

    "be 404 with missing backend" in {
      withServer(noBackends, config1) {
        val path = root / "missing"
        val meta = Http(path > code)

        meta() must_== 404
      }
    }

    "return empty for null fs" in {
      withServer(backends1, config1) {
        val path = root / "empty" / ""
        val meta = Http(path OK asJson)

        meta() must beRightDisj((jsonContentType, List(Json("children" := List[Json]()))))
      }
    }

    "be 404 with missing path" in {
      withServer(backends1, config1) {
        val path = root / "foo" / "baz" / ""
        val meta = Http(path > code)

        meta() must_== 404
      }
    }

    "find stubbed filesystems" in {
      withServer(backends1, config1) {
        val meta = Http(root OK asJson)

        meta() must beRightDisj((
          jsonContentType,
          List(
            Json("children" := List(
              Json("name" := "badPath1", "type" := "mount"),
              Json("name" := "badPath2", "type" := "mount"),
              Json("name" := "empty",    "type" := "mount"),
              Json("name" := "foo",      "type" := "mount"),
              Json("name" := "non",      "type" := "directory"))))))
      }
    }

    "find stubbed files" in {
      withServer(backends1, config1) {
        val path = root / "foo" / ""
        val meta = Http(path OK asJson)

        meta() must beRightDisj((
          jsonContentType,
          List(
            Json("children" := List(
              Json("name" := "a file",  "type" := "file"),
              Json("name" := "bar",     "type" := "file"),
              Json("name" := "dir",     "type" := "directory"),
              Json("name" := "empty",   "type" := "file"),
              Json("name" := "quoting", "type" := "file"),
              Json("name" := "tmp",     "type" := "directory"))))))
      }
    }

    "find intermediate directory" in {
      withServer(backends1, config1) {
        val path = root / "non" / ""
        val meta = Http(path OK asJson)

        meta() must beRightDisj((
          jsonContentType,
          List(
            Json("children" := List(
              Json("name" := "root", "type" := "directory"))))))
      }
    }

    "find nested mount" in {
      withServer(backends1, config1) {
        val path = root / "non" / "root" / ""
        val meta = Http(path OK asJson)

        meta() must beRightDisj((
          jsonContentType,
          List(
            Json("children" := List(
              Json("name" := "mounting", "type" := "mount"))))))
      }
    }

    "be 404 for file with same name as existing directory (minus the trailing slash)" in {
      withServer(backends1, config1) {
        val path = root / "foo"
        val meta = Http(path > code)

        meta() must_== 404
      }
    }

    "be empty for file" in {
      withServer(backends1, config1) {
        val path = root / "foo" / "bar"
        val meta = Http(path OK asJson)

        meta() must beRightDisj((
          jsonContentType,
          List(
            Json())))
      }
    }

    "also contain CORS headers" in {
      withServer(noBackends, config1) {
        val methods = Http(root > corsMethods)

        methods() must contain(allOf("GET", "POST"))
      }
    }
  }

  "/data/fs" should {
    val root = svc / "data" / "fs" / ""

    "GET" should {
      "be 404 for missing backend" in {
        withServer(noBackends, config1) {
          val path = root / "missing"
          val meta = Http(path > code)

          meta() must_== 404
        }
      }

      "be 404 for missing file" in {
        withServer(backends1, config1) {
          val path = root / "empty" / "anything"
          val meta = Http(path > code)

          meta() must_== 404
        }
      }

      "read entire file readably by default" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar"
          val meta = Http(path OK asJson)

          meta() must beRightDisj((
            readableContentType,
            List(Json("a" := 1), Json("b" := 2), Json("c" := List(3)))))
        }
      }

      "read empty file" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "empty"
          val meta = Http(path OK asJson)

          meta() must beRightDisj((
            readableContentType,
            List()))
        }
      }

      "read entire file precisely when specified" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar"
          val meta = Http(path.setHeader("Accept", "application/ldjson;mode=precise") OK asJson)

          meta() must beRightDisj((
            preciseContentType,
            List(Json("a" := 1), Json("b" := 2), Json("c" := Json("$set" := List(3))))))
        }
      }

      "read entire file precisely with complicated Accept" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar"
          val meta = Http(path.setHeader("Accept", "application/ldjson;q=0.9;mode=readable,application/json;boundary=NL;mode=precise") OK asJson)

          meta() must beRightDisj((
            preciseContentType,
            List(Json("a" := 1), Json("b" := 2), Json("c" := Json("$set" := List(3))))))
        }
      }

      "read entire file in JSON array when specified" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").setHeader("Accept", "application/json")
          val meta = Http(req OK as.String)

          meta() must_==
            """[
               |{ "a": 1 },
               |{ "b": 2 },
               |{ "c": [ 3 ] }
               |]
               |""".stripMargin.replace("\n", "\r\n")
        }
      }

      "read entire file with gzip encoding" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").setHeader("Accept-Encoding", "gzip")
          val meta = Http(req)

          val resp = meta()
          resp.getStatusCode must_== 200
          resp.getHeader("Content-Encoding") must_== "gzip"
        }
      }

      "read entire file (with space)" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "a file"
          val meta = Http(path OK asJson)

          meta() must beRightDisj((readableContentType, List(Json("1" := "ok"))))
        }
      }

      "read entire file as CSV" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar"
          val meta = Http(path.setHeader("Accept", csvContentType) OK asLines)

          meta() must_==
            csvResponseContentType ->
            List("a,b,c[0]", "1,,", ",2,", ",,3")
        }
      }

      "read entire file as CSV with quoting" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "quoting"
          val meta = Http(path.setHeader("Accept", csvContentType) OK asLines)

          meta() must_==
            csvResponseContentType ->
            List("a,b", "\"\"\"Hey\"\"\",\"a, b, c\"")
        }
      }

      "read entire file as CSV with alternative delimiters" in {
        val mt = List(
          csvContentType,
          "columnDelimiter=\"\t\"",
          "rowDelimiter=\";\"",
          "quoteChar=\"'\"",  // NB: probably doesn't need quoting, but http4s renders it that way
          "escapeChar=\"\\\\\"").mkString("; ")

        withServer(backends1, config1) {
          val req = (root / "foo" / "bar")
                      .setHeader("Accept", mt)
          val meta = Http(req OK asLines)

          meta() must_==
            mt + charsetParam ->
            List("a\tb\tc[0];1\t\t;\t2\t;\t\t3;")
        }
      }

      "read entire file as CSV with standard delimiters specified" in {
        val mt = List(
          csvContentType,
          "columnDelimiter=\",\"",
          "rowDelimiter=\"\\\\r\\\\n\"",
          "quoteChar=\"\"",
          "escapeChar=\"\\\"\"").mkString("; ")
        println(s"mt: $mt")

        withServer(backends1, config1) {
          val req = (root / "foo" / "bar")
                      .setHeader("Accept", mt)
          val meta = Http(req OK asLines)

          meta() must_==
            csvResponseContentType ->
            List("a,b,c[0]", "1,,", ",2,", ",,3")
        }
      }

      "read partial file with offset and limit" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar" <<? Map("offset" -> "1", "limit" -> "1")
          val meta = Http(path OK asJson)

          meta() must beRightDisj((
            readableContentType,
            List(Json("b" := 2))))
        }
      }

      "be 400 with negative offset" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar" <<? Map("offset" -> "-10", "limit" -> "10")
          val meta = Http(path > code)

          meta() must_== 400
        }
      }

      "be 400 with negative limit" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar" <<? Map("offset" -> "10", "limit" -> "-10")
          val meta = Http(path > code)

          meta() must_== 400
        }
      }

      "be 400 with unparsable limit" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar" <<? Map("limit" -> "a")
          val meta = Http(path > code)

          meta() must_== 400
        }
      }.pendingUntilFixed("#773")
    }

    "PUT" should {
      "be 404 for missing backend" in {
        withServer(noBackends, config1) {
          val path = root / "missing"
          val meta = Http(path.PUT.setBody("{\"a\": 1}\n{\"b\": 2}") > code)

          meta() must_== 404
        }
      }

      "be 400 with no body" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar"
          val meta = Http(path.PUT > code)

          meta() must_== 400
        }
      }

      "be 400 with invalid JSON" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar"
          val meta = Http(path.PUT.setBody("{") > code)

          meta() must_== 400
        }
      }

      "accept valid (Precise) JSON" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar"
          val meta = Http(path.PUT.setBody("{\"a\": 1}\n{\"b\": \"12:34:56\"}") OK as.String)

          meta() must_== ""
          history must_== List(
            Action.Save(
              Path("./bar"),
              List(
                Data.Obj(ListMap("a" -> Data.Int(1))),
                Data.Obj(ListMap("b" -> Data.Str("12:34:56"))))))
        }
      }

      "accept valid (Readable) JSON" in {
        withServer(backends1, config1) {
          val path = (root / "foo" / "bar").setHeader("Content-Type", readableContentType)
          val meta = Http(path.PUT.setBody("{\"a\": 1}\n{\"b\": \"12:34:56\"}") OK as.String)

          meta() must_== ""
          history must_== List(
            Action.Save(
              Path("./bar"),
              List(
                Data.Obj(ListMap("a" -> Data.Int(1))),
                Data.Obj(ListMap("b" -> Data.Time(org.threeten.bp.LocalTime.parse("12:34:56")))))))
        }
      }

      "accept valid (standard) CSV" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").PUT
            .setHeader("Content-Type", csvContentType)
            .setBody("a,b\n1,\n,12:34:56")
          val meta = Http(req OK as.String)

          meta() must_== ""
          history must_== List(
            Action.Save(
              Path("./bar"),
              List(
                Data.Obj(ListMap("a" -> Data.Int(1))),
                Data.Obj(ListMap("b" -> Data.Time(org.threeten.bp.LocalTime.parse("12:34:56")))))))
        }
      }

      "accept valid (weird) CSV" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").PUT
            .setHeader("Content-Type", csvContentType)
            .setBody("a|b\n1|\n|'[1|2|3]'\n")
          val meta = Http(req OK as.String)

          meta() must_== ""
          history must_== List(
            Action.Save(
              Path("./bar"),
              List(
                Data.Obj(ListMap("a" -> Data.Int(1))),
                Data.Obj(ListMap("b" -> Data.Str("[1|2|3]"))))))
        }
      }

      "be 400 with empty CSV (no headers)" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").PUT
            .setHeader("Content-Type", csvContentType)
            .setBody("")
          val meta = Http(req > code)

          meta() must_== 400
          history must_== Nil
        }
      }

      "be 400 with broken CSV (after the tenth data line)" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").PUT
            .setHeader("Content-Type", csvContentType)
            .setBody("\"a\",\"b\"\n1,2\n3,4\n5,6\n7,8\n9,10\n11,12\n13,14\n15,16\n17,18\n19,20\n\",\n") // NB: missing quote char _after_ the tenth data row
          val meta = Http(req > code)

          meta() must_== 400
          history must_== Nil
        }
      }

      "be 400 with simulated path error" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "pathError"
          val meta = Http(path.PUT.setBody("{\"a\": 1}") > code)

          meta() must_== 400
        }
      }

      "be 500 with simulated error on a particular value" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "valueError"
          val meta = Http(path.PUT.setBody("{\"a\": 1}") > code)

          meta() must_== 500
        }
      }
    }

    "POST" should {
      "be 404 for missing backend" in {
        withServer(noBackends, config1) {
          val path = root / "missing"
          val meta = Http(path.POST.setBody("{\"a\": 1}\n{\"b\": 2}") > code)

          meta() must_== 404
        }
      }

      "be 400 with no body" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar"
          val meta = Http(path.POST > code)

          meta() must_== 400
        }
      }

      "be 400 with invalid JSON" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar"
          val meta = Http(path.POST.setBody("{") > code)

          meta() must_== 400
        }
      }

      "produce two errors with partially invalid JSON" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").POST.setBody(
            """{"a": 1}
              |"unmatched
              |{"b": 2}
              |}
              |{"c": 3}""".stripMargin)
          val meta = Http(req > asJson)

          meta() must beRightDisj { (resp: (String, List[Json])) =>
            val (_, json) = resp
            json.length == 1 &&
            (for {
              obj <- json.head.obj
              errors <- obj("errors")
              eArr <- errors.array
            } yield eArr.length == 2).getOrElse(false)
          }
        }
      }

      "accept valid (Precise) JSON" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").POST
            .setBody("{\"a\": 1}\n{\"b\": \"12:34:56\"}")
          val meta = Http(req OK as.String)

          meta() must_== ""
          history must_== List(
            Action.Append(
              Path("./bar"),
              List(
                Data.Obj(ListMap("a" -> Data.Int(1))),
                Data.Obj(ListMap("b" -> Data.Str("12:34:56"))))))
        }
      }

      "accept valid (Readable) JSON" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").POST
            .setHeader("Content-Type", readableContentType)
            .setBody("{\"a\": 1}\n{\"b\": \"12:34:56\"}")
          val meta = Http(req OK as.String)

          meta() must_== ""
          history must_== List(
            Action.Append(
              Path("./bar"),
              List(
                Data.Obj(ListMap("a" -> Data.Int(1))),
                Data.Obj(ListMap("b" -> Data.Time(org.threeten.bp.LocalTime.parse("12:34:56")))))))
        }
      }

      "accept valid (standard) CSV" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").POST
            .setHeader("Content-Type", csvContentType)
            .setBody("a,b\n1,\n,12:34:56")
          val meta = Http(req OK as.String)

          meta() must_== ""
          history must_== List(
            Action.Append(
              Path("./bar"),
              List(
                Data.Obj(ListMap("a" -> Data.Int(1))),
                Data.Obj(ListMap("b" -> Data.Time(org.threeten.bp.LocalTime.parse("12:34:56")))))))
        }
      }

      "accept valid (weird) CSV" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").POST
            .setHeader("Content-Type", csvContentType)
            .setBody("a|b\n1|\n|'[1|2|3]'")
          val meta = Http(req OK as.String)

          meta() must_== ""
          history must_== List(
            Action.Append(
              Path("./bar"),
              List(
                Data.Obj(ListMap("a" -> Data.Int(1))),
                Data.Obj(ListMap("b" -> Data.Str("[1|2|3]"))))))
        }
      }

      "be 400 with empty CSV (no headers)" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").POST
            .setHeader("Content-Type", csvContentType)
            .setBody("")
          val meta = Http(req > code)

          meta() must_== 400
          history must_== Nil
        }
      }

      "be 400 with broken CSV (after the tenth data line)" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "bar").POST
            .setHeader("Content-Type", csvContentType)
            .setBody("\"a\",\"b\"\n1,2\n3,4\n5,6\n7,8\n9,10\n11,12\n13,14\n15,16\n17,18\n19,20\n\",\n") // NB: missing quote char _after_ the tenth data row
          val meta = Http(req > code)

          meta() must_== 400
          history must_== Nil
        }
      }

      "be 400 with simulated path error" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "pathError"
          val meta = Http(path.POST.setBody("{\"a\": 1}") > code)

          meta() must_== 400
        }
      }

      "be 500 with simulated error on a particular value" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "valueError"
          val meta = Http(path.POST.setBody("{\"a\": 1}") > code)

          meta() must_== 500
        }
      }
    }

    "MOVE" should {
      val moveRoot = root.setMethod("MOVE")

      "be 400 for missing src backend" in {
        withServer(noBackends, config1) {
          val req = moveRoot / "foo"
          val meta = Http(req > code)

          meta() must_== 400
        }
      }

      "be 404 for missing source file" in {
        withServer(backends1, config1) {
          val req = (moveRoot / "missing" / "a" ).setHeader("Destination", "/foo/bar")
          val meta = Http(req > code)

          meta() must_== 404
        }
      }

      "be 404 for missing dst backend" in {
        withServer(backends1, config1) {
          val req = (moveRoot / "foo" / "bar").setHeader("Destination", "/missing/a")
          val meta = Http(req > code)

          meta() must_== 404
        }
      }

      "be 201 for file" in {
        withServer(backends1, config1) {
          val req = (moveRoot / "foo" / "bar").setHeader("Destination", "/foo/baz")
          val meta = Http(req > code)

          meta() must_== 201
        }
      }

      "be 201 for dir" in {
        withServer(backends1, config1) {
          val req = (moveRoot / "foo" / "dir" / "").setHeader("Destination", "/foo/dir2/")
          val meta = Http(req > code)

          meta() must_== 201
        }
      }

      "be 501 for src and dst not in same backend" in {
        withServer(backends1, config1) {
          val req = (moveRoot / "foo" / "bar").setHeader("Destination", "/empty/a")
          val meta = Http(req > code)

          meta() must_== 501
        }
      }

    }

    "DELETE" should {
      "be 404 for missing backend" in {
        withServer(noBackends, config1) {
          val path = root / "missing"
          val meta = Http(path.DELETE > code)

          meta() must_== 404
        }
      }

      "be 200 with existing file" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "bar"
          val meta = Http(path.DELETE > code)

          meta() must_== 200
        }
      }

      "be 200 with existing dir" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "dir" / ""
          val meta = Http(path.DELETE > code)

          meta() must_== 200
        }
      }

      "be 200 with missing file (idempotency)" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "missing"
          val meta = Http(path.DELETE > code)

          meta() must_== 200
        }
      }

      "be 200 with missing dir (idempotency)" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "missingDir" / ""
          val meta = Http(path.DELETE > code)

          meta() must_== 200
        }
      }
    }
  }

  "/query/fs" should {
    val root = svc / "query" / "fs" / ""

    "GET" should {
      "be 404 for missing backend" in {
        withServer(noBackends, config1) {
          val path = root / "missing" <<? Map("q" -> "select * from bar")
          val meta = Http(path > code)

          meta() must_== 404
        }
      }.pendingUntilFixed("#771")

      "be 400 for missing query" in {
        withServer(backends1, config1) {
          val path = root / "foo" / ""
          val result = Http(path > code)

          result() must_== 400
        }
      }

      "execute simple query" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "" <<? Map("q" -> "select * from bar")
          val result = Http(path OK asJson)

          result() must beRightDisj((
            readableContentType,
            List(Json("0" := "ok"))))
        }
      }

      "be 400 for query error" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "" <<? Map("q" -> "select date where")
          val result = Http(path > code)

          result() must_== 400
        }
      }
    }

    "POST" should {
      "be 404 with missing backend" in {
        withServer(noBackends, config1) {
          val req = (root / "missing" / "").POST.setBody("select * from bar").setHeader("Destination", "/tmp/gen0")

          val result = Http(req > code)

          result() must_== 404
        }
      }.pendingUntilFixed("#771")

      "be 400 with missing query" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "").POST.setHeader("Destination", "/foo/tmp/gen0")

          val result = Http(req > code)

          result() must_== 400
        }
      }

      "be 400 with missing Destination header" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "").POST.setBody("select * from bar")

          val result = Http(req > code)

          result() must_== 400
        }
      }

      "execute simple query" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "").POST.setBody("select * from bar").setHeader("Destination", "/foo/tmp/gen0")

          val result = Http(req > code)

          result() must_== 200
        }
      }
    }
  }

  "/compile/fs" should {
    val root = svc / "compile" / "fs" / ""

    "GET" should {
      "be 404 with missing backend" in {
        withServer(noBackends, config1) {
          val req = root / "missing" / "" <<? Map("q" -> "select * from bar")
          val result = Http(req > code)

          result() must_== 404
        }
      }.pendingUntilFixed("#771")

      "be 400 with missing query" in {
        withServer(backends1, config1) {
          val req = root / "foo" / ""

          val result = Http(req > code)

          result() must_== 400
        }
      }

      "plan simple query" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "" <<? Map("q" -> "select * from bar")
          val result = Http(path OK as.String)

          result() must_== "Stub\nPlan(logical: Squash(Read(Path(\"bar\"))))"
        }
      }

      "be 400 for query error" in {
        withServer(backends1, config1) {
          val path = root / "foo" / "" <<? Map("q" -> "select date where")
          val result = Http(path > code)

          result() must_== 400
        }
      }
    }

    "POST" should {
      "be 404 with missing backend" in {
        withServer(noBackends, config1) {
          val req = (root / "missing" / "").POST.setBody("select * from bar")
          val result = Http(req > code)

          result() must_== 404
        }
      }.pendingUntilFixed("#771")

      "be 400 with missing query" in {
        withServer(backends1, config1) {
          val req = (root / "foo" / "").POST

          val result = Http(req > code)

          result() must_== 400
        }
      }

      "plan simple query" in {
        withServer(backends1, config1) {
          val path = (root / "foo" / "").POST.setBody("select * from bar")
          val result = Http(path OK as.String)

          result() must_== "Stub\nPlan(logical: Squash(Read(Path(\"bar\"))))"
        }
      }

      "be 400 for query error" in {
        withServer(backends1, config1) {
          val path = (root / "foo" / "").POST.setBody("select date where")
          val result = Http(path > code)

          result() must_== 400
        }
      }
    }
  }

  "/mount/fs" should {
    val root = svc / "mount" / "fs" / ""

    "GET" should {
      "be 404 with missing mount" in {
        withServer(noBackends, config1) {
          val req = root / "missing" / ""
          val result = Http(req > code)

          result() must_== 404
        }
      }

      "succeed with correct path" in {
        withServer(noBackends, config1) {
          val req = root / "foo" / ""
          val result = Http(req OK asJson)

          result() must beRightDisj((
            jsonContentType,
            List(Json("mongodb" := Json("connectionUri" := "mongodb://localhost/foo")))))
        }
      }

      "be 404 with missing trailing slash" in {
        withServer(noBackends, config1) {
          val req = root / "foo"
          val result = Http(req > code)

          result() must_== 404
        }
      }
    }

    "MOVE" should {
      "succeed with valid paths" in {
        withServer(noBackends, config1) {
          val req = (root / "foo" / "")
                    .setMethod("MOVE")
                    .setHeader("Destination", "/foo2/")
          val result = Http(req OK as.String)

          result() must_== "moved /foo/ to /foo2/"
          history must_== List(Action.Reload(Config(SDServerConfig(Some(port)), Map(
            Path("/foo2/") -> MongoDbConfig("mongodb://localhost/foo")))))
        }
      }

      "be 404 with missing source" in {
        withServer(noBackends, config1) {
          val req = (root / "missing" / "")
                    .setMethod("MOVE")
                    .setHeader("Destination", "/foo/")
          val result = Http(req > code)

          result() must_== 404
          history must_== Nil
        }
      }

      "be 400 with missing destination" in {
        withServer(noBackends, config1) {
          val req = (root / "foo" / "")
                    .setMethod("MOVE")
          val result = Http(req > code)

          result() must_== 400
          history must_== Nil
        }
      }

      "be 400 with relative path" in {
        withServer(noBackends, config1) {
          val req = (root / "foo" / "")
                    .setMethod("MOVE")
                    .setHeader("Destination", "foo2/")
          val result = Http(req > code)

          result() must_== 400
          history must_== Nil
        }
      }

      "be 400 with non-directory path for MongoDB mount" in {
        withServer(noBackends, config1) {
          val req = (root / "foo" / "")
                    .setMethod("MOVE")
                    .setHeader("Destination", "/foo2")
          val result = Http(req > code)

          result() must_== 400
          history must_== Nil
        }
      }
    }

    "POST" should {
      "succeed with valid MongoDB config" in {
        withServer(noBackends, config1) {
          val req = root.POST
                    .setHeader("X-File-Name", "local/")
                    .setBody("""{ "mongodb": { "connectionUri": "mongodb://localhost/test" } }""")
          val result = Http(req OK as.String)

          result() must_== "added /local/"
          history must_== List(Action.Reload(Config(SDServerConfig(Some(port)), Map(
            Path("/foo/") -> MongoDbConfig("mongodb://localhost/foo"),
            Path("/local/") -> MongoDbConfig("mongodb://localhost/test")))))
        }
      }

      "be 409 with existing path" in {
        withServer(noBackends, config1) {
          val req = root.POST
                    .setHeader("X-File-Name", "foo/")
                    .setBody("""{ "mongodb": { "connectionUri": "mongodb://localhost/foo2" } }""")
          val result = Http(req > code)

          result() must_== 409
          history must_== Nil
        }
      }

      "be 400 with missing file-name" in {
        withServer(noBackends, config1) {
          val req = root.POST
                    .setBody("""{ "mongodb": { "connectionUri": "mongodb://localhost/test" } }""")
          val result = Http(req > code)

          result() must_== 400
          history must_== Nil
        }
      }

      "be 400 with invalid MongoDB path (no trailing slash)" in {
        withServer(noBackends, config1) {
          val req = root.POST
                    .setHeader("X-File-Name", "local")
                    .setBody("""{ "mongodb": { "connectionUri": "mongodb://localhost/test" } }""")
          val result = Http(req > code)

          result() must_== 400
          history must_== Nil
        }
      }

      "be 400 with invalid JSON" in {
        withServer(noBackends, config1) {
          val req = root.POST
                    .setHeader("X-File-Name", "local/")
                    .setBody("""{ "mongodb":""")
          val result = Http(req > code)

          result() must_== 400
          history must_== Nil
        }
      }

      "be 400 with invalid MongoDB URI (extra slash)" in {
        withServer(noBackends, config1) {
          val req = root.POST
                    .setHeader("X-File-Name", "local/")
                    .setBody("""{ "mongodb": { "connectionUri": "mongodb://localhost:8080//test" } }""")
          val result = Http(req > code)

          result() must_== 400
          history must_== Nil
        }
      }
    }

    "PUT" should {
      "succeed with valid MongoDB config" in {
        withServer(noBackends, config1) {
          val req = (root / "local" / "").PUT
                    .setBody("""{ "mongodb": { "connectionUri": "mongodb://localhost/test" } }""")
          val result = Http(req OK as.String)

          result() must_== "added /local/"
          history must_== List(Action.Reload(Config(SDServerConfig(Some(port)), Map(
            Path("/foo/") -> MongoDbConfig("mongodb://localhost/foo"),
            Path("/local/") -> MongoDbConfig("mongodb://localhost/test")))))
        }
      }

      "succeed with valid, overwritten MongoDB config" in {
        withServer(noBackends, config1) {
          val req = (root / "foo" / "").PUT
                    .setBody("""{ "mongodb": { "connectionUri": "mongodb://localhost/foo2" } }""")
          val result = Http(req OK as.String)

          result() must_== "updated /foo/"
          history must_== List(Action.Reload(Config(SDServerConfig(Some(port)), Map(
            Path("/foo/") -> MongoDbConfig("mongodb://localhost/foo2")))))
        }
      }

      "be 400 with invalid MongoDB path (no trailing slash)" in {
        withServer(noBackends, config1) {
          val req = (root / "local").PUT
                    .setBody("""{ "mongodb": { "connectionUri": "mongodb://localhost/test" } }""")
          val result = Http(req > code)

          result() must_== 400
          history must_== Nil
        }
      }

      "be 400 with invalid JSON" in {
        withServer(noBackends, config1) {
          val req = (root / "local" / "").PUT
                    .setBody("""{ "mongodb":""")
          val result = Http(req > code)

          result() must_== 400
          history must_== Nil
        }
      }

      "be 400 with invalid MongoDB URI (extra slash)" in {
        withServer(noBackends, config1) {
          val req = (root / "local" / "").PUT
                    .setBody("""{ "mongodb": { "connectionUri": "mongodb://localhost:8080//test" } }""")
          val result = Http(req > code)

          result() must_== 400
          history must_== Nil
        }
      }
    }

    "DELETE" should {
      "succeed with correct path" in {
        withServer(noBackends, config1) {
          val req = (root / "foo" / "").DELETE
          val result = Http(req OK as.String)

          result() must_== "deleted /foo/"
          history must_== List(Action.Reload(Config(SDServerConfig(Some(port)), Map())))
        }
      }

      "succeed with missing path (no action)" in {
        withServer(noBackends, config1) {
          val req = (root / "missing" / "").DELETE
          val result = Http(req OK as.String)

          result() must_== ""
          history must_== Nil
        }
      }
    }
  }

  step {
    // Explicitly close dispatch's executor, since it no longer detects running in SBT properly.
    Http.shutdown
  }
}

class ResponseFormatSpecs extends Specification {
  import org.http4s._, QValue._
  import org.http4s.headers.{Accept}

  import ResponseFormat._

  "fromAccept" should {
    "be Readable by default" in {
      fromAccept(None) must_== JsonStream.Readable
    }

    "choose precise" in {
      val accept = Accept(
        new MediaType("application", "ldjson").withExtensions(Map("mode" -> "precise")))
      fromAccept(Some(accept)) must_== JsonStream.Precise
    }

    "choose streaming via boundary extension" in {
      val accept = Accept(
        new MediaType("application", "json").withExtensions(Map("boundary" -> "NL")))
      fromAccept(Some(accept)) must_== JsonStream.Readable
    }

    "choose precise list" in {
      val accept = Accept(
        new MediaType("application", "json").withExtensions(Map("mode" -> "precise")))
      fromAccept(Some(accept)) must_== JsonArray.Precise
    }

    "choose streaming and precise via extensions" in {
      val accept = Accept(
        new MediaType("application", "json").withExtensions(Map("mode" -> "precise", "boundary" -> "NL")))
      fromAccept(Some(accept)) must_== JsonStream.Precise
    }

    "choose CSV" in {
      val accept = Accept(
        new MediaType("text", "csv"))
      fromAccept(Some(accept)) must_== Csv.Default
    }

    "choose CSV with custom format" in {
      val accept = Accept(
        new MediaType("text", "csv").withExtensions(Map(
          "columnDelimiter" -> "\t",
          "rowDelimiter" -> ";",
          "quoteChar" -> "'",
          "escapeChar" -> "\\")))
      fromAccept(Some(accept)) must_== Csv('\t', ";", '\'', '\\')
    }

    "choose CSV over JSON" in {
      val accept = Accept(
        new MediaType("text", "csv").withQValue(q(1.0)),
        new MediaType("application", "ldjson").withQValue(q(0.9)))
      fromAccept(Some(accept)) must_== Csv.Default
    }

    "choose JSON over CSV" in {
      val accept = Accept(
        new MediaType("text", "csv").withQValue(q(0.9)),
        new MediaType("application", "ldjson"))
      fromAccept(Some(accept)) must_== JsonStream.Readable
    }
  }

  "Csv.escapeNewlines" should {
    """escape \r\n""" in {
      Csv.escapeNewlines("\r\n") must_== """\r\n"""
    }

    """not affect \"""" in {
      Csv.escapeNewlines("\\\"") must_== "\\\""
    }
  }

  "Csv.unescapeNewlines" should {
    """unescape \r\n""" in {
      Csv.unescapeNewlines("""\r\n""") must_== "\r\n"
    }

    """not affect \"""" in {
      Csv.escapeNewlines("""\"""") must_== """\""""
    }
  }
}
