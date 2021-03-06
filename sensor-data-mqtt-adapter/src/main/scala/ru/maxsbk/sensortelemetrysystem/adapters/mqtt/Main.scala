package ru.maxsbk.sensortelemetrysystem.adapters.mqtt

import java.time.LocalDate
import java.time.format.DateTimeFormatter

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.alpakka.mqtt.scaladsl.{ MqttMessageWithAck, MqttSource }
import akka.stream.alpakka.mqtt.{ MqttConnectionSettings, MqttQoS, MqttSubscriptions }
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import ru.maxsbk.sensortelemetrysystem.adapters.mqtt.config.ProjectConfig
import ru.maxsbk.sensortelemetrysystem.adapters.mqtt.utils.MeasurementSerializer
import ru.maxsbk.sensortelemetrysystem.models.{ Measurement, SensorInfo, SensorType }

import scala.concurrent.{ ExecutionContextExecutor, Future }

object Main {
  private val systemConfig                  = ProjectConfig()
  implicit val system: ActorSystem          = ActorSystem("sensor-data-ingestion-system")
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  val kafkaProducerConfig: Config           = system.settings.config.getConfig("akka.kafka.producer")
  val producerSettings: ProducerSettings[String, Measurement] =
    ProducerSettings(kafkaProducerConfig, new StringSerializer, new MeasurementSerializer)
      .withBootstrapServers(systemConfig.kafkaConfig.bootstrapServers)

  def main(args: Array[String]): Unit = {
    val testingTopic = "topic"
    val connectionSettings = MqttConnectionSettings(
      systemConfig.mqttBroker.url,
      systemConfig.mqttBroker.clientId,
      new MemoryPersistence
    )

    val mqttSource: Source[MqttMessageWithAck, Future[Done]] =
      MqttSource.atLeastOnce(
        connectionSettings,
        MqttSubscriptions(testingTopic, MqttQoS.AtLeastOnce),
        bufferSize = systemConfig.mqttBroker.defaultBufferSize
      )

    mqttSource
      .mapAsync(systemConfig.mqttBroker.asyncParallelismCount) { messageWithAck =>
        messageWithAck.ack().map(_ => messageWithAck.message)
      }
      .map { mqttMessage =>
        val regEx = systemConfig.measurementTemplate.r
        mqttMessage.payload.utf8String match {
          case regEx(sensorType, sensorName, sensorId, date, place, unit, value) =>
            Some(
              Measurement(
                SensorInfo(
                  Some(sensorName),
                  sensorId,
                  LocalDate.parse(date, DateTimeFormatter.ofPattern(systemConfig.datePattern)),
                  SensorType.valueOf(sensorType)
                ),
                place,
                unit,
                value.toDouble
              )
            )
          case message =>
            system.log.warning(s"Message $message is incorrect")
            Option.empty[Measurement]
        }
      }
      .collect{
        case Some(measurement) =>
          new ProducerRecord[String, Measurement](
            systemConfig.kafkaConfig.topic,
            measurement.sensorInfo.id,
            measurement
          )
      }
      .runWith(Producer.plainSink(producerSettings))
  }
}
