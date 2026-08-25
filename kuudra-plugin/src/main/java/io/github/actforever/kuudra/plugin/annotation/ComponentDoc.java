package io.github.actforever.kuudra.plugin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ComponentDoc {
    String purpose();
    String[] lifecyclePhases() default {};
    /** Structured documentation for component-specific properties below spec.options. */
    SpecProperty[] configuration() default {};
    EventEmission[] emittedEvents() default {};
}
