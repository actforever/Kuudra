package io.github.actforever.kuudra.plugin.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface EventEmission {
    String stage();
    String eventType();
    String description() default "";
    String dataExample() default "";
}
