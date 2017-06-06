package com.lynxanalytics.biggraph.controllers

import com.lynxanalytics.biggraph.frontend_operations.OperationsTestBase
import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_operations.DynamicValue
import com.lynxanalytics.biggraph.graph_util
import com.lynxanalytics.biggraph.serving

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class SQLControllerTest extends BigGraphControllerTestBase with OperationsTestBase {
  override val user = serving.User.fake
  val sqlController = new SQLController(this, ops = null)
  val resourceDir = getClass.getResource("/graph_operations/ImportGraphTest").toString
  graph_util.PrefixRepository.registerPrefix("IMPORTGRAPHTEST$", resourceDir)

  def await[T](f: concurrent.Future[T]): T =
    concurrent.Await.result(f, concurrent.duration.Duration.Inf)

  private def SQLResultToStrings(data: List[List[DynamicValue]]) = {
    data.map { case l: List[DynamicValue] => l.map { dv => dv.string } }
  }

  // TODO: Depends on #5875.

  test("global sql on vertices") {
    val eg = box("Create example graph")
    eg.snapshotOutput("test_dir/people", "project")

    val result = await(sqlController.runSQLQuery(user, SQLQueryRequest(
      DataFrameSpec.global(directory = "test_dir",
        sql = "select name from `people|vertices` where age < 40"),
      maxRows = 10)))

    assert(result.header == List(SQLColumn("name", "String")))
    val resultStrings = SQLResultToStrings(result.data)
    assert(resultStrings == List(List("Adam"), List("Eve"), List("Isolated Joe")))
  }

  test("global sql on vertices from root directory") {
    val eg = box("Create example graph")
    eg.snapshotOutput("test_dir/people", "project")

    val result = await(sqlController.runSQLQuery(user, SQLQueryRequest(
      DataFrameSpec.global(directory = "",
        sql = "select name from `test_dir/people|vertices` where age < 40"),
      maxRows = 10)))

    assert(result.header == List(SQLColumn("name", "String")))
    val resultStrings = SQLResultToStrings(result.data)
    assert(resultStrings == List(List("Adam"), List("Eve"), List("Isolated Joe")))
  }

  test("global sql on vertices with attribute name quoted with backticks") {
    val eg = box("Create example graph")
    eg.snapshotOutput("test_dir/people", "project")
    val result = await(sqlController.runSQLQuery(user, SQLQueryRequest(
      DataFrameSpec.global(directory = "test_dir",
        sql = "select `name` from `people|vertices` where age < 40"),
      maxRows = 10)))

    assert(result.header == List(SQLColumn("name", "String")))
    val resultStrings = SQLResultToStrings(result.data)
    assert(resultStrings == List(List("Adam"), List("Eve"), List("Isolated Joe")))
  }

  test("global sql on segmentation") {
    val egSeg = box("Create example graph").box("Segment by String attribute",
      Map("name" -> "gender_seg", "attr" -> "gender"))
    egSeg.snapshotOutput("test_dir/people", "project")

    val result = await(sqlController.runSQLQuery(user, SQLQueryRequest(
      DataFrameSpec.global(directory = "test_dir",
        sql = "select gender from `people|gender_seg|vertices`"),
      maxRows = 10)))

    assert(result.header == List(SQLColumn("gender", "String")))
    val resultStrings = SQLResultToStrings(result.data)
    assert(resultStrings == List(List("Male"), List("Female")))
  }

  // TODO: Depends on #5731.
  /*
  test("sql export to csv") {
    run("Create example graph")
    val result = await(sqlController.exportSQLQueryToCSV(user, SQLExportToCSVRequest(
      DataFrameSpec.local(
        project = projectName,
        sql = "select name, age from vertices where age < 40"),
      path = "<download>",
      delimiter = ";",
      quote = "\"",
      header = true)))
    val output = graph_util.HadoopFile(result.download.get.path).loadTextFile(sparkContext)
    val header = "name;age"
    assert(output.collect.filter(_ != header).sorted.mkString(", ") ==
      "Adam;20.3, Eve;18.2, Isolated Joe;2.0")
  }

  test("sql export to database") {
    val url = s"jdbc:sqlite:${dataManager.repositoryPath.resolvedNameWithNoCredentials}/test-db"
    run("Create example graph")
    val connection = graph_util.JDBCUtil.getConnection(url)
    val result = await(sqlController.exportSQLQueryToJdbc(user, SQLExportToJdbcRequest(
      DataFrameSpec.local(
        project = projectName,
        sql = "select name, age from vertices where age < 40 order by name"),
      jdbcUrl = url,
      table = "export_test",
      mode = "error")))
    val statement = connection.createStatement()
    val results = {
      val rs = statement.executeQuery("select * from export_test;")
      new Iterator[String] {
        def hasNext = rs.next
        def next = s"${rs.getString(1)};${rs.getDouble(2)}"
      }.toIndexedSeq
    }
    connection.close()
    assert(results.sorted == Seq("Adam;20.3", "Eve;18.2", "Isolated Joe;2.0"))
  }

  test("sql export to parquet + import back (including tuple columns)") {
    val exportPath = "IMPORTGRAPHTEST$/example.parquet"
    graph_util.HadoopFile(exportPath).deleteIfExists

    run("Create example graph")
    val result = await(
      sqlController.exportSQLQueryToParquet(
        user,
        SQLExportToParquetRequest(
          DataFrameSpec.local(
            project = projectName,
            sql = "select name, age, location from vertices"),
          path = exportPath)))
    val response = sqlController.importParquet(
      user,
      ParquetImportRequest(
        table = "csv-import-test",
        privacy = "public-read",
        files = exportPath + "/part*",
        overwrite = false,
        columnsToImport = List("name", "location"),
        limit = None))
    val tablePath = response.id
    run(
      "Import vertices",
      Map(
        "table" -> tablePath,
        "id_attr" -> "new_id"))

    assert(vattr[String]("name") == Seq("Adam", "Bob", "Eve", "Isolated Joe"))
    assert(vattr[(Double, Double)]("location") == Seq(
      (-33.8674869, 151.2069902),
      (1.352083, 103.819836),
      (40.71448, -74.00598),
      (47.5269674, 19.0323968)))
    graph_util.HadoopFile(exportPath).delete
  }
  */

  // TODO: Depends on https://app.asana.com/0/194476945034319/354006072569797.
  /*
  test("list project tables") {
    createProject(name = "example1")
    createDirectory(name = "dir")
    createProject(name = "dir/example2")
    run("Create example graph", on = "dir/example2")
    run(
      "Segment by Double attribute",
      params = Map(
        "name" -> "bucketing",
        "attr" -> "age",
        "interval_size" -> "0.1",
        "overlap" -> "no"),
      on = "dir/example2")
    run(
      "Segment by Double attribute",
      params = Map(
        "name" -> "vertices", // This segmentation is named vertices to test extremes.
        "attr" -> "age",
        "interval_size" -> "0.1",
        "overlap" -> "no"),
      on = "dir/example2")

    // List tables and segmentation of a project.
    val res1 = await(
      sqlController.getTableBrowserNodes(
        user, TableBrowserNodeRequest(path = "dir/example2")))
    assert(List(
      TableBrowserNode("dir/example2|edges", "edges", "table"),
      TableBrowserNode("dir/example2|edge_attributes", "edge_attributes", "table"),
      TableBrowserNode("dir/example2|vertices", "vertices", "table"),
      TableBrowserNode("dir/example2|bucketing", "bucketing", "segmentation"),
      TableBrowserNode("dir/example2|vertices", "vertices", "segmentation")) == res1.list)
  }

  def checkExampleGraphColumns(req: TableBrowserNodeRequest, idTypeOverride: String = "ID") = {
    val res = await(sqlController.getTableBrowserNodes(user, req))
    val expected = List(
      TableBrowserNode("", "age", "column", "Double"),
      TableBrowserNode("", "income", "column", "Double"),
      TableBrowserNode("", "id", "column", idTypeOverride),
      TableBrowserNode("", "location", "column", "(Double, Double)"),
      TableBrowserNode("", "name", "column", "String"),
      TableBrowserNode("", "gender", "column", "String"))

    assert(expected.sortBy(_.name) == res.list.sortBy(_.name))
  }

  test("list project table columns") {
    run("Create example graph")
    checkExampleGraphColumns(
      TableBrowserNodeRequest(
        path = "Test_Project|vertices",
        isImplicitTable = true))
  }

  test("list view columns") {
    run("Create example graph")
    sqlController.createViewDFSpec(
      user,
      SQLCreateViewRequest(
        name = "view1",
        privacy = "public-write",
        overwrite = false,
        dfSpec = DataFrameSpec(
          directory = Some(""),
          project = None,
          sql = "SELECT * FROM `Test_Project|vertices`")))

    // Check that columns of view are listed:
    checkExampleGraphColumns(
      TableBrowserNodeRequest(path = "view1"),
      idTypeOverride = "Long")
  }

  test("list table columns") {
    run("Create example graph")
    await(sqlController.exportSQLQueryToTable(
      user,
      SQLExportToTableRequest(
        table = "table1",
        privacy = "public-read",
        overwrite = false,
        dfSpec = DataFrameSpec(
          directory = Some(""),
          project = None,
          sql = "SELECT * FROM `Test_Project|vertices`"))))

    // Check that columns of view are listed:
    checkExampleGraphColumns(
      TableBrowserNodeRequest(path = "table1"),
      idTypeOverride = "Long")
  }
  */
}
