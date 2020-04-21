package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.util.context.Context;

public class TracingSubscriber<T>
    implements Subscription, CoreSubscriber<T>, Fuseable.QueueSubscription<T>, Scannable {
  private final AgentSpan downstreamSpan;
  private final AgentSpan span;
  private final CoreSubscriber<T> delegate;
  private final Context context;
  private Subscription subscription;

  public TracingSubscriber(final AgentSpan span, final CoreSubscriber<T> delegate) {
    this.delegate = delegate;
    this.span = span;
    downstreamSpan =
        (AgentSpan)
            delegate.currentContext().getOrEmpty(AgentSpan.class).orElseGet(AgentTracer::noopSpan);
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
      delegate.onSubscribe(this);
    }
  }

  @Override
  public void onNext(final T t) {
    try (final AgentScope scope = activateSpan(downstreamSpan, false)) {
      scope.setAsyncPropagation(true);
      delegate.onNext(t);
    }
  }

  @Override
  public void onError(final Throwable t) {
    try (final AgentScope scope = activateSpan(downstreamSpan, false)) {
      delegate.onError(t);
      span.setError(true);
      span.addThrowable(t);
    }
  }

  @Override
  public void onComplete() {
    try (final AgentScope scope = activateSpan(downstreamSpan, false)) {
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
