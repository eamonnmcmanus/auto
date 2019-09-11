/*
 * Copyright 2015 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.auto.value.extension;

import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * An AutoValueExtension allows for extra functionality to be created during the generation of an
 * AutoValue class.
 *
 * <p>Extensions are discovered at compile time using the {@link java.util.ServiceLoader} APIs,
 * allowing them to run without any additional annotations. To be found by {@code ServiceLoader}, an
 * extension class must be public with a public no-arg constructor, and its fully-qualified name
 * must appear in a file called {@code
 * META-INF/services/com.google.auto.value.extension.AutoValueExtension} in a jar that is on the
 * compiler's {@code -classpath} or {@code -processorpath}.
 *
 * <p>When the AutoValue processor runs for a class {@code Foo}, it will ask each Extension whether
 * it is {@linkplain #applicable applicable}. Suppose two Extensions reply that they are. Then
 * the processor will generate the AutoValue logic in a direct subclass of {@code Foo}, and it
 * will ask the first Extension to generate a subclass of that, and the second Extension to generate
 * a subclass of the subclass. So we might have this hierarchy:
 *
 * <pre>
 * &#64;AutoValue abstract class Foo {...}                          // the hand-written class
 * abstract class $$AutoValue_Foo extends Foo {...}             // generated by AutoValue processor
 * abstract class $AutoValue_Foo extends $$AutoValue_Foo {...}  // generated by first Extension
 * final class AutoValue_Foo extends $AutoValue_Foo {...}       // generated by second Extension
 * </pre>
 *
 * <p>(The exact naming scheme illustrated here is not fixed and should not be relied on.)
 *
 * <p>If an Extension needs its generated class to be the final class in the inheritance hierarchy,
 * its {@link #mustBeFinal(Context)} method returns true. Only one Extension can return true for a
 * given context. Only generated classes that will be the final class in the inheritance hierarchy
 * can be declared final. All others should be declared abstract.
 *
 * <p>The first generated class in the hierarchy will always be the one generated by the AutoValue
 * processor and the last one will always be the one generated by the Extension that {@code
 * mustBeFinal}, if any. Other than that, the order of the classes in the hierarchy is unspecified.
 * The * last class in the hierarchy is {@code AutoValue_Foo} and that is the one that the
 * {@code Foo} class will reference, for example with {@code new AutoValue_Foo(...)}.
 *
 * <p>Each Extension must also be sure to generate a constructor with arguments corresponding to all
 * properties in {@link com.google.auto.value.extension.AutoValueExtension.Context#properties()}, in
 * order, and to call the superclass constructor with the same arguments. This constructor must have
 * at least package visibility.
 *
 * <p>Because the class generated by the AutoValue processor is at the top of the generated
 * hierarchy, Extensions can override its methods, for example {@code hashCode()},
 * {@code toString()}, or the implementations of the various {@code bar()} property methods.
 */
public abstract class AutoValueExtension {

  /** The context of the generation cycle. */
  public interface Context {

    /**
     * Returns the processing environment of this generation cycle. This can be used, among other
     * things, to produce compilation warnings or errors, using {@link
     * ProcessingEnvironment#getMessager()}.
     */
    ProcessingEnvironment processingEnvironment();

    /** Returns the package name of the classes to be generated. */
    String packageName();

    /**
     * Returns the annotated class that this generation cycle is based on.
     *
     * <p>Given {@code @AutoValue public class Foo {...}}, this will be {@code Foo}.
     */
    TypeElement autoValueClass();

    /**
     * Returns the ordered collection of properties to be generated by AutoValue. Each key is a
     * property name, and the corresponding value is the getter method for that property. For
     * example, if property {@code bar} is defined by {@code abstract String getBar()} then this map
     * will have an entry mapping {@code "bar"} to the {@code ExecutableElement} for {@code
     * getBar()}.
     */
    Map<String, ExecutableElement> properties();

    /**
     * Returns the complete set of abstract methods defined in or inherited by the
     * {@code @AutoValue} class. This includes all methods that define properties (like {@code
     * abstract String getBar()}), any abstract {@code toBuilder()} method, and any other abstract
     * method even if it has been consumed by this or another Extension.
     */
    Set<ExecutableElement> abstractMethods();

    /**
     * Returns a representation of the {@code Builder} associated with the {@code @AutoValue} class,
     * if there is one. This method will return {@link Optional#empty()} if called from within the
     * {@link #applicable} method. If an Extension needs {@code Builder}
     * information to decide whether it is applicable, it should return {@code true} from the
     * {@link #applicable} method and then return {@code null} from the {@link #generateClass}
     * method if it does not need to generate a class after all.
     *
     * <p>The default implementation of this method returns {@link Optional#empty()} for
     * compatibility with extensions which may have implemented this interface themselves.
     */
    default Optional<BuilderContext> builder() {
      return Optional.empty();
    }
  }

  /**
   * Represents a {@code Builder} associated with an {@code @AutoValue} class.
   */
  public interface BuilderContext {
    /**
     * Returns the {@code @AutoValue.Builder} interface or abstract class that this object
     * represents.
     */
    TypeElement builderType();

    /**
     * Returns abstract no-argument methods in the {@code @AutoValue} class that return the builder
     * type.
     *
     * <p>Consider a class like this:
     * <pre>
     *   {@code @AutoValue} abstract class Foo {
     *     abstract String bar();
     *
     *     abstract Builder toBuilder();
     *
     *     ...
     *     {@code @AutoValue.Builder}
     *     abstract static class Builder {...}
     *   }
     * </pre>
     *
     * <p>Here {@code toBuilderMethods()} will return a set containing the method
     * {@code Foo.toBuilder()}.
     */
    Set<ExecutableElement> toBuilderMethods();

    /**
     * Returns static no-argument methods in the {@code @AutoValue} class that return the builder
     * type.
     *
     * <p>Consider a class like this:
     * <pre>
     *   {@code @AutoValue} abstract class Foo {
     *     abstract String bar();
     *
     *     static Builder builder() {
     *       return new AutoValue_Foo.Builder()
     *           .setBar("default bar");
     *     }
     *
     *     {@code @AutoValue.Builder}
     *     abstract class Builder {
     *       abstract Builder setBar(String x);
     *       abstract Foo build();
     *     }
     *   }
     * </pre>
     *
     * <p>Here {@code builderMethods()} will return a set containing the method
     * {@code Foo.builder()}. Generated code should usually call this method in preference to
     * constructing {@code AutoValue_Foo.Builder()} directly, because this method can establish
     * default values for properties, as it does here.
     */
    Set<ExecutableElement> builderMethods();

    /**
     * Returns the method {@code build()} in the builder class, if it exists and returns the
     * {@code @AutoValue} type. This is the method that generated code for
     * {@code @AutoValue class Foo} should call in order to get an instance of {@code Foo} from its
     * builder. The returned method is called {@code build()}; if the builder uses some other name
     * then extensions have no good way to guess how they should build.
     *
     * <p>A common convention is for {@code build()} to be a concrete method in the
     * {@code @AutoValue.Builder} class, which calls an abstract method {@code autoBuild()} that is
     * implemented in the generated subclass. The {@code build()} method can then do validation,
     * defaulting, and so on.
     */
    Optional<ExecutableElement> buildMethod();

    /**
     * Returns the abstract build method. If the {@code @AutoValue} class is {@code Foo}, this is an
     * abstract no-argument method in the builder class that returns {@code Foo}. This might be
     * called {@code build()}, or, following a common convention, it might be called
     * {@code autoBuild()} and used in the implementation of a {@code build()} method that is
     * defined in the builder class.
     *
     * <p>Extensions should call the {@code build()} method in preference to this one. But they
     * should override this one if they want to customize build-time behaviour.
     */
    ExecutableElement autoBuildMethod();

    /**
     * Returns a map from property names to the corresponding setters. A property may have more than
     * one setter. For example, an {@code ImmutableList<String>} might be set by
     * {@code setFoo(ImmutableList<String>)} and {@code setFoo(String[])}.
     */
    Map<String, Set<ExecutableElement>> setters();

    /**
     * Returns a map from property names to property builders. For example, if there is a property
     * {@code foo} defined by {@code abstract ImmutableList<String> foo();} or
     * {@code abstract ImmutableList<String> getFoo();} in the {@code @AutoValue} class,
     * then there can potentially be a builder defined by
     * {@code abstract ImmutableList.Builder<String> fooBuilder();} in the
     * {@code @AutoValue.Builder} class. This map would then map {@code "foo"} to the
     * {@link ExecutableElement} representing {@code fooBuilder()}.
     */
    Map<String, ExecutableElement> propertyBuilders();
  }

  /**
   * Indicates to an annotation processor environment supporting incremental annotation processing
   * (currently a feature specific to Gradle starting with version 4.8) the incremental type of an
   * Extension.
   *
   * <p>The constants for this enum are ordered by increasing performance (but also constraints).
   *
   * @see <a
   *     href="https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing">Gradle
   *     documentation of its incremental annotation processing</a>
   */
  public enum IncrementalExtensionType {
    /**
     * The incrementality of this extension is unknown, or it is neither aggregating nor isolating.
     */
    UNKNOWN,

    /**
     * This extension is <i>aggregating</i>, meaning that it may generate outputs based on several
     * annotated input classes and it respects the constraints imposed on aggregating processors.
     * It is unusual for AutoValue extensions to be aggregating.
     *
     * @see <a
     *     href="https://docs.gradle.org/current/userguide/java_plugin.html#aggregating_annotation_processors">Gradle
     *     definition of aggregating processors</a>
     */
    AGGREGATING,

    /**
     * This extension is <i>isolating</i>, meaning roughly that its output depends on the
     * {@code @AutoValue} class and its dependencies, but not on other {@code @AutoValue} classes
     * that might be compiled at the same time. The constraints that an isolating extension must
     * respect are the same as those that Gradle imposes on an isolating annotation processor.
     *
     * @see <a
     *     href="https://docs.gradle.org/current/userguide/java_plugin.html#isolating_annotation_processors">Gradle
     *     definition of isolating processors</a>
     */
    ISOLATING
  }

  /**
   * Determines the incremental type of this Extension.
   *
   * <p>The {@link ProcessingEnvironment} can be used, among other things, to obtain the processor
   * options, using {@link ProcessingEnvironment#getOptions()}.
   *
   * <p>The actual incremental type of the AutoValue processor as a whole will be the loosest
   * incremental types of the Extensions present in the annotation processor path. The default
   * returned value is {@link IncrementalExtensionType#UNKNOWN}, which will disable incremental
   * annotation processing entirely.
   */
  public IncrementalExtensionType incrementalType(ProcessingEnvironment processingEnvironment) {
    return IncrementalExtensionType.UNKNOWN;
  }

  /**
   * Analogous to {@link Processor#getSupportedOptions()}, here to allow extensions to report their
   * own.
   *
   * <p>By default, if the extension class is annotated with {@link SupportedOptions}, this will
   * return a set with the strings in the annotation. If the class is not so annotated, an empty set
   * is returned.
   *
   * @return the set of options recognized by this extension or an empty set if none
   * @see SupportedOptions
   */
  public Set<String> getSupportedOptions() {
    SupportedOptions so = this.getClass().getAnnotation(SupportedOptions.class);
    if (so == null) {
      return ImmutableSet.of();
    } else {
      return ImmutableSet.copyOf(so.value());
    }
  }

  /**
   * Determines whether this Extension applies to the given context. If an Extension returns {@code
   * false} for a given class, it will not be called again during the processing of that class. An
   * Extension can return {@code true} and still choose not to generate any code for the class, by
   * returning {@code null} from {@link #generateClass}. That is often a more flexible approach.
   *
   * @param context The Context of the code generation for this class.
   */
  public boolean applicable(Context context) {
    return false;
  }

  /**
   * Denotes that the class generated by this Extension must be the final class in the inheritance
   * hierarchy. Only one Extension may be the final class, so this should be used sparingly.
   *
   * @param context the Context of the code generation for this class.
   */
  public boolean mustBeFinal(Context context) {
    return false;
  }

  /**
   * Returns a possibly empty set of property names that this Extension intends to implement. This
   * will prevent AutoValue from generating an implementation, and remove the supplied properties
   * from builders, constructors, {@code toString}, {@code equals}, and {@code hashCode}. The
   * default set returned by this method is empty.
   *
   * <p>Each returned string must be one of the property names in {@link Context#properties()}.
   *
   * <p>Returning a property name from this method is equivalent to returning the property's getter
   * method from {@link #consumeMethods}.
   *
   * <p>For example, Android's {@code Parcelable} interface includes a <a
   * href="http://developer.android.com/reference/android/os/Parcelable.html#describeContents()">method</a>
   * {@code int describeContents()}. Since this is an abstract method with no parameters, by default
   * AutoValue will consider that it defines an {@code int} property called {@code
   * describeContents}. If an {@code @AutoValue} class implements {@code Parcelable} and does not
   * provide an implementation of this method, by default its implementation will include {@code
   * describeContents} in builders, constructors, and so on. But an {@code AutoValueExtension} that
   * understands {@code Parcelable} can instead provide a useful implementation and return a set
   * containing {@code "describeContents"}. Then {@code describeContents} will be omitted from
   * builders and the rest.
   *
   * @param context the Context of the code generation for this class.
   */
  public Set<String> consumeProperties(Context context) {
    return ImmutableSet.of();
  }

  /**
   * Returns a possible empty set of abstract methods that this Extension intends to implement. This
   * will prevent AutoValue from generating an implementation, in cases where it would have, and it
   * will also avoid warnings about abstract methods that AutoValue doesn't expect. The default set
   * returned by this method is empty.
   *
   * <p>Each returned method must be one of the abstract methods in {@link
   * Context#abstractMethods()}.
   *
   * <p>For example, Android's {@code Parcelable} interface includes a <a
   * href="http://developer.android.com/reference/android/os/Parcelable.html#writeToParcel(android.os.Parcel,
   * int)">method</a> {@code void writeToParcel(Parcel, int)}. Normally AutoValue would not know
   * what to do with that abstract method. But an {@code AutoValueExtension} that understands {@code
   * Parcelable} can provide a useful implementation and return the {@code writeToParcel} method
   * here. That will prevent a warning about the method from AutoValue.
   *
   * @param context the Context of the code generation for this class.
   */
  public Set<ExecutableElement> consumeMethods(Context context) {
    return ImmutableSet.of();
  }

  /**
   * Returns the generated source code of the class named {@code className} to extend {@code
   * classToExtend}, or {@code null} if this extension does not generate a class in the hierarchy.
   * If there is a generated class, it should be final if {@code isFinal} is true; otherwise it
   * should be abstract. The returned string should be a complete Java class definition of the class
   * {@code className} in the package {@link Context#packageName() context.packageName()}.
   *
   * <p>The returned string will typically look like this:
   *
   * <pre>{@code
   * package <package>;
   * ...
   * <finalOrAbstract> class <className> extends <classToExtend> {...}
   * }</pre>
   *
   * <p>Here, {@code <package>} is {@link Context#packageName()}; {@code <finalOrAbstract>} is the
   * keyword {@code final} if {@code isFinal} is true or {@code abstract} otherwise; and {@code
   * <className>} and {@code <classToExtend>} are the values of this method's parameters of the same
   * name.
   *
   * @param context The {@link Context} of the code generation for this class.
   * @param className The simple name of the resulting class. The returned code will be written to a
   *     file named accordingly.
   * @param classToExtend The simple name of the direct parent of the generated class. This could be
   *     the AutoValue generated class, or a class generated as the result of another Extension.
   * @param isFinal True if this class is the last class in the chain, meaning it should be marked
   *     as final. Otherwise it should be marked as abstract.
   * @return The source code of the generated class, or {@code null} if this extension does not
   *     generate a class in the hierarchy.
   */
  public abstract String generateClass(
      Context context, String className, String classToExtend, boolean isFinal);
}
