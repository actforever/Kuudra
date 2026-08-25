package io.github.actforever.kuudra.plugin.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Documents one component-owned property below a Component manifest's spec.options. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface SpecProperty {
    /** Dot-separated path relative to spec.options. */
    String path();
    /** Java value type; arrays and shared plugin POJOs are supported. */
    Class<?> type();
    boolean required() default false;
    /** JSON/YAML-compatible literal represented as text; blank means no documented default. */
    String defaultValue() default "";
    String description();
    /** JSON literals exposed as native values by the component documentation API. */
    String[] examples() default {};
    String[] allowedValues() default {};
}
