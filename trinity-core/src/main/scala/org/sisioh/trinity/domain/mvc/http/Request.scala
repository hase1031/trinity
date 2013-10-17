package org.sisioh.trinity.domain.mvc.http

import com.google.common.base.Splitter
import com.twitter.finagle.http.ParamMap
import com.twitter.finagle.http.{Request => FinagleRequest}
import java.net.{InetAddress, InetSocketAddress}
import org.sisioh.trinity.domain.io.transport.codec.http.{Request => IORequest, _}
import org.sisioh.trinity.domain.mvc.GlobalSettings
import org.sisioh.trinity.domain.mvc.action.Action
import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.util.Sorting

trait Request extends Message with RequestProxy {

  val finagle = FinagleRequest(netty)

  val actionOpt: Option[Action[Request, Response]]

  def withAction(action: Option[Action[Request, Response]]): this.type

  def encodeBytes: Array[Byte]

  def encodeString: String

  def remoteSocketAddress: InetSocketAddress

  def remoteHost: String =
    remoteAddress.getHostAddress

  def remoteAddress: InetAddress =
    remoteSocketAddress.getAddress

  def remotePort: Int =
    remoteSocketAddress.getPort

  def params: ParamMap

  def containsParam(name: String): Boolean

  val routeParams: Map[String, String]

  def withRouteParams(routeParams: Map[String, String]): this.type

  val multiParams: Map[String, MultiPartItem]

  def accepts: Seq[ContentType] = {
    val acceptOpt = Option(getHeader("Accept"))
    acceptOpt.map {
      accept =>
        val acceptParts = Splitter.on(',').split(accept).toArray
        Sorting.quickSort(acceptParts)(AcceptOrdering)
        acceptParts.map {
          xs =>
            val part = Splitter.on(";q=").split(xs).toArray.head
            ContentType.valueOf(part).getOrElse(ContentType.All)
        }.toSeq
    }.getOrElse(Seq.empty[ContentType])
  }

  def path: String

  def fileExtension: String


  val errorOpt: Option[Throwable]

  def withError(error: Throwable): this.type

  def execute(defaultAction: Action[Request, Response]): Future[Response] = actionOpt.map(_(this)).getOrElse(defaultAction(this))

  val globalSettingsOpt: Option[GlobalSettings[Request, Response]]

}

object Request {

  def fromUnderlying(underlying: IORequest,
                     action: Option[Action[Request, Response]] = None,
                     routeParams: Map[String, String] = Map.empty,
                     globalSettingsOpt: Option[GlobalSettings[Request, Response]] = None,
                     errorOpt: Option[Throwable] = None): Request =
    new RequestImpl(underlying, action, routeParams, globalSettingsOpt, errorOpt)

  def apply(method: Method.Value = Method.Get,
            uri: String = "/",
            action: Option[Action[Request, Response]] = None,
            routeParams: Map[String, String] = Map.empty,
            globalSettingsOpt: Option[GlobalSettings[Request, Response]] = None,
            errorOpt: Option[Throwable] = None,
            version: Version.Value = Version.Http11): Request =
    new RequestImpl(method, uri, action, routeParams, globalSettingsOpt, errorOpt, version)

}
