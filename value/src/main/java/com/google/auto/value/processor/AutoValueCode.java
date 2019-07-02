package com.google.auto.value.processor;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.joining;

import com.google.auto.common.GeneratedAnnotationSpecs;
import com.google.auto.common.MoreTypes;
import com.google.auto.value.processor.AutoValueOrOneOfProcessor.Property;
import com.google.auto.value.processor.BuilderSpec.PropertyGetter;
import com.google.auto.value.processor.BuilderSpec.PropertySetter;
import com.google.auto.value.processor.PropertyBuilderClassifier.PropertyBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import java.util.Arrays;
import java.util.Optional;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * @author Éamonn McManus
 */
class AutoValueCode {

  static String generate(AutoValueTemplateVars vars, Elements elementUtils, Types typeUtils) {
    return new AutoValueCode(vars, elementUtils, typeUtils).generate();
  }

  private final AutoValueTemplateVars vars;
  private final Elements elementUtils;
  private final Types typeUtils;

  private AutoValueCode(
      AutoValueTemplateVars vars, Elements elementUtils, Types typeUtils) {
    this.vars = vars;
    this.elementUtils = elementUtils;
    this.typeUtils = typeUtils;
  }

  private String generate() {
    TypeSpec.Builder builder = TypeSpec.classBuilder(vars.subclass)
        .addAnnotations(autoValueClassAnnotations())
        .addModifiers(vars.modifiers.toArray(new Modifier[0]))
        .addTypeVariables(autoValueTypeVariables())
        .superclass(autoValueSuperclass())
        .addFields(autoValueFields())
        .addMethod(autoValueConstructor())
        .addMethods(generateGetters());
    autoValueToString().ifPresent(builder::addMethod);
    autoValueEquals().ifPresent(builder::addMethod);
    autoValueHashCode().ifPresent(builder::addMethod);
    autoValueSerialVersionUID().ifPresent(builder::addField);
    autoValueToBuilderMethods().forEach(builder::addMethod);
    autoValueBuilder().ifPresent(builder::addType);
    TypeSpec autoValueClass = builder.build();
    JavaFile javaFile =
        JavaFile.builder(vars.pkg, autoValueClass)
            .skipJavaLangImports(true)
            .build();
    return javaFile.toString();
  }

  private ImmutableList<TypeVariableName> autoValueTypeVariables() {
    return vars.origClass.getTypeParameters().stream()
        .map(p ->
            TypeVariableName.get(p)
                .annotated(p.getAnnotationMirrors().stream()
                    .map(AnnotationSpec::get)
                    .collect(toImmutableList())))
        .collect(toImmutableList());
  }

  private TypeName autoValueSuperclass() {
    ImmutableList<TypeName> typeArguments = vars.origClass.getTypeParameters().stream()
        .map(t -> TypeVariableName.get(t.getSimpleName().toString()))
        .collect(toImmutableList());
    return typeArguments.isEmpty()
        ? ClassName.get(vars.origClass)
        : ParameterizedTypeName.get(
            ClassName.get(vars.origClass), typeArguments.toArray(new TypeName[0]));
  }

  private ImmutableList<AnnotationSpec> autoValueClassAnnotations() {
    ImmutableList.Builder<AnnotationSpec> builder = ImmutableList.builder();
    vars.gwtCompatibleAnnotation.ifPresent(a -> builder.add(AnnotationSpec.get(a)));
    vars.annotations.forEach(a -> builder.add(AnnotationSpec.get(a)));
    GeneratedAnnotationSpecs.generatedAnnotationSpec(
        elementUtils, SourceVersion.latest(), AutoValueProcessor.class).ifPresent(builder::add);
    return builder.build();
  }

  private ImmutableList<FieldSpec> autoValueFields() {
    return vars.props.stream().
        map(p -> FieldSpec.builder(
            TypeName.get(p.getTypeMirror()), p.toString(), Modifier.PRIVATE, Modifier.FINAL)
                .addAnnotations(p.getFieldAnnotations())
                .build())
        .collect(toImmutableList());
  }

  private MethodSpec autoValueConstructor() {
    MethodSpec.Builder builder = MethodSpec.constructorBuilder();
    if (vars.isFinal && vars.builderType.isPresent()) {
      builder.addModifiers(Modifier.PRIVATE);
    }
    for (Property p : vars.props) {
      ParameterSpec.Builder paramBuilder =
          ParameterSpec.builder(TypeName.get(p.getTypeMirror()), p.toString());
      p.nullableAnnotation().ifPresent(a -> paramBuilder.addAnnotation(a));
      builder.addParameter(paramBuilder.build());

      if (!p.getKind().isPrimitive()
          && !p.isNullable()
          && (!vars.builderType.isPresent() || !vars.isFinal)) {
        // We don't need a null check if the type is primitive or @Nullable. We also don't need it
        // if there is a builder, since the build() method will check for us. However, if there is
        // a builder but there are also extensions (!$isFinal) then we can't omit the null check
        // because the constructor is called from the extension code.
        // TODO: Possibly put the last conjunct in a boolean since its negation is used above.
        if (vars.identifiers) {
          builder.beginControlFlow("if ($L == null)", p.toString());
          builder.addStatement(
              "throw new $T(\"Null $L\")", NullPointerException.class, p.toString());
          builder.endControlFlow();
        } else {
          builder.addStatement("(($T) $L).getClass()", Object.class, p.toString());
        }
      }
      builder.addStatement("this.$1L = $1L", p.toString());
    }
    return builder.build();
  }

  private ImmutableList<MethodSpec> generateGetters() {
    return vars.props.stream()
        .map(p -> MethodSpec.methodBuilder(p.getGetter())
            .addAnnotations(p.getMethodAnnotations())
            .addAnnotation(Override.class)
            .addModifiers(p.getAccess())
            .returns(p.getTypeName())
            .addStatement("return $L", p.toString())
            .build()
        )
        .collect(toImmutableList());
  }

  private Optional<MethodSpec> autoValueToString() {
    if (!vars.toString) {
      return Optional.empty();
    }
    CodeBlock.Builder body = CodeBlock.builder();
    String name = vars.identifiers ? vars.simpleClassName : "";
    body.add("$[return \"$L{\"", name);
    String sep = "";
    for (Property p : vars.props) {
      CodeBlock value = (p.getKind() == TypeKind.ARRAY)
          ? CodeBlock.of("$T.toString($L)", Arrays.class, p.toString())
          : CodeBlock.of("$L", p.toString());
      String pName = vars.identifiers ? p.getName() : "";
      body.add("\n$L+ \"$L=\" + $L", sep, pName, value);
      sep = "+ \", \" ";
    }
    body.add("\n+ \"}\";$]\n");
    return Optional.of(
        MethodSpec.methodBuilder("toString")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(String.class)
            .addCode(body.build())
            .build());
  }

  private Optional<MethodSpec> autoValueEquals() {
    if (!vars.equals) {
      return Optional.empty();
    }
    CodeBlock.Builder body = CodeBlock.builder()
        .beginControlFlow("if (o == this)")
        .addStatement("return true")
        .endControlFlow()
        .beginControlFlow(
            "if (o instanceof $T$L)", ClassName.get(vars.origClass), vars.wildcardTypes);
    if (vars.props.isEmpty()) {
      body.addStatement("return true");
    } else {
      body.addStatement(
          "$1T$2L that = ($1T$2L) o", ClassName.get(vars.origClass), vars.wildcardTypes);
      body.add("$[return ");
      String sep = "";
      for (Property p : vars.props) {
        body.add("$L$L", sep, equalsThatExpression(p, vars.subclass));
        sep = "\n&& ";
      }
      body.add(";$]\n");
    }
    body.endControlFlow();
    body.addStatement("return false");
    return Optional.of(
        MethodSpec.methodBuilder("equals")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(TypeName.get(vars.equalsParameterType), "o")
            .returns(boolean.class)
            .addCode(body.build())
            .build());
  }

  static CodeBlock equalsThatExpression(Property p, String subclass) {
    switch (p.getKind()) {
      case FLOAT:
        return CodeBlock.of(
            "$1T.floatToIntBits(this.$2L()) == $1T.floatToIntBits(that.$2L())", Float.class, p.toString());
      case DOUBLE:
        return CodeBlock.of(
            "$1T.doubleToLongBits(this.$2L()) == $1T.doubleToLongBits(that.$2L())", Double.class, p.toString());
      case ARRAY:
        return CodeBlock.of(
            "$1T.equals(this.$2L, (that instanceof $3L) ? (($3L) that).$2L : that.$4L())",
            Arrays.class, p.toString(), subclass, p.getGetter());
    }
    if (p.getKind().isPrimitive()) {
      return CodeBlock.of("this.$L == that.$L()", p.toString(), p.getGetter());
    } else if (p.isNullable()) {
      return CodeBlock.of(
          "(this.$1L == null ? that.$2L() == null : this.$1L.equals(that.$2L()))",
          p.toString(), p.getGetter());
    } else {
      return CodeBlock.of("this.$L.equals(that.$L())", p.toString(), p.getGetter());
    }
  }

  private Optional<MethodSpec> autoValueHashCode() {
    if (!vars.hashCode) {
      return Optional.empty();
    }
    CodeBlock.Builder body = CodeBlock.builder()
        .addStatement("int h$$ = 1");
    for (Property p : vars.props) {
      body.addStatement("h$$ *= 1000003");
      body.addStatement("h$$ ^= $L", hashCodeExpression(p));
    }
    body.addStatement("return h$$");
    return Optional.of(
        MethodSpec.methodBuilder("hashCode")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(int.class)
            .addCode(body.build())
            .build());
  }

  static CodeBlock hashCodeExpression(Property p) {
    switch (p.getKind()) {
      case LONG:
        return CodeBlock.of("(int) (($1L >>> 32) ^ $1L)", p.toString());
      case FLOAT:
        return CodeBlock.of("$T.floatToIntBits($L)", Float.class, p.toString());
      case DOUBLE:
        CodeBlock bits = CodeBlock.of("$T.doubleToLongBits($L)", Double.class, p.toString());
        return CodeBlock.of("(int)(($1L >>> 32) ^ $1L)", bits);
      case BOOLEAN:
        return CodeBlock.of("$L ? 1231 : 1237", p.toString());
      case ARRAY:
        return CodeBlock.of("$T.hashCode($L)", Arrays.class, p.toString());
    }
    if (p.getKind().isPrimitive()) {
      return CodeBlock.of("$L", p.toString());
    } else if (p.isNullable()) {
      return CodeBlock.of("($1L == null) ? 0 : $1L.hashCode()", p.toString());
    } else {
      return CodeBlock.of("$L.hashCode()", p.toString());
    }
  }

  private Optional<FieldSpec> autoValueSerialVersionUID() {
    if (vars.serialVersionUID.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        FieldSpec.builder(
            long.class, "serialVersionUID", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer(vars.serialVersionUID)
            .build());
  }

  private static final ImmutableSet<Modifier> PUBLIC_PROTECTED =
      ImmutableSet.of(Modifier.PUBLIC, Modifier.PROTECTED);

  private ImmutableList<MethodSpec> autoValueToBuilderMethods() {
    if (!vars.builderType.isPresent()) {
      return ImmutableList.of(); // Can happen in error cases.
    }
    return vars.toBuilderMethods.stream()
        .map(toBuilder ->
            MethodSpec.methodBuilder(toBuilder.getSimpleName().toString())
                .addAnnotation(Override.class)
                .addModifiers(Sets.intersection(toBuilder.getModifiers(), PUBLIC_PROTECTED))
                .returns(builderActualType())
                .addStatement("return new Builder$L(this)", actualTypes())
                .build())
        .collect(toImmutableList());
  }

  private TypeName builderActualType() {
    TypeElement builderType = vars.builderType.get();
    ClassName builderClassName = ClassName.get(builderType);
    if (builderType.getTypeParameters().isEmpty()) {
      return builderClassName;
    }
    ImmutableList<TypeVariableName> params = builderType.getTypeParameters().stream()
        .map(TypeVariableName::get)
        .collect(toImmutableList());
    return ParameterizedTypeName.get(builderClassName, params.toArray(new TypeVariableName[0]));
  }

  private Optional<TypeSpec> autoValueBuilder() {
    if (!vars.builderType.isPresent()) {
      return Optional.empty();
    }
    TypeElement builderType = vars.builderType.get();
    TypeSpec.Builder builderBuilder =
        TypeSpec.classBuilder("Builder").addModifiers(Modifier.STATIC, Modifier.FINAL);
    builderType.getTypeParameters().stream()
        .map(TypeVariableName::get)
        .forEach(builderBuilder::addTypeVariable);
    if (builderType.getKind().isInterface()) {
      builderBuilder.addSuperinterface(builderActualType());
    } else {
      builderBuilder.superclass(builderActualType());
    }
    for (Property p : vars.props) {
      addBuilderFields(p, builderBuilder);
    }
    builderBuilder.addMethod(MethodSpec.constructorBuilder().build());
    builderCopyConstructor().ifPresent(builderBuilder::addMethod);
    for (Property p : vars.props) {
      builderSetters(p).forEach(builderBuilder::addMethod);
      builderPropertyBuilder(p).ifPresent(builderBuilder::addMethod);
      builderGetter(p).ifPresent(builderBuilder::addMethod);
    }
    builderBuilder.addMethod(builderBuild());
    return Optional.of(builderBuilder.build());
  }

  private void addBuilderFields(Property p, TypeSpec.Builder builderBuilder) {
    if (p.getKind().isPrimitive()) {
      builderBuilder.addField(
          FieldSpec.builder(TypeName.get(p.getTypeMirror()).box(), p.toString(), Modifier.PRIVATE)
               .build());
    } else {
      PropertyBuilder propertyBuilder = vars.builderPropertyBuilders.get(p.toString());
      if (propertyBuilder != null) {
        // If you have ImmutableList.Builder<String> stringsBuilder() then we define two fields:
        // private ImmutableList.Builder<String> stringsBuilder$;
        // private ImmutableList<String> strings;
        builderBuilder.addField(
            FieldSpec.builder(
                TypeName.get(propertyBuilder.getBuilderTypeMirror()),
                propertyBuilder.getName(),
                Modifier.PRIVATE)
                .build());
      }
      FieldSpec.Builder fieldBuilder =
          FieldSpec.builder(TypeName.get(p.getTypeMirror()), p.toString(), Modifier.PRIVATE);
      Optionalish optional = p.getOptional();
      if (optional != null && !p.isNullable()) {
        TypeElement optionalType = MoreTypes.asTypeElement(optional.getOptionalType());
        fieldBuilder.initializer("$T.$L()", ClassName.get(optionalType), optional.getEmptyName());
      }
      builderBuilder.addField(fieldBuilder.build());
    }
  }

  private Optional<MethodSpec> builderCopyConstructor() {
    if (vars.toBuilderMethods.isEmpty()) {
      return Optional.empty();
    }
    MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PRIVATE)
        .addParameter(
            ParameterSpec.builder(originalAutoValueClassWithActualTypes(), "source").build());
    for (Property p : vars.props) {
      constructor.addStatement("this.$L = source.$L()", p.toString(), p.getGetter());
    }
    return Optional.of(constructor.build());
  }

  private TypeName originalAutoValueClassWithActualTypes() {
    ClassName raw = ClassName.get(vars.origClass);
    if (vars.origClass.getTypeParameters().isEmpty()) {
      return raw;
    }
    ImmutableList<TypeVariableName> names = vars.origClass.getTypeParameters().stream()
        .map(t -> TypeVariableName.get(t.getSimpleName().toString()))
        .collect(toImmutableList());
    return ParameterizedTypeName.get(raw, names.toArray(new TypeName[0]));
  }

  private ImmutableList<MethodSpec> builderSetters(Property p) {
    ImmutableList.Builder<MethodSpec> setters = ImmutableList.builder();
    for (PropertySetter setter : vars.builderSetters.get(p.getName())) {
      setters.add(builderSetter(p, setter));
    }
    return setters.build();
  }

  private MethodSpec builderSetter(Property p, PropertySetter setter) {
    // TODO @Nullable
    VariableElement param = setter.getSetter().getParameters().get(0);
    ParameterSpec parameterSpec = ParameterSpec.get(param);
    if (p.nullableAnnotation().isPresent()) {
      parameterSpec = parameterSpec.toBuilder().addAnnotation(p.nullableAnnotation().get()).build();
    }
    MethodSpec.Builder builder = MethodSpec.methodBuilder(setter.getName())
        .addAnnotation(Override.class)
        .addModifiers(Sets.intersection(setter.getSetter().getModifiers(), PUBLIC_PROTECTED))
        .returns(builderActualType())
        .addParameter(parameterSpec);
    // Omit null check for primitive, or @Nullable, or if we are going to be calling a copy method
    // such as Optional.of, which will have its own null check if appropriate.
    if (!setter.getPrimitiveParameter()
        && !p.isNullable()
        && setter.copy(p, p.toString()).toString().equals(p.toString())) {
      if (vars.identifiers) {
        builder
            .beginControlFlow("if ($L == null)", param)
            .addStatement("throw new $T(\"Null $L\")", NullPointerException.class, p.getName())
            .endControlFlow();
      } else {
        // Just throw NullPointerException with no message if it's null.
        // The Object cast has no effect on the code but silences an ErrorProne warning.
        builder.addStatement("(($T) $L).getClass()", Object.class, param);
      }
    }
    PropertyBuilder propertyBuilder = vars.builderPropertyBuilders.get(p.getName());
    if (propertyBuilder != null) {
      builder.beginControlFlow("if ($L != null)", propertyBuilder.getName());
      if (vars.identifiers) {
        builder.addStatement("throw new $1T(\"Cannot set $2L after calling $2LBuilder()\")",
            IllegalStateException.class, p.getName());
      } else {
        builder.addStatement("throw new $T()", IllegalStateException.class);
      }
      builder.endControlFlow();
    }
    return builder
        .addStatement("this.$L = $L",
            p.toString(), setter.copy(p, param.getSimpleName().toString()))
        .addStatement("return this")
        .build();
  }

  private Optional<MethodSpec> builderPropertyBuilder(Property p) {
    PropertyBuilder propertyBuilder = vars.builderPropertyBuilders.get(p.getName());
    if (propertyBuilder != null) {
      return Optional.of(builderPropertyBuilder(p, propertyBuilder));
    } else {
      return Optional.empty();
    }
  }

  private MethodSpec builderPropertyBuilder(Property p, PropertyBuilder propertyBuilder) {
    MethodSpec.Builder method = MethodSpec.methodBuilder(p.getName() + "Builder")
        .addAnnotation(Override.class)
        .addModifiers(
            Sets.intersection(
                PUBLIC_PROTECTED, propertyBuilder.getPropertyBuilderMethod().getModifiers()))
        .returns(TypeName.get(propertyBuilder.getBuilderTypeMirror()))
        .beginControlFlow("if ($L == null)", propertyBuilder.getName());
        // This is the first time someone has asked for the builder. If the property it sets already
        // has a value (because it came from a toBuilder() call on the AutoValue class, or because
        // there is also a setter for this property) then we copy that value into the builder.
        // Otherwise the builder starts out empty.
        // If we have neither a setter nor a toBuilder() method, then the builder always starts
        // off empty.
    if (vars.builderSetters.get(p.getName()).isEmpty() && vars.toBuilderMethods.isEmpty()) {
      method.addStatement("$L = $L", propertyBuilder.getName(), propertyBuilder.getInitializer());
    } else {
      method.beginControlFlow("if ($L == null)", p.toString())
          .addStatement("$L = $L", propertyBuilder.getName(), propertyBuilder.getInitializer())
          .nextControlFlow("else");
      if (propertyBuilder.getBuiltToBuilder() != null) {
        method.addStatement("$L = $L.$L()",
            propertyBuilder.getName(), p.toString(), propertyBuilder.getBuiltToBuilder());
      } else {
        method.addStatement("$L = $L", propertyBuilder.getName(), propertyBuilder.getInitializer())
            .addStatement(
                "$L.$L($L)", propertyBuilder.getName(), propertyBuilder.getCopyAll(), p.toString());
      }
      method.addStatement("$L = null", p.toString())
          .endControlFlow();
    }
    return method
        .endControlFlow()
        .addStatement("return $L", propertyBuilder.getName())
        .build();
  }

  private Optional<MethodSpec> builderGetter(Property p) {
    PropertyGetter getter = vars.builderGetters.get(p.getName());
    if (getter != null) {
      return Optional.of(builderGetter(p, getter));
    } else {
      return Optional.empty();
    }
  }

  private MethodSpec builderGetter(Property p, PropertyGetter getter) {
    MethodSpec.Builder method = MethodSpec.overriding(getter.getMethod());
    Optionalish optional = getter.getOptional();
    if (optional != null) {
      ClassName optionalType = ClassName.get(MoreTypes.asTypeElement(optional.getOptionalType()));
      method.beginControlFlow("if ($L == null)", p.toString())
          .addStatement("return $T.$L()", optionalType, optional.getEmptyName())
          .nextControlFlow("else")
          .addStatement("return $T.of($L)", optionalType, p.toString())
          .endControlFlow();
    } else {
      if (vars.builderRequiredProperties.contains(p)) {
        method.beginControlFlow("if ($L == null)", p.toString());
        if (vars.identifiers) {
          method.addStatement(
              "throw new $T(\"Property \\\"$L\\\" has not been set\")",
              IllegalStateException.class,
              p.getName());
        } else {
          method.addStatement("throw new $T()", IllegalStateException.class);
        }
        method.endControlFlow();
      }
      PropertyBuilder propertyBuilder = vars.builderPropertyBuilders.get(p.getName());
      if (propertyBuilder != null) {
        method.beginControlFlow("if ($L != null)", propertyBuilder.getName())
            .addStatement("return $L.build()", propertyBuilder.getName())
            .endControlFlow()
            .beginControlFlow("if ($L == null)", p.toString())
            .addCode(propertyBuilder.getBeforeInitDefault())
            .addStatement("$L = $L", p.toString(), propertyBuilder.getInitDefault())
            .endControlFlow();
      }
      method.addStatement("return $L", p.toString());
    }
    return method.build();
  }

  private MethodSpec builderBuild() {
    MethodSpec.Builder method = MethodSpec.overriding(vars.buildMethod.get());
    for (Property p : vars.props) {
      PropertyBuilder propertyBuilder = vars.builderPropertyBuilders.get(p.getName());
      if (propertyBuilder != null) {
        method.beginControlFlow("if ($L != null)", propertyBuilder.getName())
            .addStatement("this.$L = $L.build()", p.toString(), propertyBuilder.getName())
            .nextControlFlow("else if (this.$L == null)", p.toString())
            .addCode(propertyBuilder.getBeforeInitDefault())
            .addStatement("this.$L = $L", p.toString(), propertyBuilder.getInitDefault())
            .endControlFlow();
      }
    }
    if (!vars.builderRequiredProperties.isEmpty()) {
      if (vars.identifiers) { // build a friendly message showing all missing properties
        method.addStatement("$T missing = \"\"", String.class);
        for (Property p : vars.builderRequiredProperties) {
          method.beginControlFlow("if (this.$L == null)", p.toString())
              .addStatement("missing += \" $L\"", p.getName())
              .endControlFlow();
        }
        method.beginControlFlow("if (!missing.isEmpty())")
            .addStatement(
                "throw new $T(\"Missing required properties:\" + missing)",
                IllegalStateException.class)
            .endControlFlow();
      } else { // just throw an exception if anything is missing
        String condition = vars.builderRequiredProperties.stream()
            .map(p -> "this." + p + " == null")
            .collect(joining(" || "));
        method.beginControlFlow("if ($L)", condition)
            .addStatement("throw new $T()", IllegalStateException.class)
            .endControlFlow();
      }
    }
    CodeBlock params =
        vars.props.stream()
            .map(p -> CodeBlock.of("this.$L", p.toString()))
            .collect(CodeBlock.joining(",\n"));
    return method
        .addStatement("return new $L$L($L)", vars.finalSubclass, actualTypes(), params)
        .build();
  }

  private CodeBlock actualTypes() {
    return
        vars.origClass.getTypeParameters().isEmpty()
            ? CodeBlock.of("")
            : autoValueTypeVariables().stream()
                .map(v -> CodeBlock.of("$L", v))
                .collect(CodeBlock.joining(", ", "<", ">"));
  }
}
