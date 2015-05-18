package com.google.auto.value.processor.escapevelocity;

import com.google.auto.value.processor.escapevelocity.DirectiveNode.SetNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.AndExpressionNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.ArithmeticExpressionNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.EqualsExpressionNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.LessExpressionNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.NotExpressionNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.OrExpressionNode;
import com.google.auto.value.processor.escapevelocity.Node.EofNode;
import com.google.auto.value.processor.escapevelocity.ReferenceNode.IndexReferenceNode;
import com.google.auto.value.processor.escapevelocity.ReferenceNode.MemberReferenceNode;
import com.google.auto.value.processor.escapevelocity.ReferenceNode.MethodReferenceNode;
import com.google.auto.value.processor.escapevelocity.ReferenceNode.PlainReferenceNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.CommentTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.ElseIfTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.ElseTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.EndTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.ForEachTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.IfTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.MacroDefinitionTokenNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.Reader;
import java.util.LinkedList;

/**
 * A parser that reads input from the given {@link Reader} and parses it to produce a
 * {@link Template}.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
class Parser {
  private static final int EOF = -1;

  private final LineNumberReader reader;

  /**
   * The invariant of this parser is that {@code c} is always the next character of interest.
   * This means that we never have to "unget" a character by reading too far. For example, after
   * we parse an integer, {@code c} will be the first character after the integer, which is exactly
   * the state we will be in when there are no more digits.
   */
  private int c;

  Parser(Reader reader) throws IOException {
    this.reader = new LineNumberReader(reader);
    this.reader.setLineNumber(1);
    next();
  }

  /**
   * Parse the input completely to produce a {@link Template}.
   *
   * <p>Parsing happens in two phases. First, we parse a sequence of "tokens", where tokens include
   * entire references such as <pre>
   *    ${x.foo()[23]}
   * </pre>or entire directives such as<pre>
   *    #set ($x = $y + $z)
   * </pre>But tokens do not span complex constructs. For example,<pre>
   *    #if ($x == $y) something #end
   * </pre>is three tokens:<pre>
   *    #if ($x == $y)
   *    (literal text " something ")
   *   #end
   * </pre>
   *
   * <p>The second phase then takes the sequence of tokens and constructs a parse tree out of it.
   * Some nodes in the parse tree will be unchanged from the token sequence, such as the <pre>
   *    ${x.foo()[23]}
   *    #set ($x = $y + $z)
   * </pre>examples above. But a construct such as the {@code #if ... #end} mentioned above will
   * become a single IfNode in the parse tree in the second phase.
   *
   * <p>The main reason for this approach is that Velocity has two kinds of lexical contexts. At the
   * top level, there can be arbitrary literal text, references like <code>${x.foo()}</code>, and
   * directives like {@code #if} or {@code #set}. Inside the parentheses of a directive, however,
   * neither arbitrary text nor directives can appear, but expressions can, so we need to tokenize
   * the inside of <pre>
   *    #if ($x == $a + $b)
   * </pre>as the five tokens "$x", "==", "$a", "+", "$b". Rather than having a classical
   * parser/lexer combination, where the lexer would need to switch between these two modes, we
   * replace the lexer with an ad-hoc parser that is the first phase described above, and we
   * define a simple parser over the resultant tokens that is the second phase.
   */
  Template parse() throws IOException {
    LinkedList<Node> tokens = Lists.newLinkedList();
    Node token;
    do {
      token = parseNode();
      tokens.add(token);
    } while (!(token instanceof EofNode));
    return new Reparser(tokens).reparse();
  }

  private int lineNumber() {
    return reader.getLineNumber();
  }

  /**
   * Gets the next character from the reader and assigns it to {@code c}. If there are no more
   * characters, sets {@code c} to {@link #EOF} if it is not already.
   */
  private void next() throws IOException {
    if (c != EOF) {
      c = reader.read();
    }
  }

  /**
   * If {@code c} is a space character, keeps reading until {@code c} is a non-space character or
   * there are no more characters.
   */
  private void skipSpace() throws IOException {
    while (Character.isSpaceChar(c)) {
      next();
    }
  }

  /**
   * Gets the next character from the reader, and if it is a space character, keeps reading until
   * a non-space character is found.
   */
  private void nextNonSpace() throws IOException {
    next();
    skipSpace();
  }

  /**
   * Skips any space in the reader, and then throws an exception if the first non-space character
   * found is not the expected one. Sets {@code c} to the first character after that expected one.
   */
  private void expect(char expected) throws IOException {
    skipSpace();
    if (c == expected) {
      next();
    } else {
      throw parseError("Expected " + expected);
    }
  }

  /**
   * Parses a single node from the reader, as part of the first parsing phase.
   * <pre>{@code
   * <template> -> <empty> |
   *               <directive> <template> |
   *               <non-directive> <template>
   * }</pre>
   */
  private Node parseNode() throws IOException {
    while (c == '#') {
      next();
      if (c == '#') {
        return parseComment();
      } else {
        return parseDirective();
      }
    }
    if (c == EOF) {
      return new EofNode(lineNumber());
    }
    return parseNonDirective();
  }

  /**
   * Parses a single non-directive node from the reader.
   * <pre>{@code
   * <non-directive> -> <reference> |
   *                    <text containing neither $ nor #>
   * }</pre>
   */
  private Node parseNonDirective() throws IOException {
    if (c == '$') {
      next();
      if (Character.isLetter(c) || c == '{') {
        return parseReference();
      } else {
        return parsePlainText('$');
      }
    } else {
      int firstChar = c;
      next();
      return parsePlainText(firstChar);
    }
  }

  /**
   * Parses a single directive token from the reader. Directives can be spelled with or without
   * braces, for example {@code #if} or {@code #{if}}. We omit the brace spelling in the productions
   * here: <pre>{@code
   * <directive> -> <if-token> |
   *                <else-token> |
   *                <elseif-token> |
   *                <end-token> |
   *                <foreach-token> |
   *                <set-token> |
   *                <macro-token> |
   *                <macro-call> |
   *                <comment>
   * }</pre>
   */
  private Node parseDirective() throws IOException {
    String directive;
    if (c == '{') {
      next();
      directive = parseId("Directive inside #{...}");
      expect('}');
    } else {
      directive = parseId("Directive");
    }
    Node node;
    if (directive.equals("end")) {
      node = new EndTokenNode(lineNumber());
    } else if (directive.equals("if") || directive.equals("elseif")) {
      expect('(');
      ExpressionNode condition = parseExpression();
      expect(')');
      node = directive.equals("if") ? new IfTokenNode(condition) : new ElseIfTokenNode(condition);
    } else if (directive.equals("else")) {
      node = new ElseTokenNode(lineNumber());
    } else if (directive.equals("foreach")) {
      node = parseForEach();
    } else if (directive.equals("set")) {
      node = parseSet();
    } else if (directive.equals("macro")) {
      node = parseMacroDefinition();
    } else {
      node = parsePossibleMacroCall(directive);
    }
    if (c == '\n') {
      next();
    }
    return node;
  }

  /**
   * Parses a {@code #foreach} token from the reader. <pre>{@code
   * <foreach-token> -> #foreach ( $<id> in <expression>)
   * }</pre>
   */
  private Node parseForEach() throws IOException {
    expect('(');
    expect('$');
    String var = parseId("For-each variable");
    skipSpace();
    boolean bad = false;
    if (c != 'i') {
      bad = true;
    } else {
      next();
      if (c != 'n') {
        bad = true;
      }
    }
    if (bad) {
      throw parseError("Expected 'in' for #foreach");
    }
    next();
    ExpressionNode collection = parseExpression();
    expect(')');
    return new ForEachTokenNode(var, collection);
  }

  /**
   * Parses a {@code #set} token from the reader. <pre>{@code
   * <set-token> -> #set ( $<id> = <expression>)
   * }</pre>
   */
  private Node parseSet() throws IOException {
    expect('(');
    expect('$');
    String var = parseId("#set variable");
    expect('=');
    ExpressionNode expression = parseExpression();
    expect(')');
    return new SetNode(var, expression);
  }

  /**
   * Parses a {@code #macro} token from the reader. <pre>{@code
   * <macro-token> -> #macro ( <id> <macro-parameter-list> )
   * <macro-parameter-list> -> <empty> |
   *                           $<id> <macro-parameter-list>
   * }</pre>
   *
   * <p>Macro parameters are not separated by commas, though method-reference parameters are.
   */
  private Node parseMacroDefinition() throws IOException {
    expect('(');
    skipSpace();
    String name = parseId("Macro name");
    ImmutableList.Builder<String> parameterNames = ImmutableList.builder();
    while (true) {
      skipSpace();
      if (c == ')') {
        break;
      }
      if (c != '$') {
        throw parseError("Macro parameters should look like $name");
      }
      next();
      parameterNames.add(parseId("Macro parameter name"));
    }
    next();  // Skip )
    return new MacroDefinitionTokenNode(lineNumber(), name, parameterNames.build());
  }

  /**
   * Parses an identifier after {@code #} that is not one of the standard directives. The assumption
   * is that it is a call of a macro that is defined in the template. Macro definitions are
   * extracted from the template during the second parsing phase (and not during evaluation of the
   * template as you might expect). This means that a macro can be called before it is defined.
   * <pre>{@code
   * <macro-call> -> # <id> ( <expression-list> )
   * <expression-list> -> <empty> |
   *                      <expression> <expression-list>
   * }</pre>
   */
  private Node parsePossibleMacroCall(String directive) throws IOException {
    skipSpace();
    if (c != '(') {
      throw parseError("Unrecognized directive #" + directive);
    }
    next();
    ImmutableList.Builder<Node> parameterNodes = ImmutableList.builder();
    while (true) {
      skipSpace();
      if (c == ')') {
        break;
      }
      parameterNodes.add(parsePrimary());
      if (c == ',') {
        // The documentation doesn't say so, but you can apparently have an optional comma in
        // macro calls.
        next();
      }
    }
    next();  // Skip )
    return new DirectiveNode.MacroCallNode(lineNumber(), directive, parameterNodes.build());
  }

  /**
   * Parses and discards a comment, which is {@code ##} followed by any number of characters up to
   * and including the next newline.
   */
  private CommentTokenNode parseComment() throws IOException {
    while (c != '\n' && c != EOF) {
      next();
    }
    next();
    return new CommentTokenNode(lineNumber());
  }

  /**
   * Parses plain text, which is text that contains neither {@code $} nor {@code #}. The given
   * {@code firstChar} is the first character of the plain text, and {@link #c} is the second
   * (if the plain text is more than one character).
   */
  private Node parsePlainText(int firstChar) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.appendCodePoint(firstChar);

    literal:
    while (true) {
      switch (c) {
        case EOF:
        case '$':
        case '#':
          break literal;
      }
      sb.appendCodePoint(c);
      next();
    }
    return new ConstantExpressionNode(lineNumber(), sb.toString());
  }

  /**
   * Parses a reference, which is everything that can start with a {@code $}. References can
   * optionally be enclosed in braces, so {@code $x} and {@code ${x}} are the same. Braces are
   * useful when text after the reference would otherwise be parsed as part of it. For example,
   * {@code ${x}y} is a reference to the variable {@code $x}, followed by the plain text {@code y}.
   * Of course {@code $xy} would be a reference to the variable {@code $xy}.
   * <pre>{@code
   * <reference> -> $<reference-no-brace> |
   *                ${<reference-no-brace>}
   * }</pre>
   *
   * <p>On entry to this method, {@link #c} is the character immediately after the {@code $}.
   */
  private ReferenceNode parseReference() throws IOException {
    if (c == '{') {
      next();
      ReferenceNode node = parseReferenceNoBrace();
      if (c == '}') {
        next();
        return node;
      } else {
        throw parseError("Expected } at end of reference");
      }
    } else {
      return parseReferenceNoBrace();
    }
  }

  /**
   * Parses a reference, in the simple form without braces.
   * <pre>{@code
   * <reference-no-brace> -> <id><reference-suffix>
   * }</pre>
   */
  private ReferenceNode parseReferenceNoBrace() throws IOException {
    String id = parseId("Reference");
    ReferenceNode lhs = new PlainReferenceNode(lineNumber(), id);
    return parseReferenceSuffix(lhs);
  }

  /**
   * Parses the modifiers that can appear at the tail of a reference.
   * <pre>{@code
   * <reference-suffix> -> <empty> |
   *                       <reference-member> |
   *                       <reference-index>
   * }</pre>
   *
   * @param lhs the reference node representing the first part of the reference
   * {@code $x} in {@code $x.foo} or {@code $x.foo()}, or later {@code $x.y} in {@code $x.y.z}.
   */
  private ReferenceNode parseReferenceSuffix(ReferenceNode lhs) throws IOException {
    switch (c) {
      case '.':
        return parseReferenceMember(lhs);
      case '[':
        return parseReferenceIndex(lhs);
      default:
        return lhs;
    }
  }

  /**
   * Parses a reference member, which is either a property reference like {@code $x.y} or a method
   * call like {@code $x.y($z)}.
   * <pre>{@code
   * <reference-member> -> .<id><reference-method-or-property><reference-suffix>
   * <reference-method-or-property> -> <id> |
   *                                   <id> ( <method-parameter-list> )
   * }</pre>
   *
   * @param lhs the reference node representing what appears to the left of the dot, like the
   * {@code $x} in {@code $x.foo} or {@code $x.foo()}.
   */
  private ReferenceNode parseReferenceMember(ReferenceNode lhs) throws IOException {
    assert c == '.';
    next();
    String id = parseId("Member");
    ReferenceNode reference;
    if (c == '(') {
      reference = parseReferenceMethodParams(lhs, id);
    } else {
      reference = new MemberReferenceNode(lhs, id);
    }
    return parseReferenceSuffix(reference);
  }

  /**
   * Parses the parameters to a method reference, like {@code $foo.bar($a, $b)}.
   * <pre>{@code
   * <method-parameter-list> -> <empty> |
   *                            <non-empty-method-parameter-list>
   * <non-empty-method-parameter-list> -> <expression> |
   *                                      <expression> , <non-empty-method-parameter-list>
   * }</pre>
   *
   * @param lhs the reference node representing what appears to the left of the dot, like the
   * {@code $x} in {@code $x.foo()}.
   */
  private ReferenceNode parseReferenceMethodParams(ReferenceNode lhs, String id)
      throws IOException {
    assert c == '(';
    nextNonSpace();
    ImmutableList.Builder<ExpressionNode> args = ImmutableList.builder();
    if (c != ')') {
      args.add(parseExpression());
      while (c == ',') {
        nextNonSpace();
        args.add(parseExpression());
      }
      if (c != ')') {
        throw parseError("Expected )");
      }
    }
    assert c == ')';
    next();
    return new MethodReferenceNode(lhs, id, args.build());
  }

  /**
   * Parses an index suffix to a method, like {@code $x[$i]}.
   * <pre>{@code
   * <reference-index> -> [ <expression> ]
   * }</pre>
   *
   * @param lhs the reference node representing what appears to the left of the dot, like the
   * {@code $x} in {@code $x[$i]}.
   */
  private ReferenceNode parseReferenceIndex(ReferenceNode lhs) throws IOException {
    assert c == '[';
    next();
    ExpressionNode index = parseExpression();
    if (c != ']') {
      throw parseError("Expected ]");
    }
    next();
    ReferenceNode reference = new IndexReferenceNode(lhs, index);
    return parseReferenceSuffix(reference);
  }

  /**
   * Parses an expression, which can only occur within a directive like {@code #if} or {@code #set}.
   * <pre>{@code
   * <expression> -> <and-expression> |
   *                 <expression> || <and-expression>
   * }</pre>
   */
  private ExpressionNode parseExpression() throws IOException {
    skipSpace();

    ExpressionNode lhs = parseAndExpression();
    while (c == '|') {
      next();
      if (c != '|') {
        throw parseError("Expected ||, not just |");
      }
      nextNonSpace();
      lhs = new OrExpressionNode(lhs, parseExpression());
    }
    return lhs;
  }

  /**
   * Parses an expression where any operators have at least the precedence of {@code &&}.
   * <pre>{@code
   * <and-expression> -> <relational-expression> |
   *                     <and-expression> && <relational-expression>
   * }</pre>
   */
  private ExpressionNode parseAndExpression() throws IOException {
    ExpressionNode lhs = parseEqualityExpression();
    while (c == '&') {
      next();
      if (c != '&') {
        throw parseError("Expected &&, not just &");
      }
      nextNonSpace();
      lhs = new AndExpressionNode(lhs, parseEqualityExpression());
    }
    return lhs;
  }

  /**
   * Parses an expression where any operators have at least the precedence of {@code ==}.
   * <pre>{@code
   * <equality-exression> -> <relational-expression> |
   *                         <equality-expression> <equality-op> <relational-expression>
   * <equality-op> -> == | !=
   * }</pre>
   */
  private ExpressionNode parseEqualityExpression() throws IOException {
    ExpressionNode lhs = parseRelationalExpression();
    while (true) {
      switch (c) {
        case '=':
          next();
          if (c != '=') {
            throw parseError("Expected ==, not just =");
          }
          nextNonSpace();
          lhs = new EqualsExpressionNode(lhs, parseRelationalExpression());
          break;
        case '!':
          next();
          if (c != '=') {
            throw parseError("Expected !=, not just !");
          }
          nextNonSpace();
          lhs = new NotExpressionNode(new EqualsExpressionNode(lhs, parseAdditiveExpression()));
          break;
        default:
          return lhs;
      }
    }
  }

  /**
   * Parses an expression where any operators have at least the precedence of {@code <}.
   * <pre>{@code
   * <relational-expression> -> <additive-expression> |
   *                            <relational-expression> <relation> <additive-expression>
   * <relation> -> < | <= | > | >=
   * }</pre>
   */
  private ExpressionNode parseRelationalExpression() throws IOException {
    ExpressionNode lhs = parseAdditiveExpression();
    while (true) {
      switch (c) {
        case '<':
          next();
          if (c == '=') {
            nextNonSpace();
            // a <= b  <=>  !(b < a)
            lhs = new NotExpressionNode(new LessExpressionNode(parseAdditiveExpression(), lhs));
          } else {
            skipSpace();
            lhs = new LessExpressionNode(lhs, parseAdditiveExpression());
          }
          break;
        case '>':
          next();
          if (c == '=') {
            nextNonSpace();
            // a >= b  <=>  !(a < b)
            lhs = new NotExpressionNode(new LessExpressionNode(lhs, parseAdditiveExpression()));
          } else {
            skipSpace();
            // a > b  <=>  b < a
            lhs = new LessExpressionNode(parseAdditiveExpression(), lhs);
          }
          break;
        default:
          return lhs;
      }
    }
  }

  /**
   * Parses an expression where any operators have at least the precedence of {@code +}.
   * <pre>{@code
   * <additive-expression> -> <multiplicative-expression> |
   *                          <additive-expression> <add-op> <multiplicative-expression>
   * <add-op> -> + | -
   * }</pre>
   */
  private ExpressionNode parseAdditiveExpression() throws IOException {
    ExpressionNode lhs = parseMultiplicativeExpression();
    while (c == '+' || c == '-') {
      int op = c;
      nextNonSpace();
      ExpressionNode rhs = parseMultiplicativeExpression();
      lhs = new ArithmeticExpressionNode(lineNumber(), op, lhs, rhs);
    }
    return lhs;
  }

  /**
   * Parses an expression where any operators have at least the precedence of {@code *}.
   * <pre>{@code
   * <multiplicative-expression> -> <unary-expression> |
   *                                <multiplicative-expression> <mult-op> <unary-expression>
   * <mult-op> -> * | / | %
   * }</pre>
   */
  private ExpressionNode parseMultiplicativeExpression() throws IOException {
    ExpressionNode lhs = parseUnaryExpression();
    while (c == '*' || c == '/' || c == '%') {
      int op = c;
      nextNonSpace();
      ExpressionNode rhs = parseUnaryExpression();
      lhs = new ArithmeticExpressionNode(lineNumber(), op, lhs, rhs);
    }
    return lhs;
  }

  /**
   * Parses an expression not containing any operators (except inside parentheses).
   * <pre>{@code
   * <unary-expression> -> <primary> |
   *                       ( <expression> ) |
   *                       ! <unary-expression>
   * }</pre>
   */
  private ExpressionNode parseUnaryExpression() throws IOException {
    ExpressionNode node;
    if (c == '(') {
      nextNonSpace();
      node = parseExpression();
      expect(')');
      skipSpace();
      return node;
    } else if (c == '!') {
      nextNonSpace();
      node = new NotExpressionNode(parseUnaryExpression());
      skipSpace();
      return node;
    } else {
      return parsePrimary();
    }
  }

  /**
   * Parses an expression containing only literals or references.
   * <pre>{@code
   * <primary> -> <reference> |
   *              <string-literal> |
   *              <integer-literal> |
   *              true |
   *              false
   * }</pre>
   */
  private ExpressionNode parsePrimary() throws IOException {
    ExpressionNode node;
    if (c == '$') {
      next();
      node = parseReference();
    } else if (c == '"') {
      node = parseStringLiteral();
    } else if (c == '-') {
      // Velocity does not have a negation operator. If we see '-' it must be the start of a
      // negative integer literal.
      next();
      node = parseIntLiteral("-");
    } else if (Character.isDigit(c)) {
      node = parseIntLiteral("");
    } else if (Character.isLetter(c)) {
      node = parseBooleanLiteral();
    } else {
      throw parseError("Expected an expression");
    }
    skipSpace();
    return node;
  }

  private ExpressionNode parseStringLiteral() throws IOException {
    assert c == '"';
    StringBuilder sb = new StringBuilder();
    next();
    while (c != '"') {
      if (c == '\n' || c == EOF) {
        throw parseError("Unterminated string constant");
      }
      if (c == '$' || c == '\\') {
        // In real Velocity, you can have a $ reference expanded inside a "" string literal.
        // There are also '' string literals where that is not so. We haven't needed that yet
        // so it's not supported.
        throw parseError("Escapes or references in string constants are not currently supported");
      }
      sb.appendCodePoint(c);
      next();
    }
    next();
    return new ConstantExpressionNode(lineNumber(), sb.toString());
  }

  private ExpressionNode parseIntLiteral(String prefix) throws IOException {
    StringBuilder sb = new StringBuilder(prefix);
    while (Character.isDigit(c)) {
      sb.appendCodePoint(c);
      next();
    }
    Integer value = Ints.tryParse(sb.toString());
    if (value == null) {
      throw parseError("Invalid integer: " + sb);
    }
    return new ConstantExpressionNode(lineNumber(), value);
  }

  private ExpressionNode parseBooleanLiteral() throws IOException {
    String s = parseId("Identifier without $");
    boolean value;
    if (s.equals("true")) {
      value = true;
    } else if (s.equals("false")) {
      value = false;
    } else {
      throw parseError("Identifier in expression must be preceded by $ or be true or false");
    }
    return new ConstantExpressionNode(lineNumber(), value);
  }

  private String parseId(String what) throws IOException {
    if (!Character.isLetter(c)) {
      throw parseError(what + " should start with a letter");
    }
    StringBuilder id = new StringBuilder();
    while (Character.isLetter(c) || Character.isDigit(c) || c == '-') {
      id.appendCodePoint(c);
      next();
    }
    return id.toString();
  }

  /**
   * Returns an exception to be thrown describing a parse error with the given message, and
   * including information about where it occurred.
   */
  private ParseException parseError(String message) throws IOException {
    StringBuilder context = new StringBuilder();
    if (c == EOF) {
      context.append("EOF");
    } else {
      int count = 0;
      while (c != EOF && count < 20) {
        context.appendCodePoint(c);
        next();
        count++;
      }
      if (c != EOF) {
        context.append("...");
      }
    }
    return new ParseException(message, lineNumber(), context.toString());
  }
}
