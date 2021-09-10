/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
package com.dremio.service.sysflight;

import java.net.ServerSocket;
import java.util.List;

import org.apache.arrow.flight.Criteria;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.BufferAllocator;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.dremio.BaseTestQuery;
import com.dremio.common.AutoCloseables;
import com.dremio.exec.proto.FlightProtos.CoordinatorFlightTicket;
import com.dremio.exec.proto.FlightProtos.SysFlightTicket;
import com.dremio.service.job.ActiveJobSummary;
import com.dremio.service.sysflight.SystemTableManager.TABLES;
import com.google.common.collect.ImmutableList;

import io.grpc.StatusRuntimeException;

/**
 * Tests for SysFlight producer
 */
public class TestSysFlightProducer extends BaseTestQuery {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @ClassRule
  public static final TestSysFlightResource SYS_FLIGHT_RESOURCE = new TestSysFlightResource();

  private static BufferAllocator allocator;
  private static SysFlightProducer producer;
  private static FlightServer server;
  private static FlightClient client;

  @BeforeClass
  public static void setup() throws Exception {
    allocator = getSabotContext().getAllocator().newChildAllocator("sys-flight-allocator", 0, Long.MAX_VALUE);
    producer = new SysFlightProducer(allocator, SYS_FLIGHT_RESOURCE::getChronicleBlockingStub);

    Location location = null;
    while(server == null) {
      try (ServerSocket ss = new ServerSocket(0)) {
        location = Location.forGrpcInsecure("localhost", ss.getLocalPort());
        server = FlightServer.builder()
                  .allocator(allocator)
                  .producer(producer)
                  .location(location)
                  .build();
      }
    }
    server.start();
    client = FlightClient.builder()
      .allocator(allocator)
      .location(location).build();
  }

  @AfterClass
  public static void tearDown() throws Exception {
    AutoCloseables.close(client, server, producer, allocator);
  }

  @Test
  public void testFlightInfoAndData() throws Exception {
    Iterable<FlightInfo> flightInfos = client.listFlights(Criteria.ALL);
    List<FlightInfo> infoList = ImmutableList.copyOf(flightInfos);
    Assert.assertTrue(infoList.size() > 0);

    final FlightInfo info = client.getInfo(FlightDescriptor.path(TABLES.JOBS.getName()));
    final CoordinatorFlightTicket ticket = CoordinatorFlightTicket.newBuilder()
      .setSyFlightTicket(SysFlightTicket.newBuilder().setDatasetName(TABLES.JOBS.getName()).build())
      .build();

    int rowCount = 0;
    try (FlightStream stream = client.getStream(new Ticket(ticket.toByteArray()))) {
      while (stream.next()) {
        rowCount = rowCount + stream.getRoot().getRowCount();
        Assert.assertEquals(info.getSchema(), stream.getRoot().getSchema());
        Assert.assertEquals(info.getSchema(), ProtobufRecordReader.getSchema(ActiveJobSummary.getDescriptor()));
      }
    }
    Assert.assertEquals(2, rowCount);
  }

  @Test
  public void testUnsupportedDataset() {
    final String random = "random";
    thrown.expect(StatusRuntimeException.class);
    thrown.expectMessage("'" + random + "' system table is not supported");

    client.getSchema(FlightDescriptor.path(random));
  }
  @Test
  public void testFlightStreamBatched() throws Exception{
    //TODO: Add a test for zero fields
    testFlightStreamBatchedMethod(1);
    testFlightStreamBatchedMethod(2);
    testFlightStreamBatchedMethod(3);
  }
  public void testFlightStreamBatchedMethod(int recordBatchSize) throws Exception{
    producer.setRecordBatchSize(recordBatchSize);
    int actualRowCount = 2;

    final CoordinatorFlightTicket ticket = CoordinatorFlightTicket.newBuilder()
      .setSyFlightTicket(SysFlightTicket.newBuilder().setDatasetName(TABLES.JOBS.getName()).build())
      .build();

    int rowCount = 0;
    int batchCount = 0;
    int expectedRowCount = actualRowCount;
    try (FlightStream stream = client.getStream(new Ticket(ticket.toByteArray()))) {
      while (stream.next()) {
        rowCount = rowCount + stream.getRoot().getRowCount();
        Assert.assertEquals(Math.min(expectedRowCount,recordBatchSize), stream.getRoot().getRowCount());
        batchCount++;
        expectedRowCount = expectedRowCount - recordBatchSize;
      }
    }
    int expectedBatchCount = 0;
    if(actualRowCount % recordBatchSize == 0){
      expectedBatchCount = actualRowCount/recordBatchSize;
    } else{
      expectedBatchCount = actualRowCount/recordBatchSize + 1;
    }

    Assert.assertEquals(actualRowCount, rowCount);
    Assert.assertEquals(expectedBatchCount, batchCount);
  }
}