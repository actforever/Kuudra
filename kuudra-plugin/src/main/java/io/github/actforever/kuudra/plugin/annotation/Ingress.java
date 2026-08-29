package io.github.actforever.kuudra.plugin.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
public @interface Ingress { String value(); ResourcePolicy policy() default @ResourcePolicy; }
