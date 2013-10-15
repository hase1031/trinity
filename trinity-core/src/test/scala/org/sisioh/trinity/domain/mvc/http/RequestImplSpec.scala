package org.sisioh.trinity.domain.mvc.http

import org.specs2.mutable.Specification
import org.sisioh.trinity.domain.io.transport.codec.http.{Version, Method}
import org.sisioh.trinity.domain.mvc.action.SimpleAction
import scala.concurrent.Future

class RequestImplSpec extends Specification {

  "request" should {
    "has action as some" in {
      val method = Method.Get
      val uri = "/"
      val actionOpt = Some(new SimpleAction {
        def apply(request: Request): Future[Response] = ???
      })
      val routeParams = Map.empty[String, String]
      val globalSettingsOpt = None
      val errorOpt = None
      val version = Version.Http11

      val request =  new RequestImpl(method, uri, actionOpt, routeParams, globalSettingsOpt, errorOpt, version)
      request.actionOpt must beSome
    }
  }

}