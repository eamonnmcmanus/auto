package com.google.auto.value.processor.escapevelocity;

import com.google.auto.value.processor.escapevelocity.DirectiveNode.ForEachNode;
import com.google.auto.value.processor.escapevelocity.DirectiveNode.IfNode;
import com.google.auto.value.processor.escapevelocity.DirectiveNode.SetNode;
import com.google.auto.value.processor.escapevelocity.Node.EofNode;
import com.google.auto.value.processor.escapevelocity.TokenNode.CommentTokenNode;
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

import static com.google.auto.value.processor.escapevelocity.Node.emptyNode;

/**
 * The second phase of parsing. See {@link Parser#parse()} for a description of the phases and why
 * we need them.
 *
 * @author emcmanus@google.com (Éamonn McManus)
 */
class Reparser {
  private static final ImmutableSet<Class<?>> END_SET =
      ImmutableSet.<Class<?>>of(EndTokenNode.class);
  private static final ImmutableSet<Class<?>> EOF_SET =
      ImmutableSet.<Class<?>>of(EofNode.class);
  private static final ImmutableSet<Class<?>> ELSE_ELSE_IF_END_SET =
      ImmutableSet.<Class<?>>of(ElseTokenNode.class, ElseIfTokenNode.class, EndTokenNode.class);

  /**
   * The nodes that make up the input sequence. Nodes are removed one by one from this list as
   * parsing proceeds. At any time, {@link #currentNode} is the node being examined.
   */
  private final LinkedList<Node> nodes;
  private Node currentNode;

  /**
   * Macros are removed from the input as they are found. They do not appear in the output parse
   * tree. Macro definitions are not executed in place but are all applied before template rendering
   * starts. This means that a macro can be referenced before it is defined.
   */
  private final Map<String, Macro> macros;

  Reparser(LinkedList<Node> nodes) {
    this.nodes = nodes;
    removeSpaceBeforeSet();
    this.currentNode = this.nodes.remove();
    this.macros = Maps.newTreeMap();
  }

  Template reparse() {
    Node root = parseTo(EOF_SET, null);
    return new Template(root, macros);
  }

  /**
   * Remove spaces between certain tokens and {@code #set}. This hack is needed to match what
   * appears to be special treatment of spaces before {@code #set} directives. If you have
   * <i>thing</i> <i>whitespace</i> {@code #set}, then the whitespace is deleted if the <i>thing</i>
   * is a comment ({@code ##...\n}); a reference ({@code $x} or {@code $x.foo} etc); a macro
   * definition; or another {@code #set}.
   */
  private void removeSpaceBeforeSet() {
    assert nodes.peekLast() instanceof EofNode;
    for (int i = 0; i < nodes.size(); i++) {
      Node nodeI = nodes.get(i);
      if (nodeI instanceof CommentTokenNode
          || nodeI instanceof ReferenceNode
          || nodeI instanceof MacroDefinitionTokenNode
          || nodeI instanceof SetNode) {
        Node next = nodes.get(i + 1);
        if (next instanceof ConstantExpressionNode) {
          Object constant = next.evaluate(null);
          if (constant instanceof String
              && CharMatcher.WHITESPACE.matchesAllOf((String) constant)
              && nodes.get(i + 2) instanceof SetNode) {
            // The node at i is one of the trigger nodes listed above; the node at i + 1 consists
            // of whitespace only; and the node at i + 2 is a #set. Delete the i + 1 node. That
            // means that the next time through the loop we will examine the #set.
            nodes.remove(i + 1);
          }
        }
      }
    }
  }

  /**
   * Cons together parsed subtrees until one of the token types in {@code stopSet} is encountered.
   * If this is the top level, {@code stopSet} will include {@link EofNode} so parsing will stop
   * when it reaches the end of the input. Otherwise, if an {@code EofNode} is encountered it is an
   * error because we have something like {@code #if} without {@code #end}.
   */
  private Node parseTo(Set<Class<?>> stopSet, TokenNode forWhat) {
    int startLineNumber = (forWhat == null) ? 1 : forWhat.lineNumber;
    Node node = emptyNode(startLineNumber);
    while (!stopSet.contains(currentNode.getClass())) {
      if (currentNode instanceof EofNode) {
        throw new ParseException(
            "Reached end of file while parsing " + forWhat.name(), startLineNumber);
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
    if (tokenNode instanceof CommentTokenNode) {
      return emptyNode(tokenNode.lineNumber);
    } else if (tokenNode instanceof IfTokenNode) {
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
    return emptyNode(macroDefinition.lineNumber);
  }

  private Node parseIfOrElseIf(IfOrElseIfTokenNode ifOrElseIf) {
    Node truePart = parseTo(ELSE_ELSE_IF_END_SET, ifOrElseIf);
    Node falsePart;
    Node token = currentNode;
    nextNode();  // Skip #else or #elseif (cond) or #end.
    if (token instanceof EndTokenNode) {
      falsePart = emptyNode(token.lineNumber);
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
