/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.spring.kafka.v2_7;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.util.VirtualField;
import io.opentelemetry.instrumentation.kafka.internal.ConsumerAndRecord;
import javax.annotation.Nullable;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.kafka.listener.BatchInterceptor;

final class InstrumentedBatchInterceptor<K, V> implements BatchInterceptor<K, V> {

  private static final VirtualField<ConsumerRecords<?, ?>, Context> receiveContextField =
      VirtualField.find(ConsumerRecords.class, Context.class);
  private static final VirtualField<ConsumerRecords<?, ?>, State> stateField =
      VirtualField.find(ConsumerRecords.class, State.class);

  private final Instrumenter<ConsumerAndRecord<ConsumerRecords<?, ?>>, Void>
      batchProcessInstrumenter;
  @Nullable private final BatchInterceptor<K, V> decorated;

  InstrumentedBatchInterceptor(
      Instrumenter<ConsumerAndRecord<ConsumerRecords<?, ?>>, Void> batchProcessInstrumenter,
      @Nullable BatchInterceptor<K, V> decorated) {
    this.batchProcessInstrumenter = batchProcessInstrumenter;
    this.decorated = decorated;
  }

  @Override
  public ConsumerRecords<K, V> intercept(ConsumerRecords<K, V> records, Consumer<K, V> consumer) {
    Context parentContext = getParentContext(records);

    ConsumerAndRecord<ConsumerRecords<?, ?>> request = ConsumerAndRecord.create(consumer, records);
    if (batchProcessInstrumenter.shouldStart(parentContext, request)) {
      Context context = batchProcessInstrumenter.start(parentContext, request);
      Scope scope = context.makeCurrent();
      stateField.set(records, State.create(context, scope));
    }

    return decorated == null ? records : decorated.intercept(records, consumer);
  }

  private static Context getParentContext(ConsumerRecords<?, ?> records) {
    Context receiveContext = receiveContextField.get(records);

    // use the receive CONSUMER span as parent if it's available
    return receiveContext != null ? receiveContext : Context.current();
  }

  @Override
  public void success(ConsumerRecords<K, V> records, Consumer<K, V> consumer) {
    end(ConsumerAndRecord.create(consumer, records), null);
    if (decorated != null) {
      decorated.success(records, consumer);
    }
  }

  @Override
  public void failure(ConsumerRecords<K, V> records, Exception exception, Consumer<K, V> consumer) {
    end(ConsumerAndRecord.create(consumer, records), exception);
    if (decorated != null) {
      decorated.failure(records, exception, consumer);
    }
  }

  private void end(
      ConsumerAndRecord<ConsumerRecords<?, ?>> consumerAndRecord, @Nullable Throwable error) {
    ConsumerRecords<?, ?> records = consumerAndRecord.record();
    State state = stateField.get(records);
    stateField.set(records, null);
    if (state != null) {
      state.scope().close();
      batchProcessInstrumenter.end(state.context(), consumerAndRecord, null, error);
    }
  }
}
