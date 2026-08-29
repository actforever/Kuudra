package io.github.actforever.kuudra.plugin.annotation;

import io.github.actforever.kuudra.plugin.ResourceLimitScope;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface ResourcePolicy {
    int maxInstances() default Integer.MAX_VALUE;
    ResourceLimitScope limitScope() default ResourceLimitScope.APP;
    String exclusivityDomain() default "";
    boolean allowParallel() default true;
}
