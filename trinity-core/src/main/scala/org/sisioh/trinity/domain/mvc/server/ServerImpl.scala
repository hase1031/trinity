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
import com.twitter.finagle.builder.ServerConfig.Yes
import com.twitter.finagle.builder.{Server => FinagleServer}
import com.twitter.finagle.channel.OpenConnectionsThresholds
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
import org.sisioh.trinity.util.DurationConverters._
import org.sisioh.trinity.util.FutureConverters._
import org.slf4j.bridge.SLF4JBridgeHandler
import scala.concurrent._
import com.twitter.finagle.builder.ServerConfig.Yes

/**
 * Represents the implementation class for [[Server]].
 *
 * @param serverConfig [[ServerConfig]]
 * @param action wrapped [[Action]] around `scala.Option`
 * @param filter wrapped [[Filter]] around `scala.Option`
 * @param globalSettings [[GlobalSettings]]
 * @param executor `ExecutionContext`
 */
private[mvc]
class ServerImpl
(val serverConfig: ServerConfig,
 val action: Option[Action[Request, Response]],
 val filter: Option[Filter[Request, Response, Request, Response]],
 val globalSettings: Option[GlobalSettings[Request, Response]])
(implicit executor: ExecutionContext)
  extends Server with LoggingEx {

  private var finagleServer: Option[FinagleServer] = None

  /**
   * 
   * @return
   */
  protected def createTracer: Tracer = NullTracer

  protected def createRuntimeEnviroment: RuntimeEnvironment = new RuntimeEnvironment(this)

  private val defaultAdminHttpServicePort = 9990

  filter.foreach(registerFilter)

  /**
   * Creates a `com.twitter.ostrich.admin.AdminHttpService`.
   *
   * @param runtimeEnv `com.twitter.ostrich.admin.RuntimeEnvironment`
   * @return `com.twitter.ostrich.admin.AdminHttpService`
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
   * Creates a `com.twitter.finagle.CodecFactory`.
   *
   * @return `com.twitter.finagle.CodecFactory`
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

  private def configNewSSLEngine[Req, Rep, HasCodec, HasBindTo, HasName]
  (sb: ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes]): ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes] = {
    serverConfig.newSSLEngine.fold(sb)(f =>
      sb.newFinagleSslEngine({
        () =>
          val r = f()
          com.twitter.finagle.ssl.Engine(r.self, r.handlesRenegotiation, r.certId)
      }))
  }

  private def configTls[Req, Rep, HasCodec, HasBindTo, HasName]
  (sb: ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes]): ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes] = {
    serverConfig.tlsConfig.fold(sb)(tc =>
      sb.tls(
        tc.certificatePath,
        tc.keyPath,
        tc.caCertificatePath.orNull,
        tc.ciphers.orNull,
        tc.nextProtos.orNull
      ))
  }

  private def configMaxConcurrentRequests[Req, Rep, HasCodec, HasBindTo, HasName]
  (sb: ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes]): ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes] = {
    serverConfig.maxConcurrentRequests.fold(sb)(v =>
      sb.maxConcurrentRequests(v))
  }


  private def configHostConnectionMaxIdleTime[Req, Rep, HasCodec, HasBindTo, HasName]
  (sb: ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes]): ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes] = {
    serverConfig.hostConnectionMaxIdleTime.fold(sb)(v =>
      sb.hostConnectionMaxIdleTime(v.toTwitter))
  }

  private def configHostConnectionMaxLifeTime[Req, Rep, HasCodec, HasBindTo, HasName]
  (sb: ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes]): ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes] = {
    serverConfig.hostConnectionMaxLifeTime.fold(sb)(v =>
      sb.hostConnectionMaxLifeTime(v.toTwitter))
  }


  private def configRequestTimeout[Req, Rep, HasCodec, HasBindTo, HasName]
  (sb: ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes]): ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes] = {
    serverConfig.requestTimeout.fold(sb)(v =>
      sb.requestTimeout(v.toTwitter))
  }

  private def configReadTimeout[Req, Rep, HasCodec, HasBindTo, HasName]
  (sb: ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes]): ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes] = {
    serverConfig.readTimeout.fold(sb)(v =>
      sb.readTimeout(v.toTwitter))
  }

  private def configWriteCompletionTimeout[Req, Rep, HasCodec, HasBindTo, HasName]
  (sb: ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes]): ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes] = {
    serverConfig.writeCompletionTimeout.fold(sb)(v =>
      sb.writeCompletionTimeout(v.toTwitter))
  }

  private def configSendBufferSize[Req, Rep, HasCodec, HasBindTo, HasName]
  (sb: ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes]): ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes] = {
    serverConfig.sendBufferSize.fold(sb)(v =>
      sb.sendBufferSize(v))
  }

  private def configReceiveBufferSize[Req, Rep, HasCodec, HasBindTo, HasName]
  (sb: ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes]): ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes] = {
    serverConfig.receiveBufferSize.fold(sb)(v =>
      sb.recvBufferSize(v))
  }

  private def configOpenConnectionsThresholdsConfig[Req, Rep, HasCodec, HasBindTo, HasName]
  (sb: ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes]): ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes] = {
    serverConfig.openConnectionsThresholdsConfig.fold(sb)(v =>
      sb.openConnectionsThresholds(
        OpenConnectionsThresholds(v.lowWaterMark, v.highWaterMark, v.idleTime.toTwitter)
      ))
  }

  private def configKeepAlive[Req, Rep, HasCodec, HasBindTo, HasName]
  (sb: ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes]): ServerBuilder[Req, Rep, HasCodec, HasBindTo, Yes] = {
    serverConfig.keepAlive.fold(sb)(v =>
      sb.keepAlive(v))
  }

  override def start(environment: Environment.Value = Environment.Development)
                    (implicit executor: ExecutionContext): Future[Unit] = future {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    withDebugScope("start") {
      require(finagleServer.isEmpty)
      info(s"aciton = $action, routingFilter = $filter")

      if (serverConfig.statsEnabled) {
        createAdminHttpService(createRuntimeEnviroment)
      }

      val service = buildService(environment, action)
      val bindAddress = serverConfig.bindAddress.getOrElse(Server.defaultBindAddress)
      val name = serverConfig.name.getOrElse(Server.defaultName)

      val defaultServerBuilder = ServerBuilder()
        .codec(createCodec)
        .bindTo(bindAddress)
        .tracer(createTracer)
        .logChannelActivity(serverConfig.finagleLogging)
        .name(name)

      val sb0 = configKeepAlive(defaultServerBuilder)
      val sb1 = configOpenConnectionsThresholdsConfig(sb0)

      val sb2 = configNewSSLEngine(sb1)
      val sb3 = configTls(sb2)

      val sb4 = configMaxConcurrentRequests(sb3)
      val sb5 = configHostConnectionMaxIdleTime(sb4)
      val sb6 = configHostConnectionMaxLifeTime(sb5)

      val sb7 = configRequestTimeout(sb6)
      val sb8 = configReadTimeout(sb7)
      val sb9 = configWriteCompletionTimeout(sb8)

      val sb10 = configSendBufferSize(sb9)
      val sb11 = configReceiveBufferSize(sb10)

      finagleServer = Some(sb11.build(service))

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

  override def stop()(implicit executor: ExecutionContext): Future[Unit] = synchronized {
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

  override def isStarted: Boolean = finagleServer.isDefined

}
