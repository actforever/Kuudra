package io.github.actforever.kuudra.app;

import java.util.concurrent.atomic.AtomicBoolean;

/** Product banner printed when the assembled Kuudra application starts. */
final class KuudraBanner {
    private static final AtomicBoolean PRINTED = new AtomicBoolean();
    private static final String TEXT = """
   ▄█   ▄█▄ ███    █▄  ███    █▄  ████████▄     ▄████████    ▄████████
  ███ ▄███▀ ███    ███ ███    ███ ███   ▀███   ███    ███   ███    ███
  ███▐██▀   ███    ███ ███    ███ ███    ███   ███    ███   ███    ███
 ▄█████▀    ███    ███ ███    ███ ███    ███  ▄███▄▄▄▄██▀   ███    ███
▀▀█████▄    ███    ███ ███    ███ ███    ███ ▀▀███▀▀▀▀▀   ▀███████████
  ███▐██▄   ███    ███ ███    ███ ███    ███ ▀███████████   ███    ███
  ███ ▀███▄ ███    ███ ███    ███ ███   ▄███   ███    ███   ███    ███
  ███   ▀█▀ ████████▀  ████████▀  ████████▀    ███    ███    ███    █▀
  ▀                                            ███    ███

 :: Kuudra ::
""";

    private KuudraBanner() { }

    static void print() {
        if (PRINTED.compareAndSet(false, true)) System.out.println(TEXT);
    }
}
