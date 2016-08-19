/**
 * Copyright 2016 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.destination.jobtype.avroparquet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.OnRecordError;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.sdk.RecordCreator;
import com.streamsets.pipeline.sdk.TargetRunner;
import com.streamsets.pipeline.stage.destination.mapreduce.MapReduceDExecutor;
import com.streamsets.pipeline.stage.destination.mapreduce.MapReduceExecutor;
import com.streamsets.pipeline.stage.destination.mapreduce.jobtype.avroparquet.AvroParquetConfig;
import org.apache.avro.Schema;
import org.apache.avro.util.Utf8;
import org.apache.hadoop.fs.Path;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AvroParquetConvertIT extends BaseAvroParquetConvertIT {

  private static final Schema AVRO_SCHEMA = Schema.parse("{" +
    "\"type\": \"record\", " +
    "\"name\": \"RandomRecord\", " +
    "\"fields\": [" +
      "{\"name\": \"id\", \"type\": \"string\"}," +
      "{\"name\": \"price\", \"type\": \"int\"}" +
    "]" +
  "}");

  @Test
  public void testStaticConfiguration() throws Exception {
    File inputFile = new File(getInputDir(), "input.avro");

    List<Map<String, Object>> data = ImmutableList.of(
      (Map<String, Object>)new ImmutableMap.Builder<String, Object>()
        .put("id", new Utf8("monitor"))
        .put("price", 10)
        .build()
    );

    generateAvroFile(AVRO_SCHEMA, inputFile, data);

    AvroParquetConfig conf = new AvroParquetConfig();
    conf.inputFile = inputFile.getAbsolutePath();
    conf.outputDirectory = getOutputDir();

    MapReduceExecutor executor = generateExecutor(conf, Collections.<String, String>emptyMap());

    TargetRunner runner = new TargetRunner.Builder(MapReduceDExecutor.class, executor)
      .setOnRecordError(OnRecordError.TO_ERROR)
      .build();
    runner.runInit();

    Record record = RecordCreator.create();
    record.set(Field.create(Collections.<String, Field>emptyMap()));

    runner.runWrite(ImmutableList.of(record));
    Assert.assertEquals(0, runner.getErrorRecords().size());
    runner.runDestroy();

    validateParquetFile(new Path(getOutputDir(), "input.parquet"), data);
    Assert.assertFalse(inputFile.exists());
  }

  @Test
  public void testDynamicConfiguration() throws Exception {
    File inputFile = new File(getInputDir(), "input.avro");

    List<Map<String, Object>> data = ImmutableList.of(
      (Map<String, Object>)new ImmutableMap.Builder<String, Object>()
        .put("id", new Utf8("keyboard"))
        .put("price", 666)
        .build()
    );

    generateAvroFile(AVRO_SCHEMA, inputFile, data);

    AvroParquetConfig conf = new AvroParquetConfig();
    conf.inputFile = "${record:value('/input')}";
    conf.outputDirectory = "${record:value('/output')}";

    MapReduceExecutor executor = generateExecutor(conf, Collections.<String, String>emptyMap());

    TargetRunner runner = new TargetRunner.Builder(MapReduceDExecutor.class, executor)
      .setOnRecordError(OnRecordError.TO_ERROR)
      .build();
    runner.runInit();

    Record record = RecordCreator.create();
    record.set(Field.create(ImmutableMap.of(
      "input", Field.create(inputFile.getAbsolutePath()),
      "output", Field.create(getOutputDir())
    )));

    runner.runWrite(ImmutableList.of(record));
    Assert.assertEquals(0, runner.getErrorRecords().size());
    runner.runDestroy();

    validateParquetFile(new Path(getOutputDir(), "input.parquet"), data);
    Assert.assertFalse(inputFile.exists());
  }

  @Test
  public void testDropInputFile() throws Exception {
    File inputFile = new File(getInputDir(), "input.avro");

    List<Map<String, Object>> data = ImmutableList.of(
      (Map<String, Object>)new ImmutableMap.Builder<String, Object>()
        .put("id", new Utf8("mouse"))
        .put("price", -2)
        .build()
    );

    generateAvroFile(AVRO_SCHEMA, inputFile, data);

    AvroParquetConfig conf = new AvroParquetConfig();
    conf.inputFile = inputFile.getAbsolutePath();
    conf.outputDirectory = getOutputDir();
    conf.keepInputFile = true;

    MapReduceExecutor executor = generateExecutor(conf, Collections.<String, String>emptyMap());

    TargetRunner runner = new TargetRunner.Builder(MapReduceDExecutor.class, executor)
      .setOnRecordError(OnRecordError.TO_ERROR)
      .build();
    runner.runInit();

    Record record = RecordCreator.create();
    record.set(Field.create(Collections.<String, Field>emptyMap()));

    runner.runWrite(ImmutableList.of(record));
    Assert.assertEquals(0, runner.getErrorRecords().size());
    runner.runDestroy();

    validateParquetFile(new Path(getOutputDir(), "input.parquet"), data);
    Assert.assertTrue(inputFile.exists());
  }

}