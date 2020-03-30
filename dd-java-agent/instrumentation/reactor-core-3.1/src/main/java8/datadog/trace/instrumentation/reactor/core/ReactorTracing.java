package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

public class ReactorTracing {
  public static <T> Function<? super Publisher<T>, ? extends Publisher<T>> operator() {
    return Operators.lift(
        (scannable) -> {
          // Don't wrap ourselves, and ConnectableFlux causes an exception in early reactor versions
          // due to not having the correct super types for being handled by the LiftFunction
          // operator
          if (scannable instanceof TracingSubscriber) {
            return false;
          } else if (scannable instanceof ConnectableFlux) {
            return false;
          }
          return true;
        },
        ReactorTracing::subscriber);
  }

  public static class TracingLifter<O>
      implements BiFunction<Scannable, CoreSubscriber<O>, CoreSubscriber<O>> {

    private AgentSpan span;

    public TracingLifter(final AgentSpan span) {
      this.span = span;
    }

    @Override
    public CoreSubscriber<O> apply(final Scannable scannable, final CoreSubscriber<O> delegate) {
      if (delegate instanceof TracingSubscriber) {
        return delegate;
      }
      // DirectProcessor doesn't always propagate signals upstream
      if (delegate instanceof DirectProcessor) {
        return delegate;
      }

      if (span == null) {
        span = AgentTracer.noopSpan();
      }

      return null; // activate the span and then pass that scope to the tracing subscriber
    }
  }

  public static <T> CoreSubscriber<? super T> subscriber(
      final Scannable scannable, final CoreSubscriber<T> delegate) {
    if (delegate instanceof TracingSubscriber) {
      return delegate;
    }
    // DirectProcessor doesn't always propagate signals upstream
    if (delegate instanceof DirectProcessor) {
      return delegate;
    }

    AgentSpan span = activeSpan();
    if (span == null) {
      span = AgentTracer.noopSpan();
    }

    return new TracingSubscriber<>(delegate, delegate.currentContext(), activeScope());
  }

  @Slf4j
  private static class TracingSubscriber<T>
      implements Subscription, CoreSubscriber<T>, Fuseable.QueueSubscription<T>, Scannable {

    private final AtomicReference<TraceScope.Continuation> continuation = new AtomicReference<>();

    private final Subscriber<T> delegate;
    private final Context context;
    private TraceScope scope;
    private Subscription subscription;

    public TracingSubscriber(
        final Subscriber<T> delegate, final Context context, final TraceScope scope) {
      this.delegate = delegate;
      this.context = context;
      this.scope = scope != null ? scope : AgentTracer.NoopTraceScope.INSTANCE;

      this.scope.setAsyncPropagation(true);
      continuation.set(this.scope.capture());
    }

    private TraceScope maybeScope() {
      final TraceScope.Continuation continuation = this.continuation.getAndSet(scope.capture());
      return continuation.activate();
    }

    private TraceScope maybeScopeAndDeactivate() {
      scope = AgentTracer.NoopTraceScope.INSTANCE;
      return maybeScope();
    }

    /*
     * Methods from CoreSubscriber
     */

    @Override
    public Context currentContext() {
      return context;
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
        activeSpan().setError(true);
        activeSpan().addThrowable(t);
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
      try (final TraceScope scope = maybeScope()) {
        subscription.request(n);
      }
    }

    @Override
    public void cancel() {
      try (final TraceScope scope = maybeScopeAndDeactivate()) {
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
}
