/**
 * Copyright 2015 StreamSets Inc.
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
package com.streamsets.pipeline.stage.origin.lib;

import com.google.common.base.Joiner;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.Dependency;
import com.streamsets.pipeline.api.ListBeanModel;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.ValueChooserModel;
import com.streamsets.pipeline.api.impl.XMLChar;
import com.streamsets.pipeline.common.DataFormatConstants;
import com.streamsets.pipeline.config.AvroSchemaLookupMode;
import com.streamsets.pipeline.config.CharsetChooserValues;
import com.streamsets.pipeline.config.Compression;
import com.streamsets.pipeline.config.CompressionChooserValues;
import com.streamsets.pipeline.config.CsvHeader;
import com.streamsets.pipeline.config.CsvHeaderChooserValues;
import com.streamsets.pipeline.config.CsvMode;
import com.streamsets.pipeline.config.CsvModeChooserValues;
import com.streamsets.pipeline.config.CsvRecordType;
import com.streamsets.pipeline.config.CsvRecordTypeChooserValues;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.config.DatagramMode;
import com.streamsets.pipeline.config.DatagramModeChooserValues;
import com.streamsets.pipeline.config.JsonMode;
import com.streamsets.pipeline.config.JsonModeChooserValues;
import com.streamsets.pipeline.config.LogMode;
import com.streamsets.pipeline.config.LogModeChooserValues;
import com.streamsets.pipeline.config.OnParseError;
import com.streamsets.pipeline.config.OnParseErrorChooserValues;
import com.streamsets.pipeline.config.OriginAvroSchemaLookupModeChooserValues;
import com.streamsets.pipeline.config.OriginAvroSchemaSource;
import com.streamsets.pipeline.config.OriginAvroSchemaSourceChooserValues;
import com.streamsets.pipeline.lib.parser.DataParserFactory;
import com.streamsets.pipeline.lib.parser.DataParserFactoryBuilder;
import com.streamsets.pipeline.lib.parser.log.LogDataFormatValidator;
import com.streamsets.pipeline.lib.parser.log.LogDataParserFactory;
import com.streamsets.pipeline.lib.parser.log.RegExConfig;
import com.streamsets.pipeline.lib.parser.text.TextDataParserFactory;
import com.streamsets.pipeline.lib.parser.udp.DatagramParserFactory;
import com.streamsets.pipeline.lib.parser.xml.XmlDataParserFactory;
import com.streamsets.pipeline.lib.util.DelimitedDataConstants;
import com.streamsets.pipeline.lib.util.ProtobufConstants;
import com.streamsets.pipeline.stage.common.DataFormatConfig;
import com.streamsets.pipeline.stage.common.DataFormatErrors;
import com.streamsets.pipeline.stage.common.DataFormatGroups;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.SCHEMA_ID_KEY;
import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.SCHEMA_KEY;
import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.SCHEMA_REPO_URLS_KEY;
import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.SCHEMA_SOURCE_KEY;
import static com.streamsets.pipeline.lib.util.AvroSchemaHelper.SUBJECT_KEY;
import static com.streamsets.pipeline.stage.common.DataFormatErrors.DATA_FORMAT_11;

/**
 * Instances of this object must be called 'dataFormatConfig' exactly for error
 * messages to be placed in the correct location on the UI.
 */
public class DataParserFormatConfig implements DataFormatConfig{
  private static final String DEFAULT_REGEX =
      "^(\\S+) (\\S+) (\\S+) \\[([\\w:/]+\\s[+\\-]\\d{4})\\] \"(\\S+) (\\S+) (\\S+)\" (\\d{3}) (\\d+)";
  private static final String DEFAULT_APACHE_CUSTOM_LOG_FORMAT = "%h %l %u %t \"%r\" %>s %b";
  private static final String DEFAULT_GROK_PATTERN = "%{COMMONAPACHELOG}";
  private static final String DEFAULT_LOG4J_CUSTOM_FORMAT = "%r [%t] %-5p %c %x - %m%n";

  private LogDataFormatValidator logDataFormatValidator;
  private DataParserFactory parserFactory;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "UTF-8",
      label = "Charset",
      displayPosition = 300,
      group = "#0",
      dependsOn = "dataFormat^",
      triggeredByValue = {"TEXT", "JSON", "DELIMITED", "XML", "LOG", "DATAGRAM"}
  )
  @ValueChooserModel(CharsetChooserValues.class)
  public String charset = "UTF-8";

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "false",
      label = "Ignore Control Characters",
      description = "Use only if required as it impacts reading performance",
      displayPosition = 310,
      group = "#0"
  )
  public boolean removeCtrlChars = false;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      description = "Compression formats gzip, bzip2, xz, lzma, Pack200, DEFLATE and Z are supported. " +
          "Archive formats 7z, ar, arj, cpio, dump, tar and zip are supported.",
      defaultValue = "NONE",
      label = "Compression Format",
      displayPosition = 320,
      group = "#0"
  )
  @ValueChooserModel(CompressionChooserValues.class)
  public Compression compression = Compression.NONE;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "File Name Pattern within Compressed Directory",
      description = "A glob or regular expression that defines the pattern of the file names within the compressed " +
          "directory.",
      defaultValue = "*",
      displayPosition = 330,
      group = "#0",
      dependsOn = "compression",
      triggeredByValue = {"ARCHIVE", "COMPRESSED_ARCHIVE"}
  )
  public String filePatternInArchive = "*";

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "1024",
      label = "Max Line Length",
      description = "Longer lines are truncated",
      displayPosition = 340,
      group = "TEXT",
      dependsOn = "dataFormat^",
      triggeredByValue = "TEXT",
      min = 1,
      max = Integer.MAX_VALUE
  )
  public int textMaxLineLen = 1024;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "false",
      label = "Use Custom Delimiter",
      description = "Use custom delimiters to create records",
      displayPosition = 342,
      group = "TEXT",
      dependsOn = "dataFormat^",
      triggeredByValue = "TEXT"
  )
  public boolean useCustomDelimiter = TextDataParserFactory.USE_CUSTOM_DELIMITER_DEFAULT;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = TextDataParserFactory.CUSTOM_DELIMITER_DEFAULT,
      label = "Custom Delimiter",
      description = "One or more characters. Leading and trailing spaces are stripped.",
      displayPosition = 344,
      group = "TEXT",
      dependsOn = "useCustomDelimiter",
      triggeredByValue = "true"
  )
  public String customDelimiter = TextDataParserFactory.CUSTOM_DELIMITER_DEFAULT;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "\r\n",
      label = "Include Custom Delimiter",
      description = "Include custom delimiters in the data",
      displayPosition = 346,
      group = "TEXT",
      dependsOn = "useCustomDelimiter",
      triggeredByValue = "true"
  )
  public boolean includeCustomDelimiterInTheText = TextDataParserFactory.INCLUDE_CUSTOM_DELIMITER_IN_TEXT_DEFAULT;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "MULTIPLE_OBJECTS",
      label = "JSON Content",
      description = "",
      displayPosition = 350,
      group = "JSON",
      dependsOn = "dataFormat^",
      triggeredByValue = "JSON"
  )
  @ValueChooserModel(JsonModeChooserValues.class)
  public JsonMode jsonContent = JsonMode.MULTIPLE_OBJECTS;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "4096",
      label = "Max Object Length (chars)",
      description = "Larger objects are not processed",
      displayPosition = 360,
      group = "JSON",
      dependsOn = "dataFormat^",
      triggeredByValue = "JSON",
      min = 1,
      max = Integer.MAX_VALUE
  )
  public int jsonMaxObjectLen = 4096;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "CSV",
      label = "Delimiter Format Type",
      description = "",
      displayPosition = 370,
      group = "DELIMITED",
      dependsOn = "dataFormat^",
      triggeredByValue = "DELIMITED"
  )
  @ValueChooserModel(CsvModeChooserValues.class)
  public CsvMode csvFileFormat = CsvMode.CSV;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "NO_HEADER",
      label = "Header Line",
      description = "",
      displayPosition = 380,
      group = "DELIMITED",
      dependsOn = "dataFormat^",
      triggeredByValue = "DELIMITED"
  )
  @ValueChooserModel(CsvHeaderChooserValues.class)
  public CsvHeader csvHeader = CsvHeader.NO_HEADER;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "1024",
      label = "Max Record Length (chars)",
      description = "Larger objects are not processed",
      displayPosition = 390,
      group = "DELIMITED",
      dependsOn = "dataFormat^",
      triggeredByValue = "DELIMITED",
      min = 1,
      max = Integer.MAX_VALUE
  )
  public int csvMaxObjectLen = 1024;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.CHARACTER,
      defaultValue = "|",
      label = "Delimiter Character",
      displayPosition = 400,
      group = "DELIMITED",
      dependsOn = "csvFileFormat",
      triggeredByValue = "CUSTOM"
  )
  public char csvCustomDelimiter = '|';

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.CHARACTER,
      defaultValue = "\\",
      label = "Escape Character",
      displayPosition = 410,
      group = "DELIMITED",
      dependsOn = "csvFileFormat",
      triggeredByValue = "CUSTOM"
  )
  public char csvCustomEscape = '\\';

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.CHARACTER,
      defaultValue = "\"",
      label = "Quote Character",
      displayPosition = 420,
      group = "DELIMITED",
      dependsOn = "csvFileFormat",
      triggeredByValue = "CUSTOM"
  )
  public char csvCustomQuote = '\"';

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "LIST_MAP",
      label = "Root Field Type",
      description = "",
      displayPosition = 430,
      group = "DELIMITED",
      dependsOn = "dataFormat^",
      triggeredByValue = "DELIMITED"
  )
  @ValueChooserModel(CsvRecordTypeChooserValues.class)
  public CsvRecordType csvRecordType = CsvRecordType.LIST_MAP;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "0",
      label = "Lines to Skip",
      description = "Number of lines to skip before reading",
      displayPosition = 435,
      group = "DELIMITED",
      dependsOn = "dataFormat^",
      triggeredByValue = "DELIMITED",
      min = 0
  )
  public int csvSkipStartLines;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "false",
      label = "Parse NULLs",
      description = "When checked, configured string constant will be converted into NULL field.",
      displayPosition = 436,
      group = "DELIMITED",
      dependsOn = "dataFormat^",
      triggeredByValue = "DELIMITED"
  )
  public boolean parseNull;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.STRING,
      defaultValue = "\\\\N",
      label = "NULL constant",
      description = "String constant that should be converted to a NULL rather then passed as it is.",
      displayPosition = 437,
      group = "DELIMITED",
      dependsOn = "parseNull",
      triggeredByValue = "true"
  )
  public String nullConstant;

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.STRING,
      label = "Delimiter Element",
      defaultValue = "",
      description = "XML element that acts as a record delimiter. No delimiter will treat the whole XML document as one record.",
      displayPosition = 440,
      group = "XML",
      dependsOn = "dataFormat^",
      triggeredByValue = "XML"
  )
  public String xmlRecordElement = "";

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "4096",
      label = "Max Record Length (chars)",
      description = "Larger records are not processed",
      displayPosition = 450,
      group = "XML",
      dependsOn = "dataFormat^",
      triggeredByValue = "XML",
      min = 1,
      max = Integer.MAX_VALUE
  )
  public int xmlMaxObjectLen = 4096;

  // LOG Configuration

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "COMMON_LOG_FORMAT",
      label = "Log Format",
      description = "",
      displayPosition = 460,
      group = "LOG",
      dependsOn = "dataFormat^",
      triggeredByValue = "LOG"
  )
  @ValueChooserModel(LogModeChooserValues.class)
  public LogMode logMode = LogMode.COMMON_LOG_FORMAT;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "1024",
      label = "Max Line Length",
      description = "Longer lines are truncated",
      displayPosition = 470,
      group = "LOG",
      dependsOn = "dataFormat^",
      triggeredByValue = "LOG",
      min = 1,
      max = Integer.MAX_VALUE
  )
  public int logMaxObjectLen = 1024;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "false",
      label = "Retain Original Line",
      description = "Indicates if the original line of log should be retained in the record",
      displayPosition = 480,
      group = "LOG",
      dependsOn = "dataFormat^",
      triggeredByValue = "LOG"
  )
  public boolean retainOriginalLine = false;

  //APACHE_CUSTOM_LOG_FORMAT
  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = DEFAULT_APACHE_CUSTOM_LOG_FORMAT,
      label = "Custom Log Format",
      description = "",
      displayPosition = 490,
      group = "LOG",
      dependsOn = "logMode",
      triggeredByValue = "APACHE_CUSTOM_LOG_FORMAT"
  )
  public String customLogFormat = DEFAULT_APACHE_CUSTOM_LOG_FORMAT;

  //REGEX

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = DEFAULT_REGEX,
      label = "Regular Expression",
      description = "The regular expression which is used to parse the log line.",
      displayPosition = 500,
      group = "LOG",
      dependsOn = "logMode",
      triggeredByValue = "REGEX"
  )
  public String regex = DEFAULT_REGEX;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "",
      label = "Field Path To RegEx Group Mapping",
      description = "Map groups in the regular expression to field paths",
      displayPosition = 510,
      group = "LOG",
      dependsOn = "logMode",
      triggeredByValue = "REGEX"
  )
  @ListBeanModel
  public List<RegExConfig> fieldPathsToGroupName = new ArrayList<>();

  //GROK

  @ConfigDef(
      required = false,
      type = ConfigDef.Type.TEXT,
      defaultValue = "",
      label = "Grok Pattern Definition",
      description = "Define your own grok patterns which will be used to parse the logs",
      displayPosition = 520,
      group = "LOG",
      dependsOn = "logMode",
      triggeredByValue = "GROK",
      mode = ConfigDef.Mode.PLAIN_TEXT
  )
  public String grokPatternDefinition = "";

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = DEFAULT_GROK_PATTERN,
      label = "Grok Pattern",
      description = "The grok pattern which is used to parse the log line",
      displayPosition = 530,
      group = "LOG",
      dependsOn = "logMode",
      triggeredByValue = "GROK"
  )
  public String grokPattern = DEFAULT_GROK_PATTERN;

  //LOG4J

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      defaultValue = "ERROR",
      label = "On Parse Error",
      description = "",
      displayPosition = 540,
      group = "LOG",
      dependsOn = "logMode",
      triggeredByValue = "LOG4J"
  )
  @ValueChooserModel(OnParseErrorChooserValues.class)
  public OnParseError onParseError = OnParseError.ERROR;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "50",
      label = "Trim Stack Trace to Length",
      description = "Any line that does not match the expected pattern will be treated as a Stack trace as long as it " +
          "is part of the same message. The stack trace will be trimmed to the specified number of lines.",
      displayPosition = 550,
      group = "LOG",
      dependsOn = "onParseError",
      triggeredByValue = "INCLUDE_AS_STACK_TRACE",
      min = 0,
      max = Integer.MAX_VALUE
  )
  public int maxStackTraceLines = 50;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "false",
      label = "Use Custom Log Format",
      description = "",
      displayPosition = 560,
      group = "LOG",
      dependsOn = "logMode",
      triggeredByValue = "LOG4J"
  )
  public boolean enableLog4jCustomLogFormat = false;


  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = DEFAULT_LOG4J_CUSTOM_FORMAT,
      label = "Custom Log4J Format",
      description = "Specify your own custom log4j format.",
      displayPosition = 570,
      group = "LOG",
      dependsOn = "enableLog4jCustomLogFormat",
      triggeredByValue = "true"
  )
  public String log4jCustomLogFormat = DEFAULT_LOG4J_CUSTOM_FORMAT;

  //AVRO

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      label = "Avro Schema Location",
      description = "Where to load the Avro Schema from.",
      displayPosition = 400,
      dependsOn = "dataFormat^",
      triggeredByValue = "AVRO",
      group = "AVRO"
  )
  @ValueChooserModel(OriginAvroSchemaSourceChooserValues.class)
  public OriginAvroSchemaSource avroSchemaSource;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.TEXT,
      label = "Avro Schema",
      description = "Overrides the schema included in the data (if any). Optionally use the runtime:loadResource " +
          "function to use a schema stored in a file.",
      displayPosition = 410,
      group = "AVRO",
      dependencies = {
          @Dependency(configName = "dataFormat^", triggeredByValues = "AVRO"),
          @Dependency(configName = "avroSchemaSource", triggeredByValues = "INLINE")
      },
      mode = ConfigDef.Mode.JSON
  )
  public String avroSchema;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.LIST,
      label = "Schema Registry URLs",
      description = "List of Confluent Schema Registry URLs",
      dependencies = {
          @Dependency(configName = "dataFormat^", triggeredByValues = "AVRO"),
          @Dependency(configName = "avroSchemaSource", triggeredByValues = "REGISTRY")
      },
      displayPosition = 420,
      group = "AVRO"

  )
  public List<String> schemaRegistryUrls = new ArrayList<>();

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.MODEL,
      label = "Lookup Schema By",
      description = "Whether to look up the Avro Schema by ID or fetch the latest schema for a Subject.",
      defaultValue = "SUBJECT",
      dependsOn = "avroSchemaSource",
      triggeredByValue = "REGISTRY",
      displayPosition = 430,
      group = "AVRO"
  )
  @ValueChooserModel(OriginAvroSchemaLookupModeChooserValues.class)
  public AvroSchemaLookupMode schemaLookupMode = AvroSchemaLookupMode.AUTO;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      label = "Schema Subject",
      dependencies = {
          @Dependency(configName = "dataFormat^", triggeredByValues = "AVRO"),
          @Dependency(configName = "avroSchemaSource", triggeredByValues = "REGISTRY"),
          @Dependency(configName = "schemaLookupMode", triggeredByValues = "SUBJECT"),
      },
      displayPosition = 440,
      group = "AVRO"
  )
  public String subject;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      label = "Schema ID",
      min = 1,
      dependencies = {
          @Dependency(configName = "dataFormat^", triggeredByValues = "AVRO"),
          @Dependency(configName = "avroSchemaSource", triggeredByValues = "REGISTRY"),
          @Dependency(configName = "schemaLookupMode", triggeredByValues = "ID"),
      },
      displayPosition = 450,
      group = "AVRO"
  )
  public int schemaId;

  // PROTOBUF

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = "",
      label = "Protobuf Descriptor File",
      description = "Protobuf Descriptor File (.desc) path relative to SDC resources directory",
      displayPosition = 600,
      group = "PROTOBUF",
      dependsOn = "dataFormat^",
      triggeredByValue = "PROTOBUF"
  )
  public String protoDescriptorFile = "";

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = "",
      description = "Fully Qualified Message Type name. Use format <packageName>.<messageTypeName>",
      label = "Message Type",
      displayPosition = 610,
      group = "PROTOBUF",
      dependsOn = "dataFormat^",
      triggeredByValue = "PROTOBUF"
  )
  public String messageType = "";

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "true",
      label = "Delimited Messages",
      description = "Should be checked when the input data is prepended with the message size. When unchecked " +
          "only a single message can be present in the source file/Kafka message, etc.",
      displayPosition = 620,
      group = "PROTOBUF",
      dependsOn = "dataFormat^",
      triggeredByValue = "PROTOBUF"
  )
  public boolean isDelimited = true;

  // BINARY

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "1024",
      label = "Max Data Size (bytes)",
      description = "Larger objects are not processed",
      displayPosition = 700,
      group = "BINARY",
      dependsOn = "dataFormat^",
      triggeredByValue = "BINARY",
      min = 1,
      max = Integer.MAX_VALUE
  )
  public int binaryMaxObjectLen;

  // DATAGRAM

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.MODEL,
    label = "Data Format",
    defaultValue = "SYSLOG",
    group = "DATAGRAM",
    displayPosition = 800,
    dependsOn = "dataFormat^",
    triggeredByValue = "DATAGRAM"
  )
  @ValueChooserModel(DatagramModeChooserValues.class)
  public DatagramMode datagramMode;

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.STRING,
    label = "TypesDB File Path",
    description = "User-specified TypesDB file. Overrides the included version.",
    displayPosition = 820,
    group = "DATAGRAM",
    dependsOn = "datagramMode",
    triggeredByValue = "COLLECTD"
  )
  public String typesDbPath;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.BOOLEAN,
    defaultValue = "false",
    label = "Convert Hi-Res Time & Interval",
    description = "Converts high resolution time format interval and timestamp to unix time in (ms).",
    displayPosition = 830,
    group = "DATAGRAM",
    dependsOn = "datagramMode",
    triggeredByValue = "COLLECTD"
  )
  public boolean convertTime;

  @ConfigDef(
    required = true,
    type = ConfigDef.Type.BOOLEAN,
    defaultValue = "true",
    label = "Exclude Interval",
    description = "Excludes the interval field from output records.",
    displayPosition = 840,
    group = "DATAGRAM",
    dependsOn = "datagramMode",
    triggeredByValue = "COLLECTD"
  )
  public boolean excludeInterval;

  @ConfigDef(
    required = false,
    type = ConfigDef.Type.STRING,
    label = "Auth File",
    description = "",
    displayPosition = 850,
    group = "DATAGRAM",
    dependsOn = "datagramMode",
    triggeredByValue = "COLLECTD"
  )
  public String authFilePath;

  //Whole File
  @ConfigDef(
      required = true,
      type = ConfigDef.Type.NUMBER,
      defaultValue = "8192",
      label = "Buffer Size (bytes)",
      description = "Size of the Buffer used to copy the file.",
      displayPosition = 900,
      group = "WHOLE_FILE",
      dependsOn = "dataFormat^",
      triggeredByValue = "WHOLE_FILE",
      min = 1,
      max = Integer.MAX_VALUE
  )
  //Optimal 8KB
  public int wholeFileMaxObjectLen = 8 * 1024;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.BOOLEAN,
      defaultValue = "false",
      label = "Verify Checksum",
      description = "When checked verifies the checksum of the stream during read.",
      displayPosition = 1000,
      group = "WHOLE_FILE",
      dependsOn = "dataFormat^",
      triggeredByValue = "WHOLE_FILE"
  )
  public boolean verifyChecksum = false;

  @Override
  public boolean init(
      Stage.Context context,
      DataFormat dataFormat,
      String stageGroup,
      String configPrefix,
      List<Stage.ConfigIssue> issues
  ) {
    return init(
        context,
        dataFormat,
        stageGroup,
        configPrefix,
        DataFormatConstants.MAX_OVERRUN_LIMIT,
        false,
        issues
    );
  }

  public boolean init(
      Stage.Context context,
      DataFormat dataFormat,
      String stageGroup,
      String configPrefix,
      boolean multiLines,
      List<Stage.ConfigIssue> issues
  ) {
    return init(
        context,
        dataFormat,
        stageGroup,
        configPrefix,
        DataFormatConstants.MAX_OVERRUN_LIMIT,
        multiLines,
        issues
    );
  }

  public boolean init(
      Stage.Context context,
      DataFormat dataFormat,
      String stageGroup,
      String configPrefix,
      int overrunLimit,
      List<Stage.ConfigIssue> issues
  ) {
    return init(
        context,
        dataFormat,
        stageGroup,
        configPrefix,
        overrunLimit,
        false,
        issues
    );
  }

  public boolean init(
      Stage.Context context,
      DataFormat dataFormat,
      String stageGroup,
      String configPrefix,
      int overrunLimit,
      boolean multiLines,
      List<Stage.ConfigIssue> issues
  ) {
    boolean valid = true;
    switch (dataFormat) {
      case JSON:
        valid = validateJson(context, configPrefix, issues);
        break;
      case TEXT:
        valid = validateText(context, configPrefix, issues);
        break;
      case DELIMITED:
        valid = validateDelimited(context, configPrefix, issues);
        break;
      case XML:
        valid = validateXml(context, configPrefix, issues);
        break;
      case LOG:
        validateLogFormat(context, configPrefix, issues);
        break;
      case PROTOBUF:
        validateProtobuf(context, configPrefix, issues);
        break;
      case DATAGRAM:
        if (datagramMode == DatagramMode.COLLECTD) {
          checkCollectdParserConfigs(context, configPrefix, issues);
        }
        break;
      case WHOLE_FILE:
        valid = validateWholeFile(context, configPrefix, issues);
        break;
      case SDC_JSON:
      case BINARY:
      case AVRO:
        break;
      default:
        issues.add(context.createConfigIssue(
            stageGroup,
            configPrefix + "dataFormat",
            DataFormatErrors.DATA_FORMAT_04,
            dataFormat
        ));
        valid = false;
        break;
    }

    valid &= validateDataParser(
        context,
        dataFormat,
        stageGroup,
        configPrefix,
        overrunLimit,
        multiLines,
        issues
    );

    return valid;
  }

  private boolean validateJson(Stage.Context context, String configPrefix, List<Stage.ConfigIssue> issues) {
    boolean valid = true;
    if (jsonMaxObjectLen < 1) {
      issues.add(
          context.createConfigIssue(
              DataFormatGroups.JSON.name(),
              configPrefix + "jsonMaxObjectLen",
              DataFormatErrors.DATA_FORMAT_01
          )
      );
      valid = false;
    }
    return valid;
  }

  private boolean validateText(Stage.Context context, String configPrefix, List<Stage.ConfigIssue> issues) {
    boolean valid = true;
    if (textMaxLineLen < 1) {
      issues.add(
          context.createConfigIssue(
              DataFormatGroups.TEXT.name(),
              configPrefix + "textMaxLineLen",
              DataFormatErrors.DATA_FORMAT_01
          )
      );
      valid = false;
    }
    if (useCustomDelimiter && customDelimiter.isEmpty()) {
      issues.add(
          context.createConfigIssue(
              DataFormatGroups.TEXT.name(),
              configPrefix + "customDelimiter",
              DataFormatErrors.DATA_FORMAT_200
          )
      );
      valid = false;
    }
    return valid;
  }

  private boolean validateDelimited(Stage.Context context, String configPrefix, List<Stage.ConfigIssue> issues) {
    boolean valid = true;
    if (csvMaxObjectLen < 1) {
      issues.add(
          context.createConfigIssue(
              DataFormatGroups.DELIMITED.name(),
              configPrefix + "csvMaxObjectLen",
              DataFormatErrors.DATA_FORMAT_01
          )
      );
      valid = false;
    }
    return valid;
  }

  private boolean validateXml(Stage.Context context, String configPrefix, List<Stage.ConfigIssue> issues) {
    boolean valid = true;
    if (xmlMaxObjectLen < 1) {
      issues.add(
          context.createConfigIssue(
              DataFormatGroups.XML.name(),
              configPrefix + "xmlMaxObjectLen",
              DataFormatErrors.DATA_FORMAT_01
          )
      );
      valid = false;
    }
    if (xmlRecordElement != null && !xmlRecordElement.isEmpty() && !XMLChar.isValidName(xmlRecordElement)) {
      issues.add(
          context.createConfigIssue(DataFormatGroups.XML.name(),
              configPrefix + "xmlRecordElement",
              DataFormatErrors.DATA_FORMAT_03,
              xmlRecordElement
          )
      );
      valid = false;
    }
    return valid;
  }

  private boolean validateWholeFile(
      Stage.Context context, String configPrefix, List<Stage.ConfigIssue> issues) {
    boolean valid = true;
    if (wholeFileMaxObjectLen < 1) {
      issues.add(
          context.createConfigIssue(
              DataFormatGroups.WHOLE_FILE.name(),
              configPrefix + "wholeFileMaxObjectLen",
              DataFormatErrors.DATA_FORMAT_01
          )
      );
      valid = false;
    }
    return valid;
  }

  private void validateProtobuf(Stage.Context context, String configPrefix, List<Stage.ConfigIssue> issues) {
    if (protoDescriptorFile == null || protoDescriptorFile.isEmpty()) {
      issues.add(
          context.createConfigIssue(
              DataFormatGroups.PROTOBUF.name(),
              configPrefix + "protoDescriptorFile",
              DataFormatErrors.DATA_FORMAT_07
          )
      );
    } else {
      File file = new File(context.getResourcesDirectory(), protoDescriptorFile);
      if (!file.exists()) {
        issues.add(
            context.createConfigIssue(
                DataFormatGroups.PROTOBUF.name(),
                configPrefix + "protoDescriptorFile",
                DataFormatErrors.DATA_FORMAT_09,
                file.getAbsolutePath()
            )
        );
      }
      if (messageType == null || messageType.isEmpty()) {
        issues.add(
            context.createConfigIssue(
                DataFormatGroups.PROTOBUF.name(),
                configPrefix + "messageType",
                DataFormatErrors.DATA_FORMAT_08
            )
        );
      }
    }
  }

  private void validateLogFormat(Stage.Context context, String configPrefix, List<Stage.ConfigIssue> issues) {
    logDataFormatValidator = new LogDataFormatValidator(
        logMode,
        logMaxObjectLen,
        retainOriginalLine,
        customLogFormat,
        regex,
        grokPatternDefinition,
        grokPattern,
        enableLog4jCustomLogFormat,
        log4jCustomLogFormat,
        onParseError,
        maxStackTraceLines,
        DataFormatGroups.LOG.name(),
        getFieldPathToGroupMap(fieldPathsToGroupName)
    );
    logDataFormatValidator.validateLogFormatConfig(context, configPrefix, issues);
  }

  private void checkCollectdParserConfigs(Stage.Context context, String configPrefix, List<Stage.ConfigIssue> issues) {
    if (null != typesDbPath && !typesDbPath.isEmpty()) {
      File typesDbFile = new File(typesDbPath);
      if (!typesDbFile.canRead() || !typesDbFile.isFile()) {
        issues.add(
            context.createConfigIssue(
                DataFormatGroups.DATAGRAM.name(),
                configPrefix + "typesDbPath",
                DataFormatErrors.DATA_FORMAT_400, typesDbPath
            )
        );
      }
    }
    if (null != authFilePath && !authFilePath.isEmpty()) {
      File authFile = new File(authFilePath);
      if (!authFile.canRead() || !authFile.isFile()) {
        issues.add(
            context.createConfigIssue(
                DataFormatGroups.DATAGRAM.name(),
                configPrefix + "authFilePath",
                DataFormatErrors.DATA_FORMAT_401, authFilePath
            )
        );
      }
    }
  }

  private boolean validateDataParser(
      Stage.Context context,
      DataFormat dataFormat,
      String stageGroup,
      String configPrefix,
      int overrunLimit,
      boolean multiLines,
      List<Stage.ConfigIssue> issues
  ) {
    boolean valid = true;
    DataParserFactoryBuilder builder = new DataParserFactoryBuilder(context, dataFormat.getParserFormat());
    Charset fileCharset;

    try {
      fileCharset = Charset.forName(charset);
    } catch (UnsupportedCharsetException ignored) { // NOSONAR
      // setting it to a valid one so the parser factory can be configured and tested for more errors
      fileCharset = StandardCharsets.UTF_8;
      issues.add(
          context.createConfigIssue(
              stageGroup,
              configPrefix + "charset",
              DataFormatErrors.DATA_FORMAT_05,
              charset
          )
      );
      valid = false;
    }
    builder.setCharset(fileCharset);
    builder.setOverRunLimit(overrunLimit);
    builder.setRemoveCtrlChars(removeCtrlChars);
    builder.setCompression(compression);
    builder.setFilePatternInArchive(filePatternInArchive);

    switch (dataFormat) {
      case TEXT:
        builder
            .setMaxDataLen(textMaxLineLen)
            .setConfig(TextDataParserFactory.MULTI_LINE_KEY, multiLines)
            .setConfig(TextDataParserFactory.USE_CUSTOM_DELIMITER_KEY, useCustomDelimiter)
            .setConfig(TextDataParserFactory.CUSTOM_DELIMITER_KEY, customDelimiter)
            .setConfig(TextDataParserFactory.INCLUDE_CUSTOM_DELIMITER_IN_TEXT_KEY, includeCustomDelimiterInTheText);
        break;
      case JSON:
        builder.setMaxDataLen(jsonMaxObjectLen).setMode(jsonContent);
        break;
      case DELIMITED:
        buildDelimitedParser(builder);
        break;
      case XML:
        builder.setMaxDataLen(xmlMaxObjectLen).setConfig(XmlDataParserFactory.RECORD_ELEMENT_KEY, xmlRecordElement);
        break;
      case SDC_JSON:
        builder.setMaxDataLen(-1);
        break;
      case BINARY:
        builder.setMaxDataLen(binaryMaxObjectLen);
        break;
      case LOG:
        builder.setConfig(LogDataParserFactory.MULTI_LINES_KEY, multiLines);
        logDataFormatValidator.populateBuilder(builder);
        break;
      case AVRO:
        buildAvroParser(builder);
        break;
      case PROTOBUF:
        buildProtobufParser(builder);
        break;
      case DATAGRAM:
        buildDatagramParser(builder);
        break;
      case WHOLE_FILE:
        builder.setMaxDataLen(wholeFileMaxObjectLen);
        break;
      default:
        throw new IllegalStateException("Unexpected data format" + dataFormat);
    }
    try {
      parserFactory = builder.build();
    } catch (Exception ex) {
      issues.add(context.createConfigIssue(null, null, DataFormatErrors.DATA_FORMAT_06, ex.toString(), ex));
      valid = false;
    }
    return valid;
  }

  private void buildAvroParser(DataParserFactoryBuilder builder) {
    builder
        .setMaxDataLen(-1)
        .setConfig(SCHEMA_KEY, avroSchema)
        .setConfig(SCHEMA_SOURCE_KEY, avroSchemaSource)
        .setConfig(SCHEMA_REPO_URLS_KEY, schemaRegistryUrls);
    if (schemaLookupMode == AvroSchemaLookupMode.SUBJECT) {
      // Subject used for looking up schema
      builder.setConfig(SUBJECT_KEY, subject);
    } else {
      // Schema ID used for looking up schema
      builder.setConfig(SCHEMA_ID_KEY, schemaId);
    }
  }

  private void buildDelimitedParser(DataParserFactoryBuilder builder) {
    builder
        .setMaxDataLen(csvMaxObjectLen)
        .setMode(csvFileFormat).setMode(csvHeader)
        .setMode(csvRecordType)
        .setConfig(DelimitedDataConstants.SKIP_START_LINES, csvSkipStartLines)
        .setConfig(DelimitedDataConstants.DELIMITER_CONFIG, csvCustomDelimiter)
        .setConfig(DelimitedDataConstants.ESCAPE_CONFIG, csvCustomEscape)
        .setConfig(DelimitedDataConstants.QUOTE_CONFIG, csvCustomQuote)
        .setConfig(DelimitedDataConstants.PARSE_NULL, parseNull)
        .setConfig(DelimitedDataConstants.NULL_CONSTANT, nullConstant);
  }

  private void buildProtobufParser(DataParserFactoryBuilder builder) {
    builder
        .setConfig(ProtobufConstants.PROTO_DESCRIPTOR_FILE_KEY, protoDescriptorFile)
        .setConfig(ProtobufConstants.MESSAGE_TYPE_KEY, messageType)
        .setConfig(ProtobufConstants.DELIMITED_KEY, isDelimited)
        .setMaxDataLen(-1);
  }

  private void buildDatagramParser(DataParserFactoryBuilder builder) {
    builder
      .setConfig(DatagramParserFactory.CONVERT_TIME_KEY, convertTime)
      .setConfig(DatagramParserFactory.EXCLUDE_INTERVAL_KEY, excludeInterval)
      .setConfig(DatagramParserFactory.AUTH_FILE_PATH_KEY, authFilePath)
      .setConfig(DatagramParserFactory.TYPES_DB_PATH_KEY, typesDbPath)
      .setMode(datagramMode)
      .setMaxDataLen(-1);
  }

  /**
   * Returns the DataParserFactory instance.
   *
   * The DataParserFactory instance is not thread safe.
   * To improve performance the DataParserFactory instance may share a buffer among the data parser instances that
   * it creates.
   *
   * @return
   */
  public DataParserFactory getParserFactory() {
    return parserFactory;
  }

  private static Map<String, Integer> getFieldPathToGroupMap(List<RegExConfig> fieldPathsToGroupName) {
    if (fieldPathsToGroupName == null) {
      return new HashMap<>();
    }
    Map<String, Integer> fieldPathToGroup = new HashMap<>();
    for (RegExConfig r : fieldPathsToGroupName) {
      fieldPathToGroup.put(r.fieldPath, r.group);
    }
    return fieldPathToGroup;
  }

  /**
   * This is used to make sure AUTO schema lookup mode is not used for
   * stages that do not support it (e.g. file based ones).
   * This includes HDFS, Directory, S3, SFTP
   * @param dataFormat DataFormat selected in the config
   * @param configBeanPrefix prefix for the config bean so the validation errors are shown in the correct place
   * @param context stage context
   * @param issues list of issues to add any validation errors to.
   */
  public void checkForInvalidAvroSchemaLookupMode(
      DataFormat dataFormat,
      String configBeanPrefix,
      Stage.Context context,
      List<Stage.ConfigIssue> issues
  ) {
    if (dataFormat != DataFormat.AVRO) {
      return;
    }

    if (avroSchemaSource == OriginAvroSchemaSource.REGISTRY &&
        schemaLookupMode == AvroSchemaLookupMode.AUTO) {
      issues.add(context.createConfigIssue(
          "AVRO",
          Joiner.on(".").join(configBeanPrefix, "schemaLookupMode"),
          DATA_FORMAT_11
      ));
    }
  }
}
