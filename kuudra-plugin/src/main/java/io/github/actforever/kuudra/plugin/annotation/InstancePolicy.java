package io.github.actforever.kuudra.plugin.annotation;

import io.github.actforever.kuudra.plugin.ComponentLimitScope;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Instance constraints embedded in a plugin component declaration. */
@Retention(RetentionPolicy.RUNTIME)
public @interface InstancePolicy {
    int maxInstances() default Integer.MAX_VALUE;
    ComponentLimitScope limitScope() default ComponentLimitScope.APP;
    String exclusivityDomain() default "";
    boolean threadSafe() default false;
}
