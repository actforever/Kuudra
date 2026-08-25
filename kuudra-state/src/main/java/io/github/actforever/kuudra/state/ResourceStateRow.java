package io.github.actforever.kuudra.state;

import lombok.Getter;
import lombok.Setter;

/** MyBatis persistence row kept separate from the public StateStore model. */
@Getter
@Setter
final class ResourceStateRow {
    private String kind;
    private String namespace;
    private String name;
    private String resourceType;
    private long generation;
    private String desiredJson;
    private long observedGeneration;
    private String phase;
    private String message;

}
