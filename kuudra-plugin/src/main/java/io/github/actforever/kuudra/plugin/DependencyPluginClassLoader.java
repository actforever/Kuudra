package io.github.actforever.kuudra.plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/** Parent-first for Kuudra APIs, then declared plugin dependencies, then this archive's own classes. */
final class DependencyPluginClassLoader extends URLClassLoader {
    private final List<ClassLoader> dependencies;

    DependencyPluginClassLoader(URL archive, ClassLoader parent, List<? extends ClassLoader> dependencies) {
        super(new URL[]{archive}, parent);
        this.dependencies = List.copyOf(dependencies);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try { loaded = getParent().loadClass(name); }
                catch (ClassNotFoundException parentMissing) {
                    for (ClassLoader dependency : dependencies) {
                        try { loaded = dependency.loadClass(name); break; }
                        catch (ClassNotFoundException ignored) { }
                    }
                    if (loaded == null) loaded = findClass(name);
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    @Override
    public URL getResource(String name) {
        URL resource = getParent().getResource(name);
        if (resource != null) return resource;
        for (ClassLoader dependency : dependencies) {
            resource = dependency.getResource(name);
            if (resource != null) return resource;
        }
        return findResource(name);
    }
}
