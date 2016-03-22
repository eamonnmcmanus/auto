# Auto-Value Changes

## 1.1 -> 1.2

### Functional changes

  * A **provisional** extension API has been introduced. This **will change**
    in a later release. If you want to use it regardless, see the
    [AutoValueExtension] class.

  * Properties of primitive array type (e.g. `byte[]`) are no longer cloned
    when read. If your @AutoValue class includes an array property, by default
    it will get a compiler warning, which can be suppressed with
    `@SuppressWarnings("mutable")`.

  * An AutoValue.Builder type can now define both the setter and builder
    methods like so:
    
    ```
      ...
      void setStrings(ImmutableList<Strings>)
      ImmutableList.Builder<Strings> stringsBuilder()
      ...
    ```
    At runtime, if `stringsBuilder()...` is called then it is an error to call
    `setStrings(...)` afterwards.

  * The classes in the autovalue jar are now shaded with a `$` so they never
    appear in IDE autocompletion.

  * AutoValue now uses its own implementation of a subset of Apache Velocity,
    so there will no longer be problems with interference between the Velocity
    that was bundled with AutoValue and other versions that might be present.

### Bugs fixed

  * Explicit check for nested @AutoValue classes being private, or not being
    static. Otherwise the compiler errors could be hard to understand,
    especially in IDEs.

  * An Eclipse bug that could occasionally lead to exceptions in the IDE has
    been fixed (GitHub issue #200).

  * Fixed a bug where AutoValue generated incorrect code if a method with a
    type parameter was inherited by a class that supplies a concrete type for
    that parameter. For example `StringIterator implements Iterator<String>`,
    where the type of `next()` is String, not `T`.

  * In AutoValueProcessor, fixed an exception that happened if the same abstract
    method was inherited from more than one parent (Github Issue #267).

  * AutoValue now works correctly in an environment where
    `@javax.annotation.Generated` does not exist.

  * Properties marked `@Nullable` now get `@Nullable` on the corresponding
    constructor parameters in the generated class.

[AutoValueExtension]: value/src/main/java/com/google/auto/value/extension/AutoValueExtension.java

