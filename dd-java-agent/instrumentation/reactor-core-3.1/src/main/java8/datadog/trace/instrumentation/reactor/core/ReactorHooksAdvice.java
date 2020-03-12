package datadog.trace.instrumentation.reactor.core;

import net.bytebuddy.asm.Advice;
import reactor.core.publisher.Hooks;

public class ReactorHooksAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void postInit() {
    Hooks.onEachOperator(ReactorHooksAdvice.class.getName(), ReactorTracing.operator());
  }
}
