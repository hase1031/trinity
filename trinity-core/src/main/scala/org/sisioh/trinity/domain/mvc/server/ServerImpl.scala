/*
 * Copyright 2013 Sisioh Project and others. (http://sisioh.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.sisioh.trinity.domain.mvc.server

import com.twitter.finagle.CodecFactory
import com.twitter.finagle.builder.ServerBuilder
import com.twitter.finagle.builder.{Server => FinagleServer}
import com.twitter.finagle.http.Http
import com.twitter.finagle.http.RichHttp
import com.twitter.finagle.http.{Request => FinagleRequest, Response => FinagleResponse}
import com.twitter.finagle.tracing.{NullTracer, Tracer}
import com.twitter.ostrich.admin._
import org.sisioh.scala.toolbox.LoggingEx
import org.sisioh.trinity.domain.mvc.action.Action
import org.sisioh.trinity.domain.mvc.filter.Filter
import org.sisioh.trinity.domain.mvc.http.{Response, Request}
import org.sisioh.trinity.domain.mvc.{Environment, GlobalSettings}
import org.sisioh.trinity.util.FutureConverters._
import scala.concurrent._

private[mvc]
class ServerImpl
(val serverConfig: ServerConfig,
 val action: Option[Action[Request, Response]],
 val filter: Option[Filter[Request, Response, Request, Response]],
 val globalSettings: Option[GlobalSettings[Request, Response]])
(implicit executor: ExecutionContext)
  extends Server with LoggingEx {

  private var finagleServer: Option[FinagleServer] = None

  protected def createTracer: Tracer = NullTracer

  protected def createRuntimeEnviroment: RuntimeEnvironment = new RuntimeEnvironment(this)

  private val defaultAdminHttpServicePort = 9990

  filter.foreach(registerFilter)

  /**
   * [[com.twitter.ostrich.admin.AdminHttpService]] を生成する。
   *
   * @param runtimeEnv [[com.twitter.ostrich.admin.RuntimeEnvironment]]
   * @return [[com.twitter.ostrich.admin.AdminHttpService]]
   */
  protected def createAdminHttpService(runtimeEnv: RuntimeEnvironment): AdminHttpService = withDebugScope("createAdminService") {
    val httpPort = serverConfig.statsPort.getOrElse(defaultAdminHttpServicePort)
    val serviceName = serverConfig.name
    scopedDebug(s"startPort = $httpPort, serviceName = $serviceName")
    AdminServiceFactory(
      httpPort,
      statsNodes = StatsFactory(
        reporters = JsonStatsLoggerFactory(serviceName = serverConfig.name) :: TimeSeriesCollectorFactory() :: Nil
      ) :: Nil
    )(runtimeEnv)
  }


  /**
   * コーデックを生成する。
   *
   * @return [[com.twitter.finagle.CodecFactory]]
   */
  protected def createCodec: CodecFactory[FinagleRequest, FinagleResponse] = {
    import com.twitter.conversions.storage._
    val http = Http()
    serverConfig.maxRequestSize.foreach {
      v =>
        http.maxRequestSize(v.megabytes)
    }
    serverConfig.maxResponseSize.foreach {
      v =>
        http.maxResponseSize(v.megabytes)
    }
    RichHttp[FinagleRequest](http)
  }


  def start(environment: Environment.Value = Environment.Development)
           (implicit executor: ExecutionContext): Future[Unit] = future {
    withDebugScope("start") {
      require(finagleServer.isEmpty)
      info(s"aciton = $action, routingFilter = $filter")
      if (serverConfig.statsEnabled) {
        createAdminHttpService(createRuntimeEnviroment)
      }
      val service = buildService(environment, action)
      val bindAddress = serverConfig.bindAddress.getOrElse(Server.defaultBindAddress)
      scopedDebug(s"bindAddress = $bindAddress")
      val name = serverConfig.name.getOrElse(Server.defaultName)
      scopedDebug(s"name = $name")

      val defaultServerBuilder = ServerBuilder()
        .codec(createCodec)
        .bindTo(bindAddress)
        .tracer(createTracer)
        .name(name)

      val sb1 = serverConfig.newSSLEngine.map {
        f =>
          defaultServerBuilder.newFinagleSslEngine({
            () =>
              val r = f()
              com.twitter.finagle.ssl.Engine(r.self, r.handlesRenegotiation, r.certId)
          })
      }.getOrElse(defaultServerBuilder)

      val sb2 = serverConfig.tlsConfig.map {
        tc =>
          sb1.tls(
            tc.certificatePath,
            tc.keyPath,
            tc.caCertificatePath.orNull,
            tc.ciphers.orNull,
            tc.nextProtos.orNull
          )
      }.getOrElse(sb1)

      finagleServer = Some(sb2.build(service))

      globalSettings.foreach {
        _.onStart(this)
      }

      if (environment == Environment.Development) {
        info( """
                |********************************************************************
                |*** WARNING: Trinity is running in DEVELOPMENT mode.             ***
                |***                                ^^^^^^^^^^^                   ***
                |********************************************************************
              """.stripMargin)
      } else {
        info( """
                |********************************************************************
                |*** Trinity is running in Product mode.                          ***
                |********************************************************************
              """.stripMargin)
      }
    }
  }

  def stop()(implicit executor: ExecutionContext): Future[Unit] = synchronized {
    withDebugScope("stop") {
      require(finagleServer.isDefined)
      finagleServer.map {
        fs =>
          val result = fs.close().toScala
          globalSettings.foreach {
            _.onStop(this)
          }
          finagleServer = None
          result
      }.get
    }
  }

  def isStarted: Boolean = finagleServer.isDefined
}
