package io.github.actforever.kuudra.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
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

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        LinkedHashSet<URL> resources = new LinkedHashSet<>();
        resources.addAll(Collections.list(getParent().getResources(name)));
        for (ClassLoader dependency : dependencies) resources.addAll(Collections.list(dependency.getResources(name)));
        resources.addAll(Collections.list(findResources(name)));
        return Collections.enumeration(resources);
    }
}
