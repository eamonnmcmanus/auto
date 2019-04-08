package com.google.auto.value.processor;

import com.google.common.collect.ImmutableList;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;

class SimpleServiceLoader {
  private SimpleServiceLoader() {}

  static <T> ImmutableList<T> load(Class<? extends T> service, ClassLoader loader) {
    String resourceName = "META-INF/services/" + service.getName();
    List<URL> resourceUrls;
    try {
      resourceUrls = Collections.list(loader.getResources(resourceName));
    } catch (IOException e) {
      throw new ServiceConfigurationError("Could not look up " + resourceName, e);
    }
    ImmutableList.Builder<T> providers = ImmutableList.builder();
    for (URL resourceUrl : resourceUrls) {
      providers.addAll(providersFromUrl(resourceUrl, service, loader));
    }
    return providers.build();
  }

  private static <T> ImmutableList<T> providersFromUrl(
      URL resourceUrl, Class<T> service, ClassLoader loader) {
    ImmutableList.Builder<T> providers = ImmutableList.builder();
    try {
      URLConnection connection = resourceUrl.openConnection();
      connection.setUseCaches(false);
      try (InputStream in = connection.getInputStream();
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          Optional<String> maybeClassName = parseClassName(line);
          if (maybeClassName.isPresent()) {
            String className = maybeClassName.get();
            Class<?> c;
            try {
              c = Class.forName(className, false, loader);
            } catch (ClassNotFoundException e) {
              throw new ServiceConfigurationError("Could not load " + className, e);
            }
            if (!service.isAssignableFrom(c)) {
              throw new ServiceConfigurationError(
                  "Class " + className + " is not assignable to " + service.getName());
            }
            try {
              Object provider = c.getConstructor().newInstance();
              providers.add(service.cast(provider));
            } catch (ReflectiveOperationException e) {
              throw new ServiceConfigurationError("Could not construct " + className, e);
            }
          }
        }
        return providers.build();
      }
    } catch (IOException e) {
      throw new ServiceConfigurationError("Could not read " + resourceUrl, e);
    }
  }

  private static Optional<String> parseClassName(String line) {
    int hash = line.indexOf('#');
    if (hash >= 0) {
      line = line.substring(0, hash);
    }
    line = line.trim();
    return line.isEmpty() ? Optional.empty() : Optional.of(line);
  }
}
