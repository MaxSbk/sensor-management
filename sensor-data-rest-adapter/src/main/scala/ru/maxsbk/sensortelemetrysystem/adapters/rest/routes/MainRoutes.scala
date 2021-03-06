package ru.maxsbk.sensortelemetrysystem.adapters.rest.routes

import java.util.concurrent.TimeUnit

import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directives, Route}
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import ru.maxsbk.sensortelemetrysystem.adapters.rest.actors.SensorDataProducer
import ru.maxsbk.sensortelemetrysystem.adapters.rest.config.ProjectConfig
import ru.maxsbk.sensortelemetrysystem.adapters.rest.utils.{EnumEncoders, ProducerAskResponses}
import ru.maxsbk.sensortelemetrysystem.models.Measurement

import scala.concurrent.Future
import scala.util.{Failure, Success}

class MainRoutes(
  config: ProjectConfig,
  producer: ActorRef[SensorDataProducer.Command]
)(implicit val system: ActorSystem[_])
    extends Directives
    with EnumEncoders {
  private implicit val timeout: Timeout = Timeout(config.routes.askTimeout, TimeUnit.SECONDS)

  private def produceSensorData(message: Measurement): Future[ProducerAskResponses.AskResponse] =
    producer.ask(
      SensorDataProducer.ProduceSensorData(message, _)
    )

  val route: Route =
    pathPrefix("sensor-data") {
      extractRequest { _ =>
        post {
          entity(as[Measurement]) { requestEntity =>
            reqRespProceed(produceSensorData(requestEntity))
          }
        }
      }
    }

  private def reqRespProceed(future: Future[_]): Route = {
    onComplete(future) {
      case Success(result) =>
        result match {
          case ProducerAskResponses.ProduceTaskCreated =>
            complete((StatusCodes.Created, "Message produced to kafka topic"))
          case ProducerAskResponses.ProduceTaskFaulted(error) =>
            complete((StatusCodes.InternalServerError, error))
        }
      case Failure(exception) =>
        complete((StatusCodes.InternalServerError, exception))
    }
  }
}
