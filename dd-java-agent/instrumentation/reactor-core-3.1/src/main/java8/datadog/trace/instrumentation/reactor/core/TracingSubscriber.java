package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopTraceScope;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.util.context.Context;

public class TracingSubscriber<T>
    implements Subscription, CoreSubscriber<T>, Fuseable.QueueSubscription<T>, Scannable {

  private final AtomicReference<TraceScope.Continuation> continuation = new AtomicReference<>();

  private final AgentSpan downstreamSpan;
  private final AgentSpan span;
  private final CoreSubscriber<T> delegate;
  private final Context context;
  private TraceScope downstreamScope;
  private Subscription subscription;

  public TracingSubscriber(
      final AgentSpan span,
      final CoreSubscriber<T> delegate,
      final TraceScope.Continuation continuation) {
    this.delegate = delegate;
    this.span = span;
    downstreamSpan =
        (AgentSpan)
            delegate.currentContext().getOrEmpty(AgentSpan.class).orElseGet(AgentTracer::noopSpan);

    try (final AgentScope scope = activateSpan(downstreamSpan, false)) {
      downstreamScope = activeScope();
      downstreamScope.setAsyncPropagation(true);
    }
    this.continuation.set(continuation);

    context = this.delegate.currentContext().put(AgentSpan.class, this.span);
  }

  @Override
  public Context currentContext() {
    return context;
  }

  private TraceScope maybeScope() {
    final TraceScope.Continuation continuation =
        this.continuation.getAndSet(downstreamScope.capture());
    return continuation.activate();
  }

  private TraceScope maybeScopeAndDeactivate() {
    downstreamScope = noopTraceScope();
    return maybeScope();
  }

  @Override
  public void onSubscribe(final Subscription subscription) {
    this.subscription = subscription;

    try (final TraceScope scope = maybeScope()) {
      delegate.onSubscribe(this);
    }
  }

  @Override
  public void onNext(final T t) {
    try (final TraceScope scope = maybeScope()) {
      delegate.onNext(t);
    }
  }

  @Override
  public void onError(final Throwable t) {
    try (final TraceScope scope = maybeScopeAndDeactivate()) {
      delegate.onError(t);
      downstreamSpan.setError(true);
      downstreamSpan.addThrowable(t);
    }
  }

  @Override
  public void onComplete() {
    try (final TraceScope scope = maybeScopeAndDeactivate()) {
      delegate.onComplete();
    }
  }

  /*
   * Methods from Subscription
   */

  @Override
  public void request(final long n) {
    try (final AgentScope scope = activateSpan(span, false)) {
      subscription.request(n);
    }
  }

  @Override
  public void cancel() {
    try (final AgentScope scope = activateSpan(span, false)) {
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
