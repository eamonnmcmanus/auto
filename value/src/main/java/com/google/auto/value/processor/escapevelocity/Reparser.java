package com.google.auto.value.processor.escapevelocity;

import com.google.auto.value.processor.escapevelocity.DirectiveNode.ForEachNode;
import com.google.auto.value.processor.escapevelocity.DirectiveNode.IfNode;
import com.google.auto.value.processor.escapevelocity.DirectiveNode.SetNode;
import com.google.auto.value.processor.escapevelocity.ConstantExpressionNode;
import com.google.auto.value.processor.escapevelocity.Node.EmptyNode;
import com.google.auto.value.processor.escapevelocity.Node.EofNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.ElseIfTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.ElseTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.EndTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.ForEachTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.IfOrElseIfTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.IfTokenNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.MacroDefinitionTokenNode;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

/**
 * @author emcmanus@google.com (Éamonn McManus)
 */
class Reparser {
  private static final ImmutableSet<Class<?>> END_SET =
      ImmutableSet.<Class<?>>of(EndTokenNode.class);
  private static final ImmutableSet<Class<?>> EOF_SET =
      ImmutableSet.<Class<?>>of(EofNode.class);
  private static final ImmutableSet<Class<?>> ELSE_ELSE_IF_END_SET =
      ImmutableSet.<Class<?>>of(ElseTokenNode.class, ElseIfTokenNode.class, EndTokenNode.class);

  private final LinkedList<Node> nodes;
  private final Map<String, Macro> macros;
  private Node currentNode;

  Reparser(LinkedList<Node> nodes) {
    this.nodes = nodes;
    removeSpaceBetweenRefAndSet();
    this.currentNode = this.nodes.remove();
    this.macros = Maps.newTreeMap();
  }

  Template reparse() {
    Node root = parseTo(EOF_SET, null);
    return new Template(root, macros);
  }

  private void removeSpaceBetweenRefAndSet() {
    assert nodes.peekLast() instanceof EofNode;
    for (int i = 0; i < nodes.size(); i++) {
      Node nodeI = nodes.get(i);
      if (nodeI instanceof ReferenceNode) {
        Node next = nodes.get(i + 1);
        if (next instanceof ConstantExpressionNode) {
          Object constant = next.evaluate(null);
          if (constant instanceof String
              && CharMatcher.WHITESPACE.matchesAllOf((String) constant)
              && nodes.get(i + 2) instanceof SetNode) {
            nodes.remove(i + 1);
          }
        }
      }
    }
  }

  private Node parseTo(Set<Class<?>> stopSet, TokenNode forWhat) {
    int startLineNumber = (forWhat == null) ? 1 : forWhat.lineNumber;
    Node node = new EmptyNode(startLineNumber);
    while (!stopSet.contains(currentNode.getClass())) {
      if (currentNode instanceof EofNode) {
        throw new IllegalArgumentException(
            "Reached end of file while parsing " + forWhat.name()
                + " starting on line " + startLineNumber);
      }
      Node parsed;
      if (currentNode instanceof TokenNode) {
        parsed = parseTokenNode();
      } else {
        parsed = currentNode;
        nextNode();
      }
      node = Node.cons(node, parsed);
    }
    return node;
  }

  private Node nextNode() {
    if (currentNode instanceof EofNode) {
      return currentNode;
    } else {
      currentNode = nodes.remove();
      return currentNode;
    }
  }

  private Node parseTokenNode() {
    TokenNode tokenNode = (TokenNode) currentNode;
    nextNode();
    if (tokenNode instanceof IfTokenNode) {
      return parseIfOrElseIf((IfTokenNode) tokenNode);
    } else if (tokenNode instanceof ForEachTokenNode) {
      return parseForEach((ForEachTokenNode) tokenNode);
    } else if (tokenNode instanceof MacroDefinitionTokenNode) {
      return parseMacroDefinition((MacroDefinitionTokenNode) tokenNode);
    } else {
      throw new IllegalArgumentException(
          "Unexpected token: " + tokenNode.name() + " on line " + tokenNode.lineNumber);
    }
  }

  private Node parseForEach(ForEachTokenNode forEach) {
    Node body = parseTo(END_SET, forEach);
    nextNode();  // Skip #end
    return new ForEachNode(forEach.lineNumber, forEach.var, forEach.collection, body);
  }

  private Node parseMacroDefinition(MacroDefinitionTokenNode macroDefinition) {
    Node body = parseTo(END_SET, macroDefinition);
    nextNode();  // Skip #end
    if (!macros.containsKey(macroDefinition.name)) {
      Macro macro = new Macro(
          macroDefinition.lineNumber, macroDefinition.name, macroDefinition.parameterNames, body);
      macros.put(macroDefinition.name, macro);
    }
    return new EmptyNode(macroDefinition.lineNumber);
  }

  private Node parseIfOrElseIf(IfOrElseIfTokenNode ifOrElseIf) {
    Node truePart = parseTo(ELSE_ELSE_IF_END_SET, ifOrElseIf);
    Node falsePart;
    Node token = currentNode;
    nextNode();  // Skip #else or #elseif (cond) or #end.
    if (token instanceof EndTokenNode) {
      falsePart = new EmptyNode(token.lineNumber);
    } else if (token instanceof ElseTokenNode) {
      falsePart = parseTo(END_SET, ifOrElseIf);
      nextNode();  // Skip #end
    } else if (token instanceof ElseIfTokenNode) {
      // We've seen #if (condition1) ... #elseif (condition2). currentToken is the first token
      // after (condition2). We pretend that we've just seen #if (condition2) and parse out a
      // the remainder (which might have further #elseif and final #else). Then we pretend that
      // we actually saw #if (condition1) ... #else #if (condition2) ...remainder ... #end #end.
      falsePart = parseIfOrElseIf((ElseIfTokenNode) token);
    } else {
      throw new AssertionError(currentNode);
    }
    return new IfNode(ifOrElseIf.lineNumber, ifOrElseIf.condition, truePart, falsePart);
  }
}
