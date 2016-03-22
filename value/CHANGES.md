# Changes from 1.1 to 1.2

## Functional changes

* A **provisional** extension API has been introduced. This **will change** in a later release. If you want to use it regardless, see the [AutoValueExtension](src/main/java/com/google/auto/value/extension/AutoValueExtension.java) class.

* Properties of primitive array type (e.g. `byte[]`) are no longer cloned when read. If your @AutoValue class includes an array property, by default it will get a compiler warning, which can be suppressed with `@SuppressWarnings("mutable")`.

* In @AutoValue builders, a property whose type is e.g. `ImmutableList<String>` can be set by a setter whose argument can be given to `ImmutableList.copyOf`, for example `setFoo(Iterable<String>)`. More generally, if the property type has a method `copyOf` and the setter parameter is compatible with the `copyOf` parameter, the setter is valid and its implementation will call `copyOf`.

* In @AutoValue builders, if you have a property foo of type `ImmutableList<String>`, then the builder can have a method `ImmutableList.Builder<String> fooBuilder()`, instead of or as well as the already-supported `setFoo(ImmutableList<String>)`.

* Builders can have getter methods, which return the currently-set value for a property.

* The classes in the autovalue jar are now shaded with a `$` so they never appear in IDE autocompletion.

* AutoValue now uses its own implementation of a subset of Apache Velocity, so there will no longer be problems with interference between the Velocity that was bundled with AutoValue and other versions that might be present.

## Bug fixes

* Explicit check for nested @AutoValue classes being private, or not being static. Otherwise the compiler errors could be hard to understand, especially in IDEs.

* An Eclipse bug that could occasionally lead to exceptions in the IDE has been fixed (#200).

* Fixed a bug where AutoValue generated incorrect code if a method with a generic parameter was inherited by a class that supplies a concrete type for that parameter. For example `StringIterator implements Iterator<String>`, where the type of `next()` is String, not T.

* In AutoValueProcessor, fixed an exception that happened if the same abstract method was inherited from more than one parent (#267).

* AutoValue now works correctly in an environment where `javax.annotation.Generated` does not exist.

* Properties marked `@Nullable` now get `@Nullable` on the corresponding constructor parameters in the generated class.
