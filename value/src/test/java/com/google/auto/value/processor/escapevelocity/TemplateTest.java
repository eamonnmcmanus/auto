package com.google.auto.value.processor.escapevelocity;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.truth.Truth;

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

  private void compare(String template, Map<String, Object> vars) {
    String velocityRendered = velocityRender(template, vars);
    String escapeVelocityRendered;
    try {
      escapeVelocityRendered = Template.from(new StringReader(template)).evaluate(vars);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
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
    compare("", ImmutableMap.<String, Object>of());
  }

  @Test
  public void literalOnly() {
    compare("In the reign of James the Second \n It was generally reckoned\n",
        ImmutableMap.<String, Object>of());
  }

  @Test
  public void substituteNoBraces() {
    compare(" $x ", ImmutableMap.of("x", (Object) 1729));
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
    compare("<$list.add(\"foo\")...$list>",
        ImmutableMap.of("list", (Object) new ArrayList<String>()));
  }
}
