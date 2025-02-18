/*
 * Copyright 2017-2023 Lenses.io Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lenses.streamreactor.connect.aws.s3.sink

import cats.implicits._
import com.datamountaineer.streamreactor.common.errors.ErrorHandler
import com.datamountaineer.streamreactor.common.errors.RetryErrorPolicy
import com.datamountaineer.streamreactor.common.utils.AsciiArtPrinter.printAsciiHeader
import com.datamountaineer.streamreactor.common.utils.JarManifest
import io.lenses.streamreactor.connect.aws.s3.auth.AwsS3ClientCreator
import io.lenses.streamreactor.connect.aws.s3.config.ConnectorTaskId
import io.lenses.streamreactor.connect.aws.s3.config.S3Config
import io.lenses.streamreactor.connect.aws.s3.formats.writer.MessageDetail
import io.lenses.streamreactor.connect.aws.s3.formats.writer.SinkData
import io.lenses.streamreactor.connect.aws.s3.model._
import io.lenses.streamreactor.connect.aws.s3.sink.config.S3SinkConfig
import io.lenses.streamreactor.connect.aws.s3.sink.conversion.HeaderToStringConverter
import io.lenses.streamreactor.connect.aws.s3.sink.conversion.ValueToSinkDataConverter
import io.lenses.streamreactor.connect.aws.s3.storage.AwsS3StorageInterface
import io.lenses.streamreactor.connect.aws.s3.utils.MapUtils
import io.lenses.streamreactor.connect.aws.s3.utils.TimestampUtils
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.{ TopicPartition => KafkaTopicPartition }
import org.apache.kafka.connect.sink.SinkRecord
import org.apache.kafka.connect.sink.SinkTask

import java.util
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.CollectionConverters.MapHasAsJava
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.util.Try

class S3SinkTask extends SinkTask with ErrorHandler {

  private val manifest = JarManifest(getClass.getProtectionDomain.getCodeSource.getLocation)

  private var writerManager: S3WriterManager = _

  private implicit var connectorTaskId: ConnectorTaskId = _

  override def version(): String = manifest.version()

  override def start(fallbackProps: util.Map[String, String]): Unit = {

    printAsciiHeader(manifest, "/aws-s3-sink-ascii.txt")

    ConnectorTaskId.fromProps(fallbackProps) match {
      case Left(value)  => throw new IllegalArgumentException(value)
      case Right(value) => connectorTaskId = value
    }

    logger.debug(s"[{}] S3SinkTask.start", connectorTaskId.show)

    val contextProps = Option(context).flatMap(c => Option(c.configs())).map(_.asScala.toMap).getOrElse(Map.empty)
    val props        = MapUtils.mergeProps(contextProps, fallbackProps.asScala.toMap).asJava
    val errOrWriterMan = for {
      config          <- S3SinkConfig.fromProps(props)
      s3Client        <- AwsS3ClientCreator.make(config.s3Config)
      storageInterface = new AwsS3StorageInterface(connectorTaskId, s3Client, config.batchDelete)
      _               <- Try(setErrorRetryInterval(config.s3Config)).toEither
      writerManager   <- Try(S3WriterManager.from(config)(connectorTaskId, storageInterface)).toEither
      _ <- Try(initialize(
        config.s3Config.connectorRetryConfig.numberOfRetries,
        config.s3Config.errorPolicy,
      )).toEither
    } yield writerManager

    errOrWriterMan.leftMap(throw _).foreach(writerManager = _)
  }

  private def setErrorRetryInterval(s3Config: S3Config): Unit =
    //if error policy is retry set retry interval
    s3Config.errorPolicy match {
      case RetryErrorPolicy() => context.timeout(s3Config.connectorRetryConfig.errorRetryInterval)
      case _                  =>
    }

  case class TopicAndPartition(topic: String, partition: Int) {
    override def toString: String = s"$topic-$partition"
  }

  private object TopicAndPartition {
    implicit val ordering: Ordering[TopicAndPartition] = (x: TopicAndPartition, y: TopicAndPartition) => {
      val c = x.topic.compareTo(y.topic)
      if (c == 0) x.partition.compareTo(y.partition)
      else c
    }
  }

  private case class Bounds(start: Long, end: Long) {
    override def toString: String = s"$start->$end"
  }

  private def buildLogForRecords(records: Iterable[SinkRecord]): Map[TopicAndPartition, Bounds] =
    records.foldLeft(Map.empty[TopicAndPartition, Bounds]) {
      case (map, record) =>
        val topicAndPartition = TopicAndPartition(record.topic(), record.kafkaPartition())
        map.get(topicAndPartition) match {
          case Some(value) =>
            map + (topicAndPartition -> value.copy(end = record.kafkaOffset()))
          case None => map + (topicAndPartition -> Bounds(record.kafkaOffset(), record.kafkaOffset()))
        }
    }

  private def rollback(topicPartitions: Set[TopicPartition]): Unit =
    topicPartitions.foreach(writerManager.cleanUp)

  private def handleErrors(value: Either[SinkError, Unit]): Unit =
    value match {
      case Left(error: SinkError) =>
        if (error.rollBack()) {
          rollback(error.topicPartitions())
        }
        throw new IllegalStateException(error.message(), error.exception())
      case Right(_) =>
    }

  override def put(records: util.Collection[SinkRecord]): Unit = {

    val _ = handleTry {
      Try {
        val recordsStats = buildLogForRecords(records.asScala)
          .toList.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(";")

        logger.debug(s"[${connectorTaskId.show}] put records=${records.size()} stats=$recordsStats")

        // a failure in recommitPending will prevent the processing of further records
        handleErrors(writerManager.recommitPending())

        records.asScala.foreach {
          record =>
            handleErrors(
              writerManager.write(
                Topic(record.topic).withPartition(record.kafkaPartition.intValue).withOffset(record.kafkaOffset),
                MessageDetail(
                  keySinkData = Option(record.key()).fold(Option.empty[SinkData])(key =>
                    Option(ValueToSinkDataConverter(key, Option(record.keySchema()))),
                  ),
                  valueSinkData = ValueToSinkDataConverter(record.value(), Option(record.valueSchema())),
                  headers       = HeaderToStringConverter(record),
                  TimestampUtils.parseTime(Option(record.timestamp()).map(_.toLong))(_ =>
                    logger.debug(
                      s"Record timestamp is invalid ${record.timestamp()}",
                    ),
                  ),
                ),
              ),
            )
        }

        if (records.isEmpty) {
          handleErrors(writerManager.commitAllWritersIfFlushRequired())
        }
      }
    }

  }

  override def preCommit(
    currentOffsets: util.Map[KafkaTopicPartition, OffsetAndMetadata],
  ): util.Map[KafkaTopicPartition, OffsetAndMetadata] = {
    def getDebugInfo(in: util.Map[KafkaTopicPartition, OffsetAndMetadata]): String =
      in.entrySet().asScala.toList.map(e =>
        e.getKey.topic() + "-" + e.getKey.partition() + ":" + e.getValue.offset(),
      ).mkString(";")

    logger.debug(s"[{}] preCommit with offsets={}", connectorTaskId.show, getDebugInfo(currentOffsets): Any)

    val topicPartitionOffsetTransformed: Map[TopicPartition, OffsetAndMetadata] =
      Option(currentOffsets).getOrElse(new util.HashMap())
        .asScala
        .map {
          topicPartToOffsetTuple: (KafkaTopicPartition, OffsetAndMetadata) =>
            (
              TopicPartition(topicPartToOffsetTuple._1),
              topicPartToOffsetTuple._2,
            )
        }
        .toMap

    val actualOffsets = writerManager
      .preCommit(topicPartitionOffsetTransformed)
      .map {
        case (topicPartition, offsetAndMetadata) =>
          (topicPartition.toKafka, offsetAndMetadata)
      }.asJava

    logger.debug(s"[{}] Returning latest written offsets={}", connectorTaskId.show, getDebugInfo(actualOffsets): Any)
    actualOffsets
  }

  override def open(partitions: util.Collection[KafkaTopicPartition]): Unit = {
    val partitionsDebug = partitions.asScala.map(tp => s"${tp.topic()}-${tp.partition()}").mkString(",")
    logger.debug(s"[{}] Open partitions", connectorTaskId.show, partitionsDebug: Any)

    val topicPartitions = partitions.asScala
      .map(tp => TopicPartition(Topic(tp.topic), tp.partition))
      .toSet

    handleErrors(
      for {
        tpoMap <- writerManager.open(topicPartitions)
      } yield {
        tpoMap.foreach {
          case (topicPartition, offset) =>
            logger.debug(
              s"[${connectorTaskId.show}] Seeking to ${topicPartition.topic.value}-${topicPartition.partition}:${offset.value}",
            )
            context.offset(topicPartition.toKafka, offset.value)
        }
      },
    )

  }

  /**
    * Whenever close is called, the topics and partitions assigned to this task
    * may be changing, eg, in a re-balance. Therefore, we must commit our open files
    * for those (topic,partitions) to ensure no records are lost.
    */
  override def close(partitions: util.Collection[KafkaTopicPartition]): Unit = {
    logger.debug("[{}] S3SinkTask.close with {} partitions",
                 Option(connectorTaskId).map(_.show).getOrElse("Unnamed"),
                 partitions.size(),
    )

    Option(writerManager).foreach(_.close())
  }

  override def stop(): Unit = {
    logger.debug("[{}] Stop", Option(connectorTaskId).map(_.show).getOrElse("Unnamed"))

    Option(writerManager).foreach(_.close())
    writerManager = null
  }

}
