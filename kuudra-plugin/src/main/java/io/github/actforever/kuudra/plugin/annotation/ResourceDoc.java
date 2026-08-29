package io.github.actforever.kuudra.plugin.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ResourceDoc {
    String purpose();
    String[] lifecyclePhases() default {};
    SpecProperty[] options() default {};
    SpecProperty[] arguments() default {};
    EventEmission[] emittedEvents() default {};
}
