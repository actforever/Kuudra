package io.github.actforever.kuudra.logging;

import java.util.concurrent.atomic.AtomicBoolean;

/** The banner belongs to the core runtime rather than to any transport adapter. */
public final class KuudraBanner {
    private static final AtomicBoolean PRINTED = new AtomicBoolean();
    private static final String TEXT = """
             _
 _  __ _   _ _   _  __| |_ __ __ _
| |/ /| | | | | | |/ _` | '__/ _` |
| . \\| |_| | |_| | (_| | | | (_| |
|_|\\_\\\\__,_|\\__,_|\\__,_|_|  \\__,_|

 :: Kuudra Core ::
""";

    private KuudraBanner() { }

    public static void print() {
        if (PRINTED.compareAndSet(false, true)) System.out.println(TEXT);
    }
}
