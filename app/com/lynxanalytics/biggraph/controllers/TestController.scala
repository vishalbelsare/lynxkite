package com.lynxanalytics.biggraph.controllers

import com.lynxanalytics.biggraph.graph_api
import com.lynxanalytics.biggraph.serving

/**
 * Case classes used by the JsonServer to communicate with the web application
 */

case class TestPostRequest(attr: String)
case class TestPostResponse(attr: String)

object TestController extends serving.PrettyContoller[TestPostRequest, TestPostResponse] {
  def process(request: TestPostRequest): TestPostResponse = {
    TestPostResponse("POST test string: " + request.attr)
  }
}
