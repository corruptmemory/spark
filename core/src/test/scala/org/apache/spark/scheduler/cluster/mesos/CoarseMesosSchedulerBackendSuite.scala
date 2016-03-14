/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler.cluster.mesos

import java.util
import java.util.Collections

import org.apache.mesos.Protos.Value.Scalar
import org.apache.mesos.Protos._
import org.apache.mesos.{Protos, Scheduler, SchedulerDriver}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.Matchers
import org.scalatest.mock.MockitoSugar
import org.scalatest.BeforeAndAfter

import org.apache.spark.scheduler.TaskSchedulerImpl
import org.apache.spark.{LocalSparkContext, SparkConf, SparkContext, SecurityManager, SparkFunSuite}

class CoarseMesosSchedulerBackendSuite extends SparkFunSuite
    with LocalSparkContext
    with MockitoSugar
    with BeforeAndAfter {

  private var sparkConf: SparkConf = _
  private var driver: SchedulerDriver = _
  private var taskScheduler: TaskSchedulerImpl = _
  private var backend: CoarseMesosSchedulerBackend = _
  private var externalShuffleClient: MesosExternalShuffleClient = _
  private var driverEndpoint: RpcEndpointRef = _

  test("mesos supports killing and limiting executors") {
    setBackend()
    sparkConf.set("spark.driver.host", "driverHost")
    sparkConf.set("spark.driver.port", "1234")

    val minMem = backend.executorMemory(sc)
    val minCpu = 4
    val offers = List((minMem, minCpu))

    // launches a task on a valid offer
    offerResources(offers)
    verifyTaskLaunched("o1")

    // kills executors
    backend.doRequestTotalExecutors(0)
    assert(backend.doKillExecutors(Seq("0")))
    val taskID0 = createTaskId("0")
    verify(driver, times(1)).killTask(taskID0)

    // doesn't launch a new task when requested executors == 0
    offerResources(offers, 2)
    verifyDeclinedOffer(driver, createOfferId("o2"))

    // Launches a new task when requested executors is positive
    backend.doRequestTotalExecutors(2)
    offerResources(offers, 2)
    verifyTaskLaunched("o2")
  }

  test("mesos supports killing and relaunching tasks with executors") {
    setBackend()

    // launches a task on a valid offer
    val minMem = backend.executorMemory(sc) + 1024
    val minCpu = 4
    val offer1 = (minMem, minCpu)
    val offer2 = (minMem, 1)
    offerResources(List(offer1, offer2))
    verifyTaskLaunched("o1")

    // accounts for a killed task
    val status = createTaskStatus("0", "s1", TaskState.TASK_KILLED)
    backend.statusUpdate(driver, status)
    verify(driver, times(1)).reviveOffers()

    // Launches a new task on a valid offer from the same slave
    offerResources(List(offer2))
    verifyTaskLaunched("o2")
  }

  test("mesos supports spark.executor.cores") {
    val executorCores = 4
    setBackend(Map("spark.executor.cores" -> executorCores.toString))

    val executorMemory = backend.executorMemory(sc)
    val offers = List((executorMemory * 2, executorCores + 1))
    offerResources(offers)

    val taskInfos = verifyTaskLaunched("o1")
    assert(taskInfos.size() == 1)

    val cpus = backend.getResource(taskInfos.iterator().next().getResourcesList, "cpus")
    assert(cpus == executorCores)
  }

  test("mesos supports unset spark.executor.cores") {
    setBackend()

    val executorMemory = backend.executorMemory(sc)
    val offerCores = 10
    offerResources(List((executorMemory * 2, offerCores)))

    val taskInfos = verifyTaskLaunched("o1")
    assert(taskInfos.size() == 1)

    val cpus = backend.getResource(taskInfos.iterator().next().getResourcesList, "cpus")
    assert(cpus == offerCores)
  }

  test("mesos does not acquire more than spark.cores.max") {
    val maxCores = 10
    setBackend(Map("spark.cores.max" -> maxCores.toString))

    val executorMemory = backend.executorMemory(sc)
    offerResources(List((executorMemory, maxCores + 1)))

    val taskInfos = verifyTaskLaunched("o1")
    assert(taskInfos.size() == 1)

    val cpus = backend.getResource(taskInfos.iterator().next().getResourcesList, "cpus")
    assert(cpus == maxCores)
  }

  test("mesos declines offers that violate attribute constraints") {
    setBackend(Map("spark.mesos.constraints" -> "x:true"))
    offerResources(List((backend.executorMemory(sc), 4)))
    verifyDeclinedOffer(driver, createOfferId("o1"), true)
  }

  test("mesos assigns tasks round-robin on offers") {
    val executorCores = 4
    val maxCores = executorCores * 2
    setBackend(Map("spark.executor.cores" -> executorCores.toString,
      "spark.cores.max" -> maxCores.toString))

    val executorMemory = backend.executorMemory(sc)
    offerResources(List(
      (executorMemory * 2, executorCores * 2),
      (executorMemory * 2, executorCores * 2)))

    verifyTaskLaunched("o1")
    verifyTaskLaunched("o2")
  }

  test("mesos creates multiple executors on a single slave") {
    val executorCores = 4
    setBackend(Map("spark.executor.cores" -> executorCores.toString))

    // offer with room for two executors
    val executorMemory = backend.executorMemory(sc)
    offerResources(List((executorMemory * 2, executorCores * 2)))

    // verify two executors were started on a single offer
    val taskInfos = verifyTaskLaunched("o1")
    assert(taskInfos.size() == 2)
  }

  test("mesos doesn't register twice with the same shuffle service") {
    setBackend(Map("spark.shuffle.service.enabled" -> "true"))
    val (mem, cpu) = (backend.executorMemory(sc), 4)

    val offer1 = createOffer("o1", "s1", mem, cpu)
    backend.resourceOffers(driver, List(offer1).asJava)
    verifyTaskLaunched("o1")

    val offer2 = createOffer("o2", "s1", mem, cpu)
    backend.resourceOffers(driver, List(offer2).asJava)
    verifyTaskLaunched("o2")

    val status1 = createTaskStatus("0", "s1", TaskState.TASK_RUNNING)
    backend.statusUpdate(driver, status1)

    val status2 = createTaskStatus("1", "s1", TaskState.TASK_RUNNING)
    backend.statusUpdate(driver, status2)
    verify(externalShuffleClient, times(1))
      .registerDriverWithShuffleService(anyString, anyInt, anyLong, anyLong)
  }

  test("mesos kills an executor when told") {
    setBackend()

    val (mem, cpu) = (backend.executorMemory(sc), 4)

    val offer1 = createOffer("o1", "s1", mem, cpu)
    backend.resourceOffers(driver, List(offer1).asJava)
    verifyTaskLaunched("o1")

    backend.doKillExecutors(List("0"))
    verify(driver, times(1)).killTask(createTaskId("0"))
  }

  test("weburi is set in created scheduler driver") {
    setBackend()
    val taskScheduler = mock[TaskSchedulerImpl]
    when(taskScheduler.sc).thenReturn(sc)
    val driver = mock[SchedulerDriver]
    when(driver.start()).thenReturn(Protos.Status.DRIVER_RUNNING)
    val securityManager = mock[SecurityManager]

    val backend = new CoarseMesosSchedulerBackend(taskScheduler, sc, "master", securityManager) {
      override protected def createSchedulerDriver(
        masterUrl: String,
        scheduler: Scheduler,
        sparkUser: String,
        appName: String,
        conf: SparkConf,
        webuiUrl: Option[String] = None,
        checkpoint: Option[Boolean] = None,
        failoverTimeout: Option[Double] = None,
        frameworkId: Option[String] = None): SchedulerDriver = {
        markRegistered()
        assert(webuiUrl.isDefined)
        assert(webuiUrl.get.equals("http://webui"))
        driver
      }
    }

    backend.start()
  }

  private def verifyDeclinedOffer(driver: SchedulerDriver,
      offerId: OfferID,
      filter: Boolean = false): Unit = {
    if (filter) {
      verify(driver, times(1)).declineOffer(Matchers.eq(offerId), anyObject[Filters])
    } else {
      verify(driver, times(1)).declineOffer(Matchers.eq(offerId))
    }
  }

  private def offerResources(offers: List[(Int, Int)], startId: Int = 1): Unit = {
    val mesosOffers = offers.zipWithIndex.map {case (offer, i) =>
      createOffer(s"o${i + startId}", s"s${i + startId}", offer._1, offer._2)}

    backend.resourceOffers(driver, mesosOffers.asJava)
  }

  private def verifyTaskLaunched(offerId: String): java.util.Collection[TaskInfo] = {
    val captor = ArgumentCaptor.forClass(classOf[java.util.Collection[TaskInfo]])
    verify(driver, times(1)).launchTasks(
      Matchers.eq(Collections.singleton(createOfferId(offerId))),
      captor.capture())
    captor.getValue
  }

  private def createTaskStatus(taskId: String, slaveId: String, state: TaskState): TaskStatus = {
    TaskStatus.newBuilder()
      .setTaskId(TaskID.newBuilder().setValue(taskId).build())
      .setSlaveId(SlaveID.newBuilder().setValue(slaveId).build())
      .setState(state)
      .build
  }


  private def createOfferId(offerId: String): OfferID = {
    OfferID.newBuilder().setValue(offerId).build()
  }

  private def createSlaveId(slaveId: String): SlaveID = {
    SlaveID.newBuilder().setValue(slaveId).build()
  }

  private def createExecutorId(executorId: String): ExecutorID = {
    ExecutorID.newBuilder().setValue(executorId).build()
  }

  private def createTaskId(taskId: String): TaskID = {
    TaskID.newBuilder().setValue(taskId).build()
  }

  private def createOffer(offerId: String, slaveId: String, mem: Int, cpu: Int): Offer = {
    val builder = Offer.newBuilder()
    builder.addResourcesBuilder()
      .setName("mem")
      .setType(Value.Type.SCALAR)
      .setScalar(Scalar.newBuilder().setValue(mem))
    builder.addResourcesBuilder()
      .setName("cpus")
      .setType(Value.Type.SCALAR)
      .setScalar(Scalar.newBuilder().setValue(cpu))
    builder.setId(OfferID.newBuilder()
      .setValue(offerId).build())
      .setFrameworkId(FrameworkID.newBuilder()
        .setValue("f1"))
      .setSlaveId(SlaveID.newBuilder().setValue(slaveId))
      .setHostname(s"host${slaveId}")
      .build()
  }

  private def createSchedulerBackend(
      taskScheduler: TaskSchedulerImpl,
      driver: SchedulerDriver): CoarseMesosSchedulerBackend = {
    val securityManager = mock[SecurityManager]
    val backend = new CoarseMesosSchedulerBackend(taskScheduler, sc, "master", securityManager) {
      override protected def createSchedulerDriver(
        masterUrl: String,
        scheduler: Scheduler,
        sparkUser: String,
        appName: String,
        conf: SparkConf,
        webuiUrl: Option[String] = None,
        checkpoint: Option[Boolean] = None,
        failoverTimeout: Option[Double] = None,
        frameworkId: Option[String] = None): SchedulerDriver = driver
      markRegistered()
    }
    backend.start()
    backend
  }

  var sparkConf: SparkConf = _

  before {
    sparkConf = (new SparkConf)
      .setMaster("local[*]")
      .setAppName("test-mesos-dynamic-alloc")
      .setSparkHome("/path")

    sc = new SparkContext(sparkConf)
  }

  test("mesos supports killing and limiting executors") {
    val driver = mock[SchedulerDriver]
    when(driver.start()).thenReturn(Protos.Status.DRIVER_RUNNING)
    val taskScheduler = mock[TaskSchedulerImpl]
    when(taskScheduler.sc).thenReturn(sc)

    sparkConf.set("spark.driver.host", "driverHost")
    sparkConf.set("spark.driver.port", "1234")

    val backend = createSchedulerBackend(taskScheduler, driver)
    val minMem = backend.calculateTotalMemory(sc)
    val minCpu = 4

    val mesosOffers = new java.util.ArrayList[Offer]
    mesosOffers.add(createOffer("o1", "s1", minMem, minCpu))

    val taskID0 = TaskID.newBuilder().setValue("0").build()

    backend.resourceOffers(driver, mesosOffers)
    verify(driver, times(1)).launchTasks(
      Matchers.eq(Collections.singleton(mesosOffers.get(0).getId)),
      any[util.Collection[TaskInfo]],
      any[Filters])

    // simulate the allocation manager down-scaling executors
    backend.doRequestTotalExecutors(0)
    assert(backend.doKillExecutors(Seq("s1/0")))
    verify(driver, times(1)).killTask(taskID0)

    val mesosOffers2 = new java.util.ArrayList[Offer]
    mesosOffers2.add(createOffer("o2", "s2", minMem, minCpu))
    backend.resourceOffers(driver, mesosOffers2)

    verify(driver, times(1))
      .declineOffer(OfferID.newBuilder().setValue("o2").build())

    // Verify we didn't launch any new executor
    assert(backend.slaveIdsWithExecutors.size === 1)

    backend.doRequestTotalExecutors(2)
    backend.resourceOffers(driver, mesosOffers2)
    verify(driver, times(1)).launchTasks(
      Matchers.eq(Collections.singleton(mesosOffers2.get(0).getId)),
      any[util.Collection[TaskInfo]],
      any[Filters])

    assert(backend.slaveIdsWithExecutors.size === 2)
    backend.slaveLost(driver, SlaveID.newBuilder().setValue("s1").build())
    assert(backend.slaveIdsWithExecutors.size === 1)
  }

  test("mesos supports killing and relaunching tasks with executors") {
    val driver = mock[SchedulerDriver]
    when(driver.start()).thenReturn(Protos.Status.DRIVER_RUNNING)
    val taskScheduler = mock[TaskSchedulerImpl]
    when(taskScheduler.sc).thenReturn(sc)

    val backend = createSchedulerBackend(taskScheduler, driver)
    val minMem = backend.calculateTotalMemory(sc) + 1024
    val minCpu = 4

    val mesosOffers = new java.util.ArrayList[Offer]
    val offer1 = createOffer("o1", "s1", minMem, minCpu)
    mesosOffers.add(offer1)

    val offer2 = createOffer("o2", "s1", minMem, 1);

    backend.resourceOffers(driver, mesosOffers)

    verify(driver, times(1)).launchTasks(
      Matchers.eq(Collections.singleton(offer1.getId)),
      anyObject(),
      anyObject[Filters])

    // Simulate task killed, executor no longer running
    val status = TaskStatus.newBuilder()
      .setTaskId(TaskID.newBuilder().setValue("0").build())
      .setSlaveId(SlaveID.newBuilder().setValue("s1").build())
      .setState(TaskState.TASK_KILLED)
      .build

    backend.statusUpdate(driver, status)
    assert(!backend.slaveIdsWithExecutors.contains("s1"))

    mesosOffers.clear()
    mesosOffers.add(offer2)
    backend.resourceOffers(driver, mesosOffers)
    assert(backend.slaveIdsWithExecutors.contains("s1"))

    verify(driver, times(1)).launchTasks(
      Matchers.eq(Collections.singleton(offer2.getId)),
      anyObject(),
      anyObject[Filters])

    verify(driver, times(1)).reviveOffers()
  }
}
