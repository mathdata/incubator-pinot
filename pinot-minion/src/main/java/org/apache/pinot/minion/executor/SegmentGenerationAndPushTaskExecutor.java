/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.minion.executor;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.io.FileUtils;
import org.apache.pinot.common.utils.TarGzCompressionUtils;
import org.apache.pinot.core.minion.PinotTaskConfig;
import org.apache.pinot.plugin.ingestion.batch.common.SegmentGenerationTaskRunner;
import org.apache.pinot.plugin.ingestion.batch.common.SegmentGenerationUtils;
import org.apache.pinot.plugin.ingestion.batch.common.SegmentPushUtils;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.env.PinotConfiguration;
import org.apache.pinot.spi.filesystem.LocalPinotFS;
import org.apache.pinot.spi.filesystem.PinotFS;
import org.apache.pinot.spi.filesystem.PinotFSFactory;
import org.apache.pinot.spi.ingestion.batch.BatchConfigProperties;
import org.apache.pinot.spi.ingestion.batch.spec.Constants;
import org.apache.pinot.spi.ingestion.batch.spec.PinotClusterSpec;
import org.apache.pinot.spi.ingestion.batch.spec.PushJobSpec;
import org.apache.pinot.spi.ingestion.batch.spec.RecordReaderSpec;
import org.apache.pinot.spi.ingestion.batch.spec.SegmentGenerationJobSpec;
import org.apache.pinot.spi.ingestion.batch.spec.SegmentGenerationTaskSpec;
import org.apache.pinot.spi.ingestion.batch.spec.SegmentNameGeneratorSpec;
import org.apache.pinot.spi.ingestion.batch.spec.TableSpec;
import org.apache.pinot.spi.utils.DataSizeUtils;
import org.apache.pinot.spi.utils.IngestionConfigUtils;
import org.apache.pinot.spi.utils.JsonUtils;
import org.apache.pinot.spi.utils.retry.AttemptsExceededException;
import org.apache.pinot.spi.utils.retry.RetriableOperationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SegmentGenerationAndPushTaskExecutor extends BaseTaskExecutor {
  private static final Logger LOGGER = LoggerFactory.getLogger(SegmentGenerationAndPushTaskExecutor.class);

  private static final PinotFS LOCAL_PINOT_FS = new LocalPinotFS();
  private static final int DEFUALT_PUSH_ATTEMPTS = 5;
  private static final int DEFAULT_PUSH_PARALLELISM = 1;
  private static final long DEFAULT_PUSH_RETRY_INTERVAL_MILLIS = 1000L;

  @Override
  public Object executeTask(PinotTaskConfig pinotTaskConfig) {
    Map<String, String> taskConfigs = pinotTaskConfig.getConfigs();
    SegmentGenerationAndPushResult.Builder resultBuilder = new SegmentGenerationAndPushResult.Builder();
    File localTempDir = new File(FileUtils.getTempDirectory(), "pinot-" + UUID.randomUUID());

    try {
      // Generate Pinot Segment
      SegmentGenerationTaskSpec taskSpec = generateTaskSpec(taskConfigs, localTempDir);
      SegmentGenerationTaskRunner taskRunner = new SegmentGenerationTaskRunner(taskSpec);
      String segmentName = taskRunner.run();

      // Tar segment directory to compress file
      File localSegmentTarFile = tarSegmentDir(taskSpec, segmentName);

      //move segment to output PinotFS
      URI outputSegmentTarURI = moveSegmentToOutputPinotFS(taskConfigs, localSegmentTarFile);

      resultBuilder.setSegmentName(segmentName);
      // Segment push task
      pushSegment(taskSpec.getTableConfig().get(BatchConfigProperties.TABLE_NAME).asText(), taskConfigs,
          outputSegmentTarURI);
      resultBuilder.setSucceed(true);
    } catch (Exception e) {
      resultBuilder.setException(e);
    } finally {
      // Cleanup output dir
      FileUtils.deleteQuietly(localTempDir);
    }
    return resultBuilder.build();
  }

  private void pushSegment(String tableName, Map<String, String> taskConfigs, URI outputSegmentTarURI)
      throws Exception {
    String pushMode = taskConfigs.get(BatchConfigProperties.PUSH_MODE);

    PushJobSpec pushJobSpec = new PushJobSpec();
    pushJobSpec.setPushAttempts(DEFUALT_PUSH_ATTEMPTS);
    pushJobSpec.setPushParallelism(DEFAULT_PUSH_PARALLELISM);
    pushJobSpec.setPushRetryIntervalMillis(DEFAULT_PUSH_RETRY_INTERVAL_MILLIS);
    pushJobSpec.setSegmentUriPrefix(taskConfigs.get(BatchConfigProperties.PUSH_SEGMENT_URI_PREFIX));
    pushJobSpec.setSegmentUriSuffix(taskConfigs.get(BatchConfigProperties.PUSH_SEGMENT_URI_SUFFIX));

    SegmentGenerationJobSpec spec = generatePushJobSpec(tableName, taskConfigs, pushJobSpec);

    URI outputSegmentDirURI = URI.create(taskConfigs.get(BatchConfigProperties.OUTPUT_SEGMENT_DIR_URI));
    PinotFS outputFileFS = getPinotFS(outputSegmentDirURI);
    switch (BatchConfigProperties.SegmentPushType.valueOf(pushMode.toUpperCase())) {
      case TAR:
        try {
          SegmentPushUtils.pushSegments(spec, LOCAL_PINOT_FS, Arrays.asList(outputSegmentTarURI.toString()));
        } catch (RetriableOperationException | AttemptsExceededException e) {
          throw new RuntimeException(e);
        }
        break;
      case URI:
        try {
          List<String> segmentUris = new ArrayList<>();
          URI updatedURI = SegmentPushUtils
              .generateSegmentTarURI(outputSegmentDirURI, outputSegmentTarURI, pushJobSpec.getSegmentUriPrefix(),
                  pushJobSpec.getSegmentUriSuffix());
          segmentUris.add(updatedURI.toString());
          SegmentPushUtils.sendSegmentUris(spec, segmentUris);
        } catch (RetriableOperationException | AttemptsExceededException e) {
          throw new RuntimeException(e);
        }
        break;
      case METADATA:
        try {
          Map<String, String> segmentUriToTarPathMap = SegmentPushUtils
              .getSegmentUriToTarPathMap(outputSegmentDirURI, pushJobSpec.getSegmentUriPrefix(),
                  pushJobSpec.getSegmentUriSuffix(), new String[]{outputSegmentTarURI.toString()});
          SegmentPushUtils.sendSegmentUriAndMetadata(spec, outputFileFS, segmentUriToTarPathMap);
        } catch (RetriableOperationException | AttemptsExceededException e) {
          throw new RuntimeException(e);
        }
        break;
      default:
        throw new UnsupportedOperationException("Unrecognized push mode - " + pushMode);
    }
  }

  private SegmentGenerationJobSpec generatePushJobSpec(String tableName, Map<String, String> taskConfigs,
      PushJobSpec pushJobSpec) {

    TableSpec tableSpec = new TableSpec();
    tableSpec.setTableName(tableName);

    PinotClusterSpec pinotClusterSpec = new PinotClusterSpec();
    pinotClusterSpec.setControllerURI(taskConfigs.get(BatchConfigProperties.PUSH_CONTROLLER_URI));
    PinotClusterSpec[] pinotClusterSpecs = new PinotClusterSpec[]{pinotClusterSpec};

    SegmentGenerationJobSpec spec = new SegmentGenerationJobSpec();
    spec.setPushJobSpec(pushJobSpec);
    spec.setTableSpec(tableSpec);
    spec.setPinotClusterSpecs(pinotClusterSpecs);
    return spec;
  }

  private URI moveSegmentToOutputPinotFS(Map<String, String> taskConfigs, File localSegmentTarFile)
      throws Exception {
    URI outputSegmentDirURI = URI.create(taskConfigs.get(BatchConfigProperties.OUTPUT_SEGMENT_DIR_URI));
    PinotFS outputFileFS = getPinotFS(outputSegmentDirURI);
    URI outputSegmentTarURI = URI.create(outputSegmentDirURI + localSegmentTarFile.getName());
    if (!Boolean.parseBoolean(taskConfigs.get(BatchConfigProperties.OVERWRITE_OUTPUT)) && outputFileFS
        .exists(outputSegmentDirURI)) {
      LOGGER.warn("Not overwrite existing output segment tar file: {}", outputFileFS.exists(outputSegmentDirURI));
    } else {
      outputFileFS.copyFromLocalFile(localSegmentTarFile, outputSegmentTarURI);
    }
    return outputSegmentTarURI;
  }

  private PinotFS getPinotFS(URI fileURI) {
    String scheme = fileURI.getScheme();
    if (scheme == null) {
      scheme = PinotFSFactory.LOCAL_PINOT_FS_SCHEME;
    }
    return PinotFSFactory.create(scheme);
  }

  private File tarSegmentDir(SegmentGenerationTaskSpec taskSpec, String segmentName)
      throws IOException {
    File localOutputTempDir = new File(taskSpec.getOutputDirectoryPath());
    File localSegmentDir = new File(localOutputTempDir, segmentName);
    String segmentTarFileName = segmentName + Constants.TAR_GZ_FILE_EXT;
    File localSegmentTarFile = new File(localOutputTempDir, segmentTarFileName);
    LOGGER.info("Tarring segment from: {} to: {}", localSegmentDir, localSegmentTarFile);
    TarGzCompressionUtils.createTarGzFile(localSegmentDir, localSegmentTarFile);
    long uncompressedSegmentSize = FileUtils.sizeOf(localSegmentDir);
    long compressedSegmentSize = FileUtils.sizeOf(localSegmentTarFile);
    LOGGER.info("Size for segment: {}, uncompressed: {}, compressed: {}", segmentName,
        DataSizeUtils.fromBytes(uncompressedSegmentSize), DataSizeUtils.fromBytes(compressedSegmentSize));
    return localSegmentTarFile;
  }

  private SegmentGenerationTaskSpec generateTaskSpec(Map<String, String> taskConfigs, File localTempDir)
      throws Exception {
    SegmentGenerationTaskSpec taskSpec = new SegmentGenerationTaskSpec();
    URI inputFileURI = URI.create(taskConfigs.get(BatchConfigProperties.INPUT_FILE_URI));
    File localInputTempDir = new File(localTempDir, "input");
    FileUtils.forceMkdir(localInputTempDir);
    String inputFileURIScheme = inputFileURI.getScheme();
    if (inputFileURIScheme == null) {
      inputFileURIScheme = PinotFSFactory.LOCAL_PINOT_FS_SCHEME;
    }
    if (!PinotFSFactory.isSchemeSupported(inputFileURIScheme)) {
      String fsClass = taskConfigs.get(BatchConfigProperties.INPUT_FS_CLASS);
      PinotConfiguration fsProps = IngestionConfigUtils.getFsProps(taskConfigs);
      PinotFSFactory.register(inputFileURIScheme, fsClass, fsProps);
    }
    PinotFS inputFileFS = PinotFSFactory.create(inputFileURIScheme);

    File localOutputTempDir = new File(localTempDir, "output");
    FileUtils.forceMkdir(localOutputTempDir);
    taskSpec.setOutputDirectoryPath(localOutputTempDir.getAbsolutePath());

    //copy input path to local
    File localInputDataFile = new File(localInputTempDir, new File(inputFileURI.getPath()).getName());
    inputFileFS.copyToLocalFile(inputFileURI, localInputDataFile);
    taskSpec.setInputFilePath(localInputDataFile.getAbsolutePath());

    RecordReaderSpec recordReaderSpec = new RecordReaderSpec();
    recordReaderSpec.setDataFormat(taskConfigs.get(BatchConfigProperties.INPUT_FORMAT));
    recordReaderSpec.setClassName(taskConfigs.get(BatchConfigProperties.RECORD_READER_CLASS));
    recordReaderSpec.setConfigClassName(taskConfigs.get(BatchConfigProperties.RECORD_READER_CONFIG_CLASS));
    taskSpec.setRecordReaderSpec(recordReaderSpec);
    Schema schema;
    if (taskConfigs.containsKey(BatchConfigProperties.SCHEMA)) {
      schema = JsonUtils
          .stringToObject(JsonUtils.objectToString(taskConfigs.get(BatchConfigProperties.SCHEMA)), Schema.class);
    } else if (taskConfigs.containsKey(BatchConfigProperties.SCHEMA_URI)) {
      schema = SegmentGenerationUtils.getSchema(taskConfigs.get(BatchConfigProperties.SCHEMA_URI));
    } else {
      throw new RuntimeException(
          "Missing schema for segment generation job: please set `schema` or `schemaURI` in task config.");
    }
    taskSpec.setSchema(schema);
    JsonNode tableConfig = JsonUtils.stringToJsonNode(taskConfigs.get(BatchConfigProperties.TABLE_CONFIGS));
    taskSpec.setTableConfig(tableConfig);
    taskSpec.setSequenceId(Integer.parseInt(taskConfigs.get(BatchConfigProperties.SEQUENCE_ID)));
    SegmentNameGeneratorSpec segmentNameGeneratorSpec = new SegmentNameGeneratorSpec();
    segmentNameGeneratorSpec.setType(taskConfigs.get(BatchConfigProperties.SEGMENT_NAME_GENERATOR_TYPE));
    segmentNameGeneratorSpec.setConfigs(
        IngestionConfigUtils.getConfigMapWithPrefix(taskConfigs, BatchConfigProperties.SEGMENT_NAME_GENERATOR_CONFIGS));
    taskSpec.setSegmentNameGeneratorSpec(segmentNameGeneratorSpec);
    taskSpec.setCustomProperty(BatchConfigProperties.INPUT_DATA_FILE_URI_KEY, inputFileURI.toString());
    return taskSpec;
  }
}
