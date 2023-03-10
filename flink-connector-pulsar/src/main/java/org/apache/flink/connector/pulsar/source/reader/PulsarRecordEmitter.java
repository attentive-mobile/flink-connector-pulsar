/*
 * Licensed to the Apache Software Foundation (ASF) under one
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

package org.apache.flink.connector.pulsar.source.reader;

import org.apache.flink.api.connector.source.SourceOutput;
import org.apache.flink.connector.base.source.reader.RecordEmitter;
import org.apache.flink.connector.pulsar.source.callback.SourceUserCallback;
import org.apache.flink.connector.pulsar.source.reader.deserializer.PulsarDeserializationSchema;
import org.apache.flink.connector.pulsar.source.split.PulsarPartitionSplitState;
import org.apache.flink.util.Collector;

import org.apache.pulsar.client.api.Message;

import javax.annotation.Nullable;

/**
 * The {@link RecordEmitter} implementation for {@link PulsarSourceReader}. We would always update
 * the last consumed message id in this emitter.
 */
public class PulsarRecordEmitter<T>
        implements RecordEmitter<Message<byte[]>, T, PulsarPartitionSplitState> {

    private final PulsarDeserializationSchema<T> deserializationSchema;
    private final SourceOutputWrapper<T> sourceOutputWrapper;
    private final SourceUserCallback<T> userCallback;

    public PulsarRecordEmitter(
            PulsarDeserializationSchema<T> deserializationSchema,
            @Nullable SourceUserCallback<T> userCallback) {
        this.deserializationSchema = deserializationSchema;
        this.userCallback = userCallback;
        this.sourceOutputWrapper = new SourceOutputWrapper<>();
    }

    @Override
    public void emitRecord(
            Message<byte[]> element, SourceOutput<T> output, PulsarPartitionSplitState splitState)
            throws Exception {
        // pass the message to the user callback
        Message<byte[]> userElement;
        if (userCallback != null) {
            userElement = userCallback.beforeCollect(element);
        } else {
            userElement = element;
        }

        // Update the source output.
        sourceOutputWrapper.setSourceOutput(output);
        sourceOutputWrapper.setTimestamp(element);

        // Deserialize the message and since it to output.
        deserializationSchema.deserialize(element, sourceOutputWrapper);
        splitState.setLatestConsumedId(element.getMessageId());
        if (userCallback != null) {
            userCallback.afterCollect(element, sourceOutputWrapper.getCollectedValue());
        }

        // Release the messages if we use message pool in Pulsar.
        if (element == userElement) {
            element.release();
        } else {
            userElement.release();
            element.release();
        }
    }

    private static class SourceOutputWrapper<T> implements Collector<T> {

        private SourceOutput<T> sourceOutput;
        private long timestamp;
        private T collectedValue;

        @Override
        public void collect(T record) {
            if (timestamp > 0) {
                sourceOutput.collect(record, timestamp);
            } else {
                sourceOutput.collect(record);
            }
            collectedValue = record;
        }

        public T getCollectedValue() {
            return collectedValue;
        }

        @Override
        public void close() {
            // Nothing to do here.
        }

        private void setSourceOutput(SourceOutput<T> sourceOutput) {
            this.sourceOutput = sourceOutput;
        }

        /**
         * Get the event timestamp from Pulsar. Zero means there is no event time. See {@link
         * Message#getEventTime()} to get the reason why it returns zero.
         */
        private void setTimestamp(Message<?> message) {
            this.timestamp = message.getEventTime();
        }
    }
}
