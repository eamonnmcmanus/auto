package com.google.auto.value.processor.escapevelocity;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assert_;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.RuntimeInstance;
import org.apache.velocity.runtime.log.NullLogChute;
import org.apache.velocity.runtime.parser.ParseException;
import org.apache.velocity.runtime.parser.node.SimpleNode;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
@RunWith(JUnit4.class)
public class TemplateTest {
  @Rule public TestName testName = new TestName();

  private RuntimeInstance velocityRuntimeInstance;
  
  @Before
  public void setUp() {
    velocityRuntimeInstance = new RuntimeInstance();

    // Ensure that $undefinedvar will produce an exception rather than outputting $undefinedvar.
    velocityRuntimeInstance.setProperty(RuntimeConstants.RUNTIME_REFERENCES_STRICT, "true");
    velocityRuntimeInstance.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS,
        new NullLogChute());

    // Disable any logging that Velocity might otherwise see fit to do.
    velocityRuntimeInstance.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM, new NullLogChute());

    velocityRuntimeInstance.init();
  }

  private void compare(String template) {
    compare(template, ImmutableMap.<String, Object>of());
  }

  private void compare(String template, Map<String, Object> vars) {
    compare(template, Suppliers.ofInstance(vars));
  }

  private void compare(String template, Supplier<Map<String, Object>> varsSupplier) {
    Map<String, Object> velocityVars = varsSupplier.get();
    String velocityRendered = velocityRender(template, velocityVars);
    Map<String, Object> escapeVelocityVars = varsSupplier.get();
    String escapeVelocityRendered;
    try {
      escapeVelocityRendered =
          Template.from(new StringReader(template)).evaluate(escapeVelocityVars);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    String failure = "from velocity: <" + velocityRendered + ">\n"
        + "from escape velocity: <" + escapeVelocityRendered + ">\n";
    assert_().withFailureMessage(failure).that(escapeVelocityRendered).isEqualTo(velocityRendered);
    assertThat(escapeVelocityRendered).isEqualTo(velocityRendered);
  }

  private String velocityRender(String template, Map<String, Object> vars) {
    VelocityContext velocityContext = new VelocityContext(new TreeMap<String, Object>(vars));
    StringWriter writer = new StringWriter();
    SimpleNode parsedTemplate;
    try {
      parsedTemplate = velocityRuntimeInstance.parse(
          new StringReader(template), testName.getMethodName());
    } catch (ParseException e) {
      throw new AssertionError(e);
    }
    boolean rendered = velocityRuntimeInstance.render(
        velocityContext, writer, parsedTemplate.getTemplateName(), parsedTemplate);
    assertThat(rendered).isTrue();
    return writer.toString();
  }

  @Test
  public void empty() {
    compare("");
  }

  @Test
  public void literalOnly() {
    compare("In the reign of James the Second \n It was generally reckoned\n");
  }

  @Test
  public void comment() {
    compare("line 1 ##\n  line 2");
  }

  @Test
  public void substituteNoBraces() {
    compare(" $x ", ImmutableMap.of("x", (Object) 1729));
    compare(" ! $x ! ", ImmutableMap.of("x", (Object) 1729));
  }

  @Test
  public void substituteWithBraces() {
    compare("a${x}\nb", ImmutableMap.of("x", (Object) "1729"));
  }

  @Test
  public void substitutePropertyNoBraces() {
    compare("=$t.name=", ImmutableMap.of("t", (Object) Thread.currentThread()));
  }

  @Test
  public void substitutePropertyWithBraces() {
    compare("=${t.name}=", ImmutableMap.of("t", (Object) Thread.currentThread()));
  }

  @Test
  public void substituteNestedProperty() {
    compare("\n$t.name.empty\n", ImmutableMap.of("t", (Object) Thread.currentThread()));
  }

  @Test
  public void substituteMethodNoArgs() {
    compare("<$c.size()>", ImmutableMap.of("c", (Object) ImmutableMap.of()));
  }

  @Test
  public void substituteMethodOneArg() {
    compare("<$list.get(0)>", ImmutableMap.of("list", (Object) ImmutableList.of("foo")));
  }

  @Test
  public void substituteMethodTwoArgs() {
    compare("\n$s.indexOf(\"bar\", 2)\n", ImmutableMap.of("s", (Object) "barbarbar"));
  }

  @Test
  public void substituteMethodNoSynthetic() {
    // If we aren't careful, we'll see both the inherited `Set<K> keySet()` from Map
    // and the overridden `ImmutableSet<K> keySet()` in ImmutableMap.
    compare("$map.keySet()", ImmutableMap.of("map", (Object) ImmutableMap.of("foo", "bar")));
  }

  @Test
  public void substituteIndexNoBraces() {
    compare("<$map[\"x\"]>", ImmutableMap.of("map", (Object) ImmutableMap.of("x", "y")));
  }

  @Test
  public void substituteIndexWithBraces() {
    compare("<${map[\"x\"]}>", ImmutableMap.of("map", (Object) ImmutableMap.of("x", "y")));
  }

  @Test
  public void substituteIndexThenProperty() {
    compare("<$map[2].name>", ImmutableMap.of("map", (Object) ImmutableMap.of(2, getClass())));
  }

  public static class Indexable {
    public String get(String y) {
      return "[" + y + "]";
    }
  }

  @Test
  public void substituteExoticIndex() {
    // Any class with a get(X) method can be used with $x[i]
    compare("<$x[\"foo\"]>", ImmutableMap.of("x", (Object) new Indexable()));
  }

  @Test
  public void ifTrueNoElse() {
    compare("x#if (true)y #end z");
    compare("x#if (true)y #end  z");
    compare("x#if (true)y #end\nz");
    compare("x#if (true)y #end\n z");
    compare("x#if (true) y #end\nz");
    compare("x#if (true)\ny #end\nz");
    compare("x#if (true) y #end\nz");
    compare("$x #if (true) y #end $x ", ImmutableMap.of("x", (Object) "!"));
  }

  @Test
  public void ifFalseNoElse() {
    compare("x#if (false)y #end z");
    compare("x#if (false)y #end\nz");
    compare("x#if (false)y #end\n z");
    compare("x#if (false) y #end\nz");
    compare("x#if (false)\ny #end\nz");
    compare("x#if (false) y #end\nz");
  }

  @Test
  public void ifTrueWithElse() {
    compare("x#if (true) a #else b #end z");
  }

  @Test
  public void ifFalseWithElse() {
    compare("x#if (false) a #else b #end z");
  }

  @Test
  public void ifTrueWithElseIf() {
    compare("x#if (true) a #elseif (true) b #else c #end z");
  }

  @Test
  public void ifFalseWithElseIfTrue() {
    compare("x#if (false) a #elseif (true) b #else c #end z");
  }

  @Test
  public void ifFalseWithElseIfFalse() {
    compare("x#if (false) a #elseif (false) b #else c #end z");
  }

  @Test
  public void ifBraces() {
    compare("x#{if}(false)a#{elseif}(false)b #{else}c#{end}z");
  }

  @Test
  public void ifUndefined() {
    compare("#if ($undefined) really? #else indeed #end");
  }

  @Test
  public void forEach() {
    compare("x#foreach ($x in $c) <$x> #end y", ImmutableMap.of("c", (Object) ImmutableList.of()));
    compare("x#foreach ($x in $c) <$x> #end y",
        ImmutableMap.of("c", (Object) ImmutableList.of("foo", "bar", "baz")));
    compare("x#foreach ($x in $c) <$x> #end y",
        ImmutableMap.of("c", (Object) new String[] {"foo", "bar", "baz"}));
    compare("x#foreach ($x in $c) <$x> #end y",
        ImmutableMap.of("c", (Object) ImmutableMap.of("foo", "bar", "baz", "buh")));
  }

  @Test
  public void forEachHasNext() {
    compare("x#foreach ($x in $c) <$x#if ($foreach.hasNext), #end> #end y",
        ImmutableMap.of("c", (Object) ImmutableList.of()));
    compare("x#foreach ($x in $c) <$x#if ($foreach.hasNext), #end> #end y",
        ImmutableMap.of("c", (Object) ImmutableList.of("foo", "bar", "baz")));
  }

  @Test
  public void nestedForEach() {
    String template =
        "$x #foreach ($x in $listOfLists)\n"
        + "  #foreach ($y in $x)\n"
        + "    ($y)#if ($foreach.hasNext), #end\n"
        + "  #end#if ($foreach.hasNext); #end\n"
        + "#end\n"
        + "$x\n";
    Object listOfLists = ImmutableList.of(
        ImmutableList.of("foo", "bar", "baz"), ImmutableList.of("fred", "jim", "sheila"));
    compare(template, ImmutableMap.of("x", 23, "listOfLists", listOfLists));
  }

  @Test
  public void setSpacing() {
    // The spacing in the output from #set is eccentric.
    compare("x#set ($x = 0)x");
    compare("x #set ($x = 0)x");
    compare("x #set ($x = 0) x");
    compare("$x#set ($x = 0)x", ImmutableMap.of("x", (Object) "!"));

    // Velocity WTF: the #set eats the space after $x and other references, so the output is <!x>.
    compare("$x  #set ($x = 0)x", ImmutableMap.of("x", (Object) "!"));
    compare("$x.length()  #set ($x = 0)x", ImmutableMap.of("x", (Object) "!"));
    compare("$x.empty  #set ($x = 0)x", ImmutableMap.of("x", (Object) "!"));
    compare("$x[0]  #set ($x = 0)x", ImmutableMap.of("x", (Object) ImmutableList.of("!")));

    compare("x#set ($x = 0)\n  $x!");

    compare("x  #set($x = 0)  #set($x = 0)  #set($x = 0)  y");

    compare("x ## comment\n  #set($x = 0)  y");
  }

  @Test
  public void simpleSet() {
    compare("$x#set ($x = 17)#set ($y = 23) ($x, $y)", ImmutableMap.of("x", (Object) 1));
  }

  @Test
  public void expressions() {
    compare("#set ($x = 1 + 1) $x");
    compare("#set ($x = 1 + 2 * 3) $x");
    compare("#set ($x = (1 + 1 == 2)) $x");
    compare("#set ($x = (1 + 1 != 2)) $x");
  }

  @Test
  public void associativity() {
    compare("#set ($x = 3 - 2 - 1) $x");
    compare("#set ($x = 16 / 4 / 4) $x");
  }

  @Test
  public void and() {
    compare("#set ($x = false && false) $x");
    compare("#set ($x = false && true) $x");
    compare("#set ($x = true && false) $x");
    compare("#set ($x = true && true) $x");
  }

  @Test
  public void or() {
    compare("#set ($x = false || false) $x");
    compare("#set ($x = false || true) $x");
    compare("#set ($x = true || false) $x");
    compare("#set ($x = true || true) $x");
  }

  @Test
  public void not() {
    compare("#set ($x = !true) $x");
    compare("#set ($x = !false) $x");
  }

  @Test
  public void numbers() {
    compare("#set ($x = 0) $x");
    compare("#set ($x = -1) $x");
    compare("#set ($x = " + Integer.MAX_VALUE + ") $x");
    compare("#set ($x = " + Integer.MIN_VALUE + ") $x");
  }

  @Test
  public void relations() {
    String[] ops = {"==", "!=", "<", ">", "<=", ">="};
    int[] numbers = {-1, 0, 1, 17};
    for (String op : ops) {
      for (int a : numbers) {
        for (int b : numbers) {
          compare("#set ($x = $a " + op + " $b) $x",
              ImmutableMap.<String, Object>of("a", a, "b", b));
        }
      }
    }
  }

  @Test
  public void relationPrecedence() {
    compare("#set ($x = 1 < 2 == 2 < 1) $x");
    compare("#set ($x = 2 < 1 == 2 < 1) $x");
  }

  @Test
  public void funkyEquals() {
    compare("#if (123 == \"123\") yes #end\n"
        + "#if (123 == \"1234\") no #end");
  }

  @Test
  public void simpleMacro() {
    String template =
        "xyz\n"
        + "#macro (m)\n"
        + "hello world\n"
        + "#end\n"
        + "#m() abc #m()\n";
    compare(template);
  }

  @Test
  public void macroWithArgs() {
    String template =
        "$x\n"
        + "#macro (m $x $y)\n"
        + "  #if ($x < $y) less #else greater #end\n"
        + "#end\n"
        + "#m(17 23) #m(23 17) #m(17 17)\n"
        + "$x";
    compare(template, ImmutableMap.of("x", (Object) "tiddly"));
  }

  @Test
  public void conditionalMacroDefinition() {
    String templateFalse =
        "#if (false)\n"
        + "  #macro (m) foo #end\n"
        + "#else\n"
        + "  #macro (m) bar #end\n"
        + "#end\n"
        + "#m()\n";
    compare(templateFalse);

    String templateTrue =
        "#if (true)\n"
        + "  #macro (m) foo #end\n"
        + "#else\n"
        + "  #macro (m) bar #end\n"
        + "#end\n"
        + "#m()\n";
    compare(templateTrue);
  }

  @Test
  public void forwardMacroReference() {
    String template =
        "#m(17)\n"
        + "#macro (m $x)\n"
        + "  !$x!\n"
        + "#end";
    compare(template);
  }

  @Test
  public void macroArgsSeparatedBySpaces() {
    String template =
        "#macro (sum $x $y $z)\n"
        + "  #set ($sum = $x + $y + $z)\n"
        + "  $sum\n"
        + "#end\n"
        + "#sum ($list[0] $list.get(1) 5)\n";
    compare(template, ImmutableMap.of("list", (Object) ImmutableList.of(3, 4)));
  }

  @Test
  public void macroArgsSeparatedByCommas() {
    String template =
        "#macro (sum $x $y $z)\n"
        + "  #set ($sum = $x + $y + $z)\n"
        + "  $sum\n"
        + "#end\n"
        + "#sum ($list[0],$list.get(1),5)\n";
    compare(template, ImmutableMap.of("list", (Object) ImmutableList.of(3, 4)));
  }

  // Macro tests based on http://wiki.apache.org/velocity/MacroEvaluationStrategy

  @Test
  public void callBySharing() {
    // The example on the web page is wrong because $map.put('x', 'a') evaluates to null, which
    // Velocity rejects as a render error. We fix this by ensuring that the returned previous value
    // is not null.
    String template =
        "#macro(callBySharing $x $map)\n"
        + "  #set($x = \"a\")\n"
        + "  $map.put(\"x\", \"a\")\n"
        + "#end\n"
        + "#callBySharing($y $map)\n"
        + "y is $y\n"
        + "map[x] is $map[\"x\"]\n";
    Supplier<Map<String, Object>> makeMap = new Supplier<Map<String, Object>>() {
      @Override public Map<String, Object> get() {
        return ImmutableMap.<String, Object>of(
            "y", "y",
            "map", new HashMap<String, Object>(ImmutableMap.of("x", (Object) "foo")));
      }
    };
    compare(template, makeMap);
  }

  @Test
  public void callByMacro() {
    String template =
        "#macro(callByMacro1 $p)\n"
        + "  not using\n"
        + "#end\n"
        + "#macro(callByMacro2 $p)\n"
        + "  using: $p\n"
        + "  using again: $p\n"
        + "#end\n"
        + "#callByMacro1($x.add(\"t\"))\n"
        + "x = $x\n"
        + "#callByMacro2($x.add(\"t\"))\n";
    Supplier<Map<String, Object>> makeMap = new Supplier<Map<String, Object>>() {
      @Override public Map<String, Object> get() {
        return ImmutableMap.<String, Object>of("x", new ArrayList<Object>());
      }
    };
    compare(template, makeMap);
  }

  @Test
  public void callByValue() {
    String template =
        "#macro(callByValueSwap $a $b)\n"
        + "  $a $b becomes\n"
        + "  #set($tmp = $a)\n"
        + "  #set($a = $b)\n"
        + "  #set($b = $tmp)\n"
        + "  $a $b\n"
        + "#end"
        + "#callByValueSwap(\"a\", \"b\")";
    compare(template);
  }

  // First "Call by macro expansion example" doesn't apply so long as we don't have map literals.

  @Test
  public void nameCaptureSwap() {
    String template =
        "#macro(nameCaptureSwap $a $b)\n"
        + "  $a $b becomes\n"
        + "  #set($tmp = $a)\n"
        + "  #set($a = $b)\n"
        + "  #set($b = $tmp)\n"
        + "  $a $b\n"
        + "#end\n"
        + "#set($x = \"a\")\n"
        + "#set($tmp = \"b\")\n"
        + "#nameCaptureSwap($x $tmp)";
    compare(template);
  }
}
