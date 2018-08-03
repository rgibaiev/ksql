/**
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.ksql.rest.server.resources.streaming;

import static io.confluent.ksql.rest.server.resources.streaming.TopicStream.Format.getFormatter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.ksql.rest.util.JsonMapper;
import io.confluent.ksql.util.SchemaUtil;
import java.io.IOException;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.utils.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TopicStream {

  public static class RecordFormatter {

    private static final Logger log = LoggerFactory.getLogger(RecordFormatter.class);
    private final SchemaRegistryClient schemaRegistryClient;
    private final String topicName;

    private Format format = Format.UNDEFINED;

    public RecordFormatter(SchemaRegistryClient schemaRegistryClient, String topicName) {
      this.schemaRegistryClient = schemaRegistryClient;
      this.topicName = topicName;
    }

    public List<String> format(ConsumerRecords<String, Bytes> records) {
      return StreamSupport
          .stream(records.records(topicName).spliterator(), false)
          .map((record) -> {
            if (record == null || record.value() == null) {
              return null;
            }
            if (format == Format.UNDEFINED) {
              format = getFormatter(topicName, record, schemaRegistryClient);
            }
            try {
              return format.print(record);
            } catch (IOException e) {
              log.warn("Exception formatting record", e);
              return null;
            }
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList());
    }

    public Format getFormat() {
      return format;
    }
  }

  public enum Format {

    UNDEFINED {
    },
    AVRO {
      private String topicName;
      private KafkaAvroDeserializer avroDeserializer;

      @Override
      public boolean isFormat(
          String topicName, ConsumerRecord<String, Bytes> record,
          SchemaRegistryClient schemaRegistryClient
      ) {
        this.topicName = topicName;
        try {
          avroDeserializer = new KafkaAvroDeserializer(schemaRegistryClient);
          avroDeserializer.deserialize(topicName, record.value().get());
          return true;
        } catch (Throwable t) {
          return false;
        }
      }

      @Override
      String print(ConsumerRecord<String, Bytes> consumerRecord) {
        String time = dateFormat.format(new Date(consumerRecord.timestamp()));
        GenericRecord record = (GenericRecord) avroDeserializer.deserialize(
            topicName,
            consumerRecord
                .value()
                .get()
        );
        String key = consumerRecord.key() != null ? consumerRecord.key() : "null";
        return time + ", " + key + ", " + record.toString() + "\n";
      }
    },
    JSON {
      final ObjectMapper objectMapper = JsonMapper.INSTANCE.mapper;

      @Override
      public boolean isFormat(
          String topicName, ConsumerRecord<String, Bytes> record,
          SchemaRegistryClient schemaRegistryClient
      ) {
        try {
          objectMapper.readTree(record.value().toString());
          return true;
        } catch (Throwable t) {
          return false;
        }
      }

      @Override
      String print(ConsumerRecord<String, Bytes> record) throws IOException {
        JsonNode jsonNode = objectMapper.readTree(record.value().toString());
        ObjectNode objectNode = objectMapper.createObjectNode();
        objectNode.put(SchemaUtil.ROWTIME_NAME, record.timestamp());
        objectNode.put(SchemaUtil.ROWKEY_NAME, (record.key() != null) ? record.key() : "null");
        objectNode.setAll((ObjectNode) jsonNode);
        StringWriter stringWriter = new StringWriter();
        objectMapper.writeValue(stringWriter, objectNode);
        return stringWriter.toString() + "\n";
      }
    },
    STRING {
      @Override
      public boolean isFormat(
          String topicName,
          ConsumerRecord<String, Bytes> record,
          SchemaRegistryClient schemaRegistryClient
      ) {
        /**
         * STRING always returns true because its last in the enum list
         */
        return true;
      }
    };

    final DateFormat dateFormat = SimpleDateFormat.getDateTimeInstance(3, 1, Locale.getDefault());

    static Format getFormatter(
        String topicName,
        ConsumerRecord<String, Bytes> record,
        SchemaRegistryClient schemaRegistryClient
    ) {
      Format result = Format.UNDEFINED;
      while (!(result.isFormat(topicName, record, schemaRegistryClient))) {
        result = Format.values()[result.ordinal() + 1];
      }
      return result;

    }

    boolean isFormat(
        String topicName,
        ConsumerRecord<String, Bytes> record,
        SchemaRegistryClient schemaRegistryClient
    ) {
      return false;
    }

    String print(final ConsumerRecord<String, Bytes> record) throws IOException {
      final String key = record.key() != null ? record.key() : "NULL";
      final String value = record.value() != null ? record.value().toString() : "NULL";
      return dateFormat.format(new Date(record.timestamp())) + " , " + key
          + " , " + value + "\n";
    }

  }
}
