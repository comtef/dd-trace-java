package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import java.util.function.Consumer;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Fuseable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.GroupedFlux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ParallelFlux;

public class TracingPublishers {
  private static final Logger log = LoggerFactory.getLogger(TracingPublishers.class);

  public static <T> Publisher<T> wrap(final Publisher<T> delegate) {
    AgentSpan span = activeSpan();
    if (span == null) {
      span = noopSpan();
    }
    log.debug("Lifting {} with span {}", delegate.getClass().getName(), span.toString());

    // based on Operators.LiftFunction.apply in reactor 3.3.4
    if (delegate instanceof Fuseable) {
      if (delegate instanceof Mono) {
        return new FuseableMonoTracingPublisher<>(span, (Mono<T>) delegate);
      }
      if (delegate instanceof ParallelFlux) {
        return new FuseableParallelFluxTracingPublisher<>(span, (ParallelFlux<T>) delegate);
      }
      if (delegate instanceof ConnectableFlux) {
        return new FuseableConnectableFluxTracingPublisher<>(span, (ConnectableFlux<T>) delegate);
      }
      if (delegate instanceof GroupedFlux) {
        return new FuseableGroupedFluxTracingPublisher<>(span, (GroupedFlux<?, T>) delegate);
      }
      return new FuseableFluxTracingPublisher<>(span, (Flux<T>) delegate);
    } else {
      if (delegate instanceof Mono) {
        return new MonoTracingPublisher<>(span, (Mono<T>) delegate);
      }
      if (delegate instanceof ParallelFlux) {
        return new ParallelFluxTracingPublisher<>(span, (ParallelFlux<T>) delegate);
      }
      if (delegate instanceof ConnectableFlux) {
        return new ConnectableFluxTracingPublisher<>(span, (ConnectableFlux<T>) delegate);
      }
      if (delegate instanceof GroupedFlux) {
        return new GroupedFluxTracingPublisher<>(span, (GroupedFlux<?, T>) delegate);
      }
      return new FluxTracingPublisher<>(span, (Flux<T>) delegate);
    }
  }

  public static class MonoTracingPublisher<T> extends Mono<T> {
    private final AgentSpan span;
    private final Mono<T> delegate;
    private final TraceScope.Continuation continuation;

    public MonoTracingPublisher(final AgentSpan span, final Mono<T> delegate) {
      this.span = span;
      this.delegate = delegate;
      continuation = activeScope().capture();
    }

    @Override
    public void subscribe(CoreSubscriber<? super T> actual) {
      try (final TraceScope scope = continuation.activate()) {
        scope.setAsyncPropagation(true);
        if (!(actual instanceof TracingSubscriber)) {
          actual = new TracingSubscriber<>(span, actual, activeScope().capture());
        }
        delegate.subscribe(actual);
      }
    }
  }

  public static class ParallelFluxTracingPublisher<T> extends ParallelFlux<T> {
    private final AgentSpan span;
    private final ParallelFlux<T> delegate;
    private final TraceScope.Continuation continuation;

    public ParallelFluxTracingPublisher(final AgentSpan span, final ParallelFlux<T> delegate) {
      this.span = span;
      this.delegate = delegate;
      continuation = activeScope().capture();
    }

    @Override
    public int parallelism() {
      return delegate.parallelism();
    }

    @Override
    protected void subscribe(final CoreSubscriber<? super T>[] subscribers) {
      try (final TraceScope scope = continuation.activate()) {
        scope.setAsyncPropagation(true);
        for (CoreSubscriber<? super T> subscriber : subscribers) {
          if (!(subscriber instanceof TracingSubscriber)) {
            subscriber = new TracingSubscriber<>(span, subscriber, activeScope().capture());
          }
          delegate.subscribe(subscriber);
        }
      }
    }
  }

  public static class ConnectableFluxTracingPublisher<T> extends ConnectableFlux<T> {
    private final AgentSpan span;
    private final ConnectableFlux<T> delegate;
    private final TraceScope.Continuation continuation;

    public ConnectableFluxTracingPublisher(
        final AgentSpan span, final ConnectableFlux<T> delegate) {
      this.span = span;
      this.delegate = delegate;
      continuation = activeScope().capture();
    }

    @Override
    public void connect(final Consumer<? super Disposable> cancelSupport) {
      try (final AgentScope scope = activateSpan(span, false)) {
        delegate.connect(cancelSupport);
      }
    }

    @Override
    public void subscribe(CoreSubscriber<? super T> actual) {
      try (final TraceScope scope = continuation.activate()) {
        scope.setAsyncPropagation(true);
        if (!(actual instanceof TracingSubscriber)) {
          actual = new TracingSubscriber<>(span, actual, activeScope().capture());
        }
        delegate.subscribe(actual);
      }
    }
  }

  public static class GroupedFluxTracingPublisher<O, T> extends GroupedFlux<O, T> {
    private final AgentSpan span;
    private final GroupedFlux<O, T> delegate;
    private final TraceScope.Continuation continuation;

    public GroupedFluxTracingPublisher(final AgentSpan span, final GroupedFlux<O, T> delegate) {
      this.span = span;
      this.delegate = delegate;
      continuation = activeScope().capture();
    }

    @Override
    public O key() {
      return delegate.key();
    }

    @Override
    public void subscribe(CoreSubscriber<? super T> actual) {
      try (final TraceScope scope = continuation.activate()) {
        scope.setAsyncPropagation(true);
        if (!(actual instanceof TracingSubscriber)) {
          actual = new TracingSubscriber<>(span, actual, activeScope().capture());
        }
        delegate.subscribe(actual);
      }
    }
  }

  public static class FluxTracingPublisher<T> extends Flux<T> {
    private final AgentSpan span;
    private final Flux<T> delegate;
    private final TraceScope.Continuation continuation;

    public FluxTracingPublisher(final AgentSpan span, final Flux<T> delegate) {
      this.span = span;
      this.delegate = delegate;
      continuation = activeScope().capture();
    }

    @Override
    public void subscribe(CoreSubscriber<? super T> actual) {
      try (final TraceScope scope = continuation.activate()) {
        scope.setAsyncPropagation(true);
        if (!(actual instanceof TracingSubscriber)) {
          actual = new TracingSubscriber<>(span, actual, activeScope().capture());
        }
        delegate.subscribe(actual);
      }
    }
  }

  public static class FuseableMonoTracingPublisher<T> extends MonoTracingPublisher<T>
      implements Fuseable {
    public FuseableMonoTracingPublisher(final AgentSpan span, final Mono<T> delegate) {
      super(span, delegate);
    }
  }

  public static class FuseableParallelFluxTracingPublisher<T>
      extends ParallelFluxTracingPublisher<T> implements Fuseable {
    public FuseableParallelFluxTracingPublisher(
        final AgentSpan span, final ParallelFlux<T> delegate) {
      super(span, delegate);
    }
  }

  public static class FuseableConnectableFluxTracingPublisher<T>
      extends ConnectableFluxTracingPublisher<T> implements Fuseable {
    public FuseableConnectableFluxTracingPublisher(
        final AgentSpan span, final ConnectableFlux<T> delegate) {
      super(span, delegate);
    }
  }

  public static class FuseableGroupedFluxTracingPublisher<O, T>
      extends GroupedFluxTracingPublisher<O, T> implements Fuseable {
    public FuseableGroupedFluxTracingPublisher(
        final AgentSpan span, final GroupedFlux<O, T> delegate) {
      super(span, delegate);
    }
  }

  public static class FuseableFluxTracingPublisher<T> extends FluxTracingPublisher<T>
      implements Fuseable {
    public FuseableFluxTracingPublisher(final AgentSpan span, final Flux<T> delegate) {
      super(span, delegate);
    }
  }
}
