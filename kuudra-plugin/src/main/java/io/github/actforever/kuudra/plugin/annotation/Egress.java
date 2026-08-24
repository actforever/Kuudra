package io.github.actforever.kuudra.plugin.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
public @interface Egress { String value(); InstancePolicy instancePolicy() default @InstancePolicy; }
