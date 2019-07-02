package com.google.auto.value.processor;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.joining;

import com.google.auto.common.GeneratedAnnotationSpecs;
import com.google.auto.value.processor.AutoValueOrOneOfProcessor.Property;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import java.util.Collections;
import java.util.List;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;

/**
 * @author Éamonn McManus
 */
class AutoOneOfCode {
  static String generate(AutoOneOfTemplateVars vars, Elements elementUtils) {
    return new AutoOneOfCode(vars, elementUtils).generate();
  }

  private final AutoOneOfTemplateVars vars;
  private final Elements elementUtils;
  private final List<WildcardTypeName> wildcards;
  private final List<TypeVariableName> typeVariables;

  private AutoOneOfCode(AutoOneOfTemplateVars vars, Elements elementUtils) {
    this.vars = vars;
    this.elementUtils = elementUtils;
    WildcardTypeName wildcard = WildcardTypeName.subtypeOf(Object.class);
    this.wildcards = Collections.nCopies(vars.origClass.getTypeParameters().size(), wildcard);
    this.typeVariables = vars.origClass.getTypeParameters().stream()
        .map(TypeVariableName::get)
        .collect(toImmutableList());
  }

  private String generate() {
    TypeSpec.Builder type = TypeSpec.classBuilder(vars.generatedClass)
        .addModifiers(Modifier.FINAL);
    GeneratedAnnotationSpecs.generatedAnnotationSpec(
        elementUtils, SourceVersion.latest(), AutoOneOfProcessor.class)
        .ifPresent(a -> type.addAnnotation(a));
    type.addMethod(
        MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
    for (Property p : vars.props) {
      type.addMethod(factoryMethod(p));
    }
    type.addType(parentClass());
    for (Property p : vars.props) {
      type.addType(propertyClass(p));
    }
    JavaFile javaFile =
        JavaFile.builder(vars.pkg, type.build())
            .skipJavaLangImports(true)
            .build();
    return javaFile.toString();
  }

  private MethodSpec factoryMethod(Property p) {
    ClassName raw = ClassName.get(vars.origClass);
    if (p.getTypeMirror().getKind() == TypeKind.VOID) {
      TypeName returnType = wildcards.isEmpty()
          ? raw
          : ParameterizedTypeName.get(raw, wildcards.toArray(new TypeName[0]));
      return MethodSpec.methodBuilder(p.toString())
          .addModifiers(Modifier.STATIC)
          .returns(returnType)
          .addStatement("return Impl_$L.INSTANCE", p.toString())
          .build();
    } else {
      MethodSpec.Builder method = MethodSpec.methodBuilder(p.toString())
          .addModifiers(Modifier.STATIC)
          .addParameter(
              ParameterSpec.builder(TypeName.get(p.getTypeMirror()), p.toString()).build());
      String typeArgString;
      if (typeVariables.isEmpty()) {
        method.returns(raw);
        typeArgString = "";
      } else {
        method.addTypeVariables(typeVariables);
        method.returns(ParameterizedTypeName.get(raw, typeVariables.toArray(new TypeName[0])));
        typeArgString = typeVariables.stream().map(v -> v.name).collect(joining(", ", "<", ">"));
      }
      if (!p.getKind().isPrimitive()) {
        method.beginControlFlow("if ($L == null)", p.toString())
            .addStatement("throw new $T()", NullPointerException.class)
            .endControlFlow();
      }
      method.addStatement("return new Impl_$1L$2L($1L)", p.toString(), typeArgString);
      return method.build();
    }
  }

  private static final ImmutableSet<Modifier> PUBLIC_PROTECTED =
      ImmutableSet.of(Modifier.PUBLIC, Modifier.PROTECTED);

  private TypeSpec parentClass() {
    ClassName rawSuperclass = ClassName.get(vars.origClass);
    TypeName superclass = typeVariables.isEmpty()
        ? rawSuperclass
        : ParameterizedTypeName.get(rawSuperclass, typeVariables.toArray(new TypeName[0]));
    TypeSpec.Builder type = TypeSpec.classBuilder("Parent_")
        .addAnnotations(annotations())
        .addModifiers(Modifier.PRIVATE, Modifier.ABSTRACT, Modifier.STATIC)
        .addTypeVariables(typeVariables)
        .superclass(superclass);
    for (Property p : vars.props) {
      type.addMethod(
          MethodSpec.methodBuilder(p.getGetter())
              .addAnnotation(Override.class)
              .addModifiers(p.getAccess().toArray(new Modifier[0]))
              .returns(TypeName.get(p.getTypeMirror()))
              .addStatement(
                  "throw new $T($L().toString())",
                  UnsupportedOperationException.class, vars.kindGetter)
              .build());
    }
    return type.build();
  }

  private ImmutableList<AnnotationSpec> annotations() {
    return vars.annotations.stream().map(AnnotationSpec::get).collect(toImmutableList());
  }

  private TypeSpec propertyClass(Property p) {
    ClassName rawParent = ClassName.get(vars.pkg, vars.generatedClass, "Parent_");
    TypeName parent = typeVariables.isEmpty()
        ? rawParent
        : ParameterizedTypeName.get(rawParent, typeVariables.toArray(new TypeName[0]));
    TypeSpec.Builder type = TypeSpec.classBuilder("Impl_" + p)
        .addAnnotations(annotations())
        .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
        .addTypeVariables(typeVariables)
        .superclass(parent);
    if (p.getKind() == TypeKind.VOID) {
      addVoidClassContents(type, p);
    } else {
      addClassContents(type, p);
    }
    TypeName kindType = TypeName.get(vars.kindType);
    type.addMethod(
        MethodSpec.methodBuilder(vars.kindGetter)
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(kindType)
            .addStatement("return $T.$L", kindType, vars.propertyToKind.get(p.getName()))
            .build());
    return type.build();
  }

  private void addVoidClassContents(TypeSpec.Builder type, Property p) {
    ClassName rawInstanceType = ClassName.get(vars.pkg, vars.generatedClass, "Impl_" + p);
    TypeName instanceType = wildcards.isEmpty()
        ? rawInstanceType
        : ParameterizedTypeName.get(rawInstanceType, wildcards.toArray(new TypeName[0]));
    type.addField(
        FieldSpec.builder(
            instanceType, "INSTANCE", Modifier.STATIC, Modifier.FINAL)
            .initializer("new $L$L()", "Impl_" + p, wildcards.isEmpty() ? "" : "<>")
            .build());
    type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
    type.addMethod(
        MethodSpec.methodBuilder(p.getGetter())
            .addAnnotation(Override.class)
            .addModifiers(p.getAccess())
            .returns(TypeName.VOID)
            .build());
    if (vars.serializable) {
      type.addMethod(
          MethodSpec.methodBuilder("readResolve")
              .addModifiers(Modifier.PRIVATE)
              .returns(Object.class)
              .addStatement("return INSTANCE")
              .build());
    }
    if (vars.toString) {
      type.addMethod(
          MethodSpec.methodBuilder("toString")
              .addAnnotation(Override.class)
              .addModifiers(Modifier.PUBLIC)
              .returns(String.class)
              .addStatement("return \"$L{$L}\"", vars.simpleClassName, p.getName())
              .build());
    }
    // The implementations of equals and hashCode are equivalent to the ones
    // we inherit from Object. We only need to define them if they're redeclared
    // as abstract in an ancestor class. But currently we define them always.
    if (vars.equals) {
      type.addMethod(
          MethodSpec.methodBuilder("equals")
              .addAnnotation(Override.class)
              .addModifiers(Modifier.PUBLIC)
              .addParameter(TypeName.get(vars.equalsParameterType), "x")
              .returns(boolean.class)
              .addStatement("return x == this")
              .build());
    }
    if (vars.hashCode) {
      type.addMethod(
          MethodSpec.methodBuilder("hashCode")
              .addAnnotation(Override.class)
              .addModifiers(Modifier.PUBLIC)
              .returns(int.class)
              .addStatement("return $T.identityHashCode(this)", System.class)
              .build());
    }
  }

  private void addClassContents(TypeSpec.Builder type, Property p) {
    TypeName pType = TypeName.get(p.getTypeMirror());
    type.addField(
        FieldSpec.builder(pType, p.toString(), Modifier.PRIVATE, Modifier.FINAL)
        .build());
    type.addMethod(
        MethodSpec.constructorBuilder()
            .addParameter(pType, p.toString())
            .addStatement("this.$1L = $1L", p.toString())
            .build());
    type.addMethod(
        MethodSpec.methodBuilder(p.getGetter())
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(pType)
            .addStatement("return $L", p.toString())
            .build());
    if (vars.toString) {
      type.addMethod(
          MethodSpec.methodBuilder("toString")
              .addAnnotation(Override.class)
              .addModifiers(Modifier.PUBLIC)
              .returns(String.class)
              .addStatement("return \"$L{$L=\" + this.$L + \"}\"",
                  vars.simpleClassName, p.getName(), p.toString())
              .build());
    }
    if (vars.equals) {
      ClassName origClass = ClassName.get(vars.origClass);
      TypeName withWildcards = wildcards.isEmpty()
          ? origClass
          : ParameterizedTypeName.get(origClass, wildcards.toArray(new TypeName[0]));
      type.addMethod(
          MethodSpec.methodBuilder("equals")
              .addAnnotation(Override.class)
              .addModifiers(Modifier.PUBLIC)
              .addParameter(
                  ParameterSpec.builder(TypeName.get(vars.equalsParameterType), "x").build())
              .returns(boolean.class)
              .beginControlFlow("if (x instanceof $T)", origClass)
              .addStatement("$1T that = ($1T) x", withWildcards)
              .addCode("$[return this.$1L() == that.$1L()\n", vars.kindGetter)
              .addCode("&& $L;$]\n", AutoValueCode.equalsThatExpression(p, "Impl_" + p))
              .nextControlFlow("else")
              .addStatement("return false")
              .endControlFlow()
              .build());
    }
    if (vars.hashCode) {
      type.addMethod(
          MethodSpec.methodBuilder("hashCode")
              .addAnnotation(Override.class)
              .addModifiers(Modifier.PUBLIC)
              .returns(int.class)
              .addStatement("return $L", AutoValueCode.hashCodeExpression(p))
              .build());
    }
  }
}
