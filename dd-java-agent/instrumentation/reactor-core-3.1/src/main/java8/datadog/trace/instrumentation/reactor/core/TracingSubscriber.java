package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopTraceScope;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.util.context.Context;

@Slf4j
public class TracingSubscriber<T>
    implements Subscription, CoreSubscriber<T>, Fuseable.QueueSubscription<T>, Scannable {

  private final AtomicReference<TraceScope.Continuation> continuation = new AtomicReference<>();

  private final AgentSpan span;
  private final CoreSubscriber<T> delegate;
  private final Context context;
  private final AgentSpan downstreamSpan;
  private Subscription subscription;

  public TracingSubscriber(final AgentSpan span, final CoreSubscriber<T> delegate) {
    this.delegate = delegate;
    this.span = span;
    downstreamSpan =
        (AgentSpan)
            delegate.currentContext().getOrEmpty(AgentSpan.class).orElseGet(AgentTracer::noopSpan);

    try (final AgentScope scope = activateSpan(downstreamSpan, false)) {
      final TraceScope downstreamScope = activeScope();
      downstreamScope.setAsyncPropagation(true);
      continuation.set(activeScope().capture());
    }

    context = this.delegate.currentContext().put(AgentSpan.class, this.span);
  }

  @Override
  public Context currentContext() {
    return context;
  }

  @Override
  public void onSubscribe(final Subscription subscription) {
    this.subscription = subscription;

    try (final AgentScope scope = activateSpan(downstreamSpan, false)) {
      log.info("onSubscribe() {}", toString());
      scope.setAsyncPropagation(true);
      delegate.onSubscribe(this);
    }
  }

  @Override
  public void onNext(final T t) {
    try (final AgentScope scope = activateSpan(downstreamSpan, false)) {
      log.info("onNext() {}", toString());
      scope.setAsyncPropagation(true);
      delegate.onNext(t);
    }
  }

  @Override
  public void onError(final Throwable t) {
    if (continuation.get() != noopTraceScope().capture()) {
      try (final TraceScope scope = continuation.getAndSet(noopTraceScope().capture()).activate()) {
        log.info("onError() {}", toString());
        scope.setAsyncPropagation(true);
        span.setError(true);
        span.addThrowable(t);
        delegate.onError(t);
      }
    } else {
      try (final AgentScope scope = activateSpan(downstreamSpan, false)) {
        log.info("onError() {}", toString());
        scope.setAsyncPropagation(true);
        span.setError(true);
        span.addThrowable(t);
        continuation.getAndSet(noopTraceScope().capture()).activate().close();
        delegate.onError(t);
      }
    }
  }

  @Override
  public void onComplete() {
    if (continuation.get() != noopTraceScope().capture()) {
      try (final TraceScope scope = continuation.getAndSet(noopTraceScope().capture()).activate()) {
        log.info("onComplete() {}", toString());
        scope.setAsyncPropagation(true);
        delegate.onComplete();
      }
    } else {
      try (final AgentScope scope = activateSpan(downstreamSpan, false)) {
        log.info("onComplete() {}", toString());
        scope.setAsyncPropagation(true);
        delegate.onComplete();
      }
    }
  }

  /*
   * Methods from Subscription
   */

  @Override
  public void request(final long n) {
    try (final AgentScope scope = activateSpan(span, false)) {
      log.info("request() {}", toString());
      scope.setAsyncPropagation(true);
      subscription.request(n);
    }
  }

  @Override
  public void cancel() {
    try (final AgentScope scope = activateSpan(span, false)) {
      log.info("cancel() {}", toString());
      scope.setAsyncPropagation(true);
      continuation.getAndSet(noopTraceScope().capture()).activate().close();
      subscription.cancel();
    }
  }

  /*
   * Methods from Scannable
   */

  @Override
  public Object scanUnsafe(final Attr attr) {
    if (attr == Attr.PARENT) {
      return subscription;
    }
    if (attr == Attr.ACTUAL) {
      return delegate;
    }
    return null;
  }

  /*
   * Methods from Fuseable.QueueSubscription
   */

  @Override
  public int requestFusion(final int requestedMode) {
    return Fuseable.NONE;
  }

  @Override
  public T poll() {
    return null;
  }

  @Override
  public int size() {
    return 0;
  }

  @Override
  public boolean isEmpty() {
    return false;
  }

  @Override
  public void clear() {}
}
