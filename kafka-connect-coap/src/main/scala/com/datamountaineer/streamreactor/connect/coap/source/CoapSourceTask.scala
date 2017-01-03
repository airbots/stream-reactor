/*
 * *
 *   * Copyright 2016 Datamountaineer.
 *   *
 *   * Licensed under the Apache License, Version 2.0 (the "License");
 *   * you may not use this file except in compliance with the License.
 *   * You may obtain a copy of the License at
 *   *
 *   * http://www.apache.org/licenses/LICENSE-2.0
 *   *
 *   * Unless required by applicable law or agreed to in writing, software
 *   * distributed under the License is distributed on an "AS IS" BASIS,
 *   * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   * See the License for the specific language governing permissions and
 *   * limitations under the License.
 *   *
 */

package com.datamountaineer.streamreactor.connect.coap.source

import java.util
import java.util.{Timer, TimerTask}

import akka.actor.{ActorRef, ActorSystem}
import com.datamountaineer.streamreactor.connect.coap.configs.{CoapConfig, CoapSettings}
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.apache.kafka.connect.source.{SourceRecord, SourceTask}

import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Created by andrew@datamountaineer.com on 27/12/2016. 
  * stream-reactor
  */
class CoapSourceTask extends SourceTask with StrictLogging {
  private var readers : Set[ActorRef] = _
  private val timer = new Timer()
  private val counter = mutable.Map.empty[String, Long]
  implicit val system = ActorSystem()

  class LoggerTask extends TimerTask {
    override def run(): Unit = logCounts()
  }

  def logCounts(): mutable.Map[String, Long] = {
    counter.foreach( { case (k,v) => logger.info(s"Delivered $v records for $k.") })
    counter.empty
  }

  override def start(props: util.Map[String, String]): Unit = {
    logger.info(scala.io.Source.fromInputStream(getClass.getResourceAsStream("/coap-source-ascii.txt")).mkString)
    val config = CoapConfig(props)
    val settings = CoapSettings(config, sink = false)
    val actorProps = CoapReader(settings)
    readers = actorProps.map({ case (source, prop) => system.actorOf(prop, source) }).toSet
    readers.foreach( _ ! StartChangeFeed)
    timer.schedule(new LoggerTask, 0, 60000)
  }

  override def poll(): util.List[SourceRecord] = {
    val records = readers.flatMap(ActorHelper.askForRecords).toList
    records.foreach(r => counter.put(r.topic() , counter.getOrElse(r.topic(), 0L) + 1L))
    records
  }

  override def stop(): Unit = {
    logger.info("Stopping Coap source and closing connections.")
    readers.foreach(_ ! StopChangeFeed)
    timer.cancel()
    counter.empty
    Await.ready(system.terminate(), 1.minute)
  }

  override def version(): String = getClass.getPackage.getImplementationVersion
}