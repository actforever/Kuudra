package io.github.actforever.kuudra.plugin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ComponentDoc {
    String purpose();
    String usageExample() default "";
    String[] lifecyclePhases() default {};
    EventEmission[] emittedEvents() default {};
}
