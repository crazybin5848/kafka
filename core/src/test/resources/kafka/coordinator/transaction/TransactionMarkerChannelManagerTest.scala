/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.coordinator.transaction

import kafka.api.{LeaderAndIsr, PartitionStateInfo}
import kafka.common.InterBrokerSendThread
import kafka.controller.LeaderIsrAndControllerEpoch
import kafka.server.{KafkaConfig, MetadataCache}
import kafka.utils.{Scheduler, TestUtils}
import org.apache.kafka.common.{Node, TopicPartition}
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.protocol.{Errors, SecurityProtocol}
import org.apache.kafka.common.requests.{TransactionResult, WriteTxnMarkerRequest}
import org.apache.kafka.common.utils.Utils
import org.easymock.EasyMock
import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable

class TransactionMarkerChannelManagerTest {
  private var completionErrors:Errors = _
  private val metadataCache = EasyMock.createNiceMock(classOf[MetadataCache])
  private val scheduler = EasyMock.createNiceMock(classOf[Scheduler])
  private val interBrokerSendThread = EasyMock.createNiceMock(classOf[InterBrokerSendThread])
  private val channel = new TransactionMarkerChannel(ListenerName.forSecurityProtocol(SecurityProtocol.PLAINTEXT), metadataCache)
  private val requestGenerator = TransactionMarkerChannelManager.requestGenerator(channel)
  private val partition1 = new TopicPartition("topic1", 0)
  private val partition2 = new TopicPartition("topic1", 1)
  private val broker1 = new Node(1, "host", 10)
  private val broker2 = new Node(2, "otherhost", 10)
  private val channelManager = new TransactionMarkerChannelManager(KafkaConfig.fromProps(TestUtils.createBrokerConfig(1, "localhost:2181")),
    metadataCache,
    scheduler,
    interBrokerSendThread,
    channel)

  @Test
  def shouldGenerateEmptyMapWhenNoRequestsOutstanding(): Unit = {
    assertTrue(requestGenerator().isEmpty)
  }

  @Test
  def shouldGenerateRequestPerBroker(): Unit ={
    EasyMock.expect(metadataCache.getPartitionInfo(partition1.topic(), partition1.partition()))
      .andReturn(Some(PartitionStateInfo(LeaderIsrAndControllerEpoch(LeaderAndIsr(1, 0, List.empty, 0), 0), Set.empty)))

    EasyMock.expect(metadataCache.getPartitionInfo(partition2.topic(), partition2.partition()))
      .andReturn(Some(PartitionStateInfo(LeaderIsrAndControllerEpoch(LeaderAndIsr(2, 0, List.empty, 0), 0), Set.empty)))

    EasyMock.replay(metadataCache)

    channel.addNewBroker(broker1)
    channel.addNewBroker(broker2)
    channel.addRequestToSend(0, 0, TransactionResult.COMMIT, 0, Set[TopicPartition](partition1, partition2))

    val requests = requestGenerator()
    val broker1Markers = requests(broker1).request.asInstanceOf[WriteTxnMarkerRequest.Builder].build().markers()
    val broker2Markers = requests(broker2).request.asInstanceOf[WriteTxnMarkerRequest.Builder].build().markers()

    assertEquals(2, requests.size)
    assertEquals(Utils.mkList(new WriteTxnMarkerRequest.TxnMarkerEntry(0, 0, 0, TransactionResult.COMMIT, Utils.mkList(partition1))), broker1Markers)
    assertEquals(Utils.mkList(new WriteTxnMarkerRequest.TxnMarkerEntry(0, 0, 0, TransactionResult.COMMIT, Utils.mkList(partition2))), broker2Markers)

  }

  @Test
  def shouldDrainBrokerQueueWhenGeneratingRequests(): Unit = {
    EasyMock.expect(metadataCache.getPartitionInfo(partition1.topic(), partition1.partition()))
      .andReturn(Some(PartitionStateInfo(LeaderIsrAndControllerEpoch(LeaderAndIsr(1, 0, List.empty, 0), 0), Set.empty)))
    EasyMock.replay(metadataCache)

    channel.addNewBroker(broker1)
    channel.addRequestToSend(0, 0, TransactionResult.COMMIT, 0, Set[TopicPartition](partition1))

    requestGenerator()
    assertTrue(channel.brokerStateMap(1).markersQueue.isEmpty)
  }

  @Test
  def shouldAddPendingTxnRequest(): Unit = {
    val metadata = new TransactionMetadata(1, 0, 0, PrepareCommit, mutable.Set[TopicPartition](partition1, partition2), 0L)
    EasyMock.expect(metadataCache.getPartitionInfo(partition1.topic(), partition1.partition()))
      .andReturn(Some(PartitionStateInfo(LeaderIsrAndControllerEpoch(LeaderAndIsr(1, 0, List.empty, 0), 0), Set.empty)))

    EasyMock.expect(metadataCache.getPartitionInfo(partition2.topic(), partition2.partition()))
      .andReturn(Some(PartitionStateInfo(LeaderIsrAndControllerEpoch(LeaderAndIsr(2, 0, List.empty, 0), 0), Set.empty)))

    EasyMock.replay(metadataCache)

    channel.addNewBroker(broker1)
    channel.addNewBroker(broker2)

    channelManager.addTxnMarkerRequest(metadata, 0, completionCallback)

    assertEquals(channel.pendingTxnMetadata(1), metadata)

  }

  @Test
  def shouldAddRequestToBrokerQueue(): Unit = {
    val metadata = new TransactionMetadata(1, 0, 0, PrepareCommit, mutable.Set[TopicPartition](partition1), 0L)

    EasyMock.expect(metadataCache.getPartitionInfo(partition1.topic(), partition1.partition()))
      .andReturn(Some(PartitionStateInfo(LeaderIsrAndControllerEpoch(LeaderAndIsr(1, 0, List.empty, 0), 0), Set.empty)))

    EasyMock.replay(metadataCache)
    channel.addNewBroker(broker1)
    channelManager.addTxnMarkerRequest(metadata, 0, completionCallback)

    assertEquals(1, channel.brokerStateMap(1).markersQueue.size())
  }

  @Test
  def shouldInvokeCallbackOnCompletedRequest(): Unit = {
    channel.maybeAddPendingRequest(new TransactionMetadata(1, 0, 0, PrepareCommit, mutable.Set.empty, 0L), completionCallback)

    channelManager.completeCompletedRequests()
    assertEquals(Errors.NONE, completionErrors)

    try {
      channel.pendingTxnMetadata(1)
      fail("should have removed the txn metadata")
    } catch {
      case e: IllegalStateException => // expected
    }
  }

  @Test
  def shouldStartInterBrokerThreadOnStartup(): Unit = {
    EasyMock.expect(interBrokerSendThread.start())
    EasyMock.replay(interBrokerSendThread, scheduler)
    channelManager.start()
    EasyMock.verify(interBrokerSendThread)
  }

  @Test
  def shouldStartSchedulerOnStartupA(): Unit = {
    EasyMock.expect(scheduler.startup())
    EasyMock.replay(interBrokerSendThread, scheduler)
    channelManager.start()
    EasyMock.verify(scheduler)
  }

  @Test
  def shouldStopSchedulerOnShutdown(): Unit = {
    EasyMock.expect(scheduler.shutdown())
    EasyMock.replay(interBrokerSendThread, scheduler)
    channelManager.shutdown()
    EasyMock.verify(scheduler)
  }

  @Test
  def shouldStopInterBrokerThreadOnShutdown(): Unit = {
    EasyMock.expect(interBrokerSendThread.shutdown())
    EasyMock.replay(interBrokerSendThread, scheduler)
    channelManager.shutdown()
    EasyMock.verify(interBrokerSendThread)
  }

  def completionCallback(errors:Errors): Unit = {
    completionErrors = errors
  }
}