package com.google.auto.value.processor.escapevelocity;

import com.google.auto.value.processor.escapevelocity.DirectiveNode.SetNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.AndExpressionNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.ArithmeticExpressionNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.ConstantExpressionNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.EqualsExpressionNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.LessExpressionNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.NotExpressionNode;
import com.google.auto.value.processor.escapevelocity.ExpressionNode.OrExpressionNode;
import com.google.auto.value.processor.escapevelocity.Node.EofNode;
import com.google.auto.value.processor.escapevelocity.ReferenceNode.IndexReferenceNode;
import com.google.auto.value.processor.escapevelocity.ReferenceNode.MemberReferenceNode;
import com.google.auto.value.processor.escapevelocity.ReferenceNode.PlainReferenceNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.ElseIfTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.ElseTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.EndTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.ForEachTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.IfTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.MacroDefinitionTokenNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Queues;
import com.google.common.primitives.Ints;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.Reader;
import java.util.Queue;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
class Parser {
  private static final int EOF = -1;

  private final LineNumberReader reader;
  private int c;

  Parser(Reader reader) throws IOException {
    this.reader = new LineNumberReader(reader);
    this.reader.setLineNumber(1);
    next();
  }

  Template parse() throws IOException {
    Queue<Node> tokens = Queues.newArrayDeque();
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

  private int next() throws IOException {
    if (c != EOF) {
      c = reader.read();
    }
    return c;
  }

  private int skipSpace() throws IOException {
    while (Character.isWhitespace(c)) {
      next();
    }
    return c;
  }

  private int nextNonSpace() throws IOException {
    next();
    return skipSpace();
  }

  private void expect(char expected) throws IOException {
    skipSpace();
    if (c == expected) {
      next();
    } else {
      throw parseError("Expected " + expected);
    }
  }

  private Node parseNode() throws IOException {
    while (c == '#') {
      next();
      if (c == '#') {
        skipComment();
      } else {
        return parseDirective();
      }
    }
    if (c == EOF) {
      return new EofNode(lineNumber());
    }
    return parseNonDirective();
  }

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

  private Node parseDirective() throws IOException {
    String directive = parseId("Directive");
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

  private Node parseSet() throws IOException {
    expect('(');
    expect('$');
    String var = parseId("#set variable");
    expect('=');
    ExpressionNode expression = parseExpression();
    expect(')');
    return new SetNode(var, expression);
  }

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
      parameterNodes.add(parseExpression());
    }
    next();  // Skip )
    return new DirectiveNode.MacroCallNode(lineNumber(), directive, parameterNodes.build());
  }

  private void skipComment() throws IOException {
    while (c != '\n' && c != EOF) {
      next();
    }
    next();
  }

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

  private ReferenceNode parseReferenceNoBrace() throws IOException {
    String id = parseId("Reference");
    ReferenceNode lhs = new PlainReferenceNode(lineNumber(), id);
    return parseReferenceSuffix(lhs);
  }

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

  private ReferenceNode parseReferenceMember(ReferenceNode lhs) throws IOException {
    assert c == '.';
    next();
    String id = parseId("Member");
    if (c == '(') {
      return parseReferenceMethod(lhs, id);
    } else {
      ReferenceNode reference = new MemberReferenceNode(lhs, id);
      return parseReferenceSuffix(reference);
    }
  }

  private ReferenceNode parseReferenceMethod(ReferenceNode lhs, String id) throws IOException {
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
    ReferenceNode reference = new MethodReferenceNode(lhs, id, args.build());
    return parseReferenceSuffix(reference);
  }

  private ReferenceNode parseReferenceIndex(ReferenceNode lhs) throws IOException {
    assert c == '[';
    next();
    ExpressionNode index = parseExpression();
    if (c != ']') {
      throw parseError("Expected ]");
    }
    next();
    return new IndexReferenceNode(lhs, index);
  }

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

  private ExpressionNode parseAndExpression() throws IOException {
    ExpressionNode lhs = parseRelationalExpression();
    while (c == '&') {
      next();
      if (c != '&') {
        throw parseError("Expected &&, not just &");
      }
      nextNonSpace();
      lhs = new AndExpressionNode(lhs, parseRelationalExpression());
    }
    return lhs;
  }

  private ExpressionNode parseRelationalExpression() throws IOException {
    ExpressionNode lhs = parseAdditiveExpression();
    switch (c) {
      case '=':
        next();
        if (c != '=') {
          throw parseError("Expected ==, not just =");
        }
        nextNonSpace();
        return new EqualsExpressionNode(lhs, parseAdditiveExpression());
      case '!':
        next();
        if (c != '=') {
          throw parseError("Expected !=, not just !");
        }
        nextNonSpace();
        return new NotExpressionNode(new EqualsExpressionNode(lhs, parseAdditiveExpression()));
      case '<':
        next();
        if (c == '=') {
          nextNonSpace();
          // a <= b  <=>  !(b < a)
          return new NotExpressionNode(new LessExpressionNode(parseAdditiveExpression(), lhs));
        } else {
          skipSpace();
          return new LessExpressionNode(lhs, parseAdditiveExpression());
        }
      case '>':
        next();
        if (c == '=') {
          nextNonSpace();
          // a >= b  <=>  !(a < b)
          return new NotExpressionNode(new LessExpressionNode(lhs, parseAdditiveExpression()));
        } else {
          skipSpace();
          // a > b  <=>  b < a
          return new LessExpressionNode(parseAdditiveExpression(), lhs);
        }
      default:
        return lhs;
    }
  }

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

  private ExpressionNode parseUnaryExpression() throws IOException {
    ExpressionNode node;
    if (c == '(') {
      nextNonSpace();
      node = parseExpression();
      expect(')');
    } else if (c == '!') {
      nextNonSpace();
      node = new NotExpressionNode(parseUnaryExpression());
    } else if (c == '$') {
      next();
      node = parseReference();
    } else if (c == '"') {
      node = parseStringLiteral();
    } else if (c == '-') {
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
