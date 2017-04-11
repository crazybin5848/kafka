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
package org.apache.kafka.common.requests;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.protocol.types.Struct;
import org.apache.kafka.common.utils.CollectionUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class WriteTxnMarkerRequest extends AbstractRequest {
    private static final String TXN_MARKER_ENTRY_KEY_NAME = "transaction_markers";

    private static final String PID_KEY_NAME = "pid";
    private static final String EPOCH_KEY_NAME = "epoch";
    private static final String TRANSACTION_RESULT_KEY_NAME = "transaction_result";
    private static final String COORDINATOR_EPOCH_KEY_NAME = "coordinator_epoch";
    private static final String TOPIC_PARTITIONS_KEY_NAME = "topic_partitions";
    private static final String TOPIC_KEY_NAME = "topic";
    private static final String PARTITIONS_KEY_NAME = "partitions";

    public static class TxnMarkerEntry {
        private final long pid;
        private final short epoch;
        private final int coordinatorEpoch;
        private final TransactionResult result;
        private final List<TopicPartition> partitions;

        public TxnMarkerEntry(long pid, short epoch, int coordinatorEpoch, TransactionResult result, List<TopicPartition> partitions) {
            this.pid = pid;
            this.epoch = epoch;
            this.result = result;
            this.partitions = partitions;
            this.coordinatorEpoch = coordinatorEpoch;
        }

        public long pid() {
            return pid;
        }

        public short epoch() {
            return epoch;
        }

        public TransactionResult transactionResult() {
            return result;
        }

        public List<TopicPartition> partitions() {
            return partitions;
        }

        public int coordinatorEpoch() {
            return coordinatorEpoch;
        }

        @Override
        public String toString() {
            return "TxnMarkerEntry{" +
                    "pid=" + pid +
                    ", epoch=" + epoch +
                    ", coordinatorEpoch=" + coordinatorEpoch +
                    ", result=" + result +
                    ", partitions=" + partitions +
                    '}';
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            final TxnMarkerEntry that = (TxnMarkerEntry) o;
            return pid == that.pid &&
                    epoch == that.epoch &&
                    coordinatorEpoch == that.coordinatorEpoch &&
                    result == that.result &&
                    Objects.equals(partitions, that.partitions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pid, epoch, coordinatorEpoch, result, partitions);
        }
    }

    public static class Builder extends AbstractRequest.Builder<WriteTxnMarkerRequest> {
        private final List<TxnMarkerEntry> markers;

        public Builder(List<TxnMarkerEntry> markers) {
            super(ApiKeys.WRITE_TXN_MARKER);

            this.markers = markers;
        }

        @Override
        public WriteTxnMarkerRequest build(short version) {
            return new WriteTxnMarkerRequest(version, markers);
        }
    }

    private final List<TxnMarkerEntry> markers;

    private WriteTxnMarkerRequest(short version, List<TxnMarkerEntry> markers) {
        super(version);

        this.markers = markers;
    }

    public WriteTxnMarkerRequest(Struct struct, short version) {
        super(version);

        List<TxnMarkerEntry> markers = new ArrayList<>();
        Object[] markersArray = struct.getArray(TXN_MARKER_ENTRY_KEY_NAME);
        for (Object markerObj : markersArray) {
            Struct markerStruct = (Struct) markerObj;

            long pid = markerStruct.getLong(PID_KEY_NAME);
            short epoch = markerStruct.getShort(EPOCH_KEY_NAME);
            int coordinatorEpoch = markerStruct.getInt(COORDINATOR_EPOCH_KEY_NAME);
            TransactionResult result = TransactionResult.forId(markerStruct.getByte(TRANSACTION_RESULT_KEY_NAME));

            List<TopicPartition> partitions = new ArrayList<>();
            Object[] topicPartitionsArray = markerStruct.getArray(TOPIC_PARTITIONS_KEY_NAME);
            for (Object topicPartitionObj : topicPartitionsArray) {
                Struct topicPartitionStruct = (Struct) topicPartitionObj;
                String topic = topicPartitionStruct.getString(TOPIC_KEY_NAME);
                for (Object partitionObj : topicPartitionStruct.getArray(PARTITIONS_KEY_NAME)) {
                    partitions.add(new TopicPartition(topic, (Integer) partitionObj));
                }
            }

            markers.add(new TxnMarkerEntry(pid, epoch, coordinatorEpoch, result, partitions));
        }

        this.markers = markers;
    }

    @Override
    protected Struct toStruct() {
        Struct struct = new Struct(ApiKeys.WRITE_TXN_MARKER.requestSchema(version()));

        Object[] markersArray = new Object[markers.size()];
        int i = 0;
        for (TxnMarkerEntry entry : markers) {
            Struct markerStruct = struct.instance(TXN_MARKER_ENTRY_KEY_NAME);
            markerStruct.set(PID_KEY_NAME, entry.pid);
            markerStruct.set(EPOCH_KEY_NAME, entry.epoch);
            markerStruct.set(COORDINATOR_EPOCH_KEY_NAME, entry.coordinatorEpoch);
            markerStruct.set(TRANSACTION_RESULT_KEY_NAME, entry.result.id);

            Map<String, List<Integer>> mappedPartitions = CollectionUtils.groupDataByTopic(entry.partitions);
            Object[] partitionsArray = new Object[mappedPartitions.size()];
            int j = 0;
            for (Map.Entry<String, List<Integer>> topicAndPartitions : mappedPartitions.entrySet()) {
                Struct topicPartitionsStruct = markerStruct.instance(TOPIC_PARTITIONS_KEY_NAME);
                topicPartitionsStruct.set(TOPIC_KEY_NAME, topicAndPartitions.getKey());
                topicPartitionsStruct.set(PARTITIONS_KEY_NAME, topicAndPartitions.getValue().toArray());
                partitionsArray[j++] = topicPartitionsStruct;
            }
            markerStruct.set(TOPIC_PARTITIONS_KEY_NAME, partitionsArray);
            markersArray[i++] = markerStruct;
        }
        struct.set(TXN_MARKER_ENTRY_KEY_NAME, markersArray);

        return struct;
    }

    @Override
    public WriteTxnMarkerResponse getErrorResponse(Throwable e) {
        Errors error = Errors.forException(e);

        Map<Long, Map<TopicPartition, Errors>> errors = new HashMap<>(markers.size());
        for (TxnMarkerEntry entry : markers) {
            Map<TopicPartition, Errors> errorsPerPartition = new HashMap<>(entry.partitions.size());
            for (TopicPartition partition : entry.partitions)
                errorsPerPartition.put(partition, error);

            errors.put(entry.pid, errorsPerPartition);
        }

        return new WriteTxnMarkerResponse(errors);
    }

    public static WriteTxnMarkerRequest parse(ByteBuffer buffer, short version) {
        return new WriteTxnMarkerRequest(ApiKeys.WRITE_TXN_MARKER.parseRequest(version, buffer), version);
    }

    public List<TxnMarkerEntry> markers() {
        return markers;
    }
}