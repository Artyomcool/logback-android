/**
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2009, QOS.ch. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package ch.qos.logback.core.pattern.parser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.core.pattern.Converter;
import ch.qos.logback.core.pattern.FormatInfo;
import ch.qos.logback.core.pattern.IdentityCompositeConverter;
import ch.qos.logback.core.pattern.ReplacingCompositeConverter;
import ch.qos.logback.core.pattern.util.IEscapeUtil;
import ch.qos.logback.core.pattern.util.RegularEscapeUtil;
import ch.qos.logback.core.spi.ContextAwareBase;

// ~=lamda
// E = TE|T

// Left factorization
// E = T(E|~)
// Eopt = E|~
// replace E|~ with Eopt in E
// E = TEopt

// T = LITERAL | '%' C | '%' FORMAT_MODIFIER C
// C = SIMPLE_KEYWORD OPTION | COMPOSITE_KEYWORD COMPOSITE
// OPTION = {...} | ~
// COMPOSITE = E ')' OPTION



public class Parser<E> extends ContextAwareBase {

  public final static Map<String, String> DEFAULT_COMPOSITE_CONVERTER_MAP = new HashMap<String, String>();
  public final static String REPLACER_CONVERTER_WORD = "replace";
  static {
    DEFAULT_COMPOSITE_CONVERTER_MAP.put(Token.BARE_COMPOSITE_KEYWORD_TOKEN.getValue().toString(),
            IdentityCompositeConverter.class.getName());
    DEFAULT_COMPOSITE_CONVERTER_MAP.put(REPLACER_CONVERTER_WORD,
             ReplacingCompositeConverter.class.getName());

  }

  final List tokenList;
  int pointer = 0;
  
  Parser(TokenStream ts) throws ScanException {
    this.tokenList = ts.tokenize();
  }

  public Parser(String pattern) throws ScanException {
    this(pattern, new RegularEscapeUtil());
  }
  
  public Parser(String pattern, IEscapeUtil escapeUtil) throws ScanException {
    try {
      TokenStream ts = new TokenStream(pattern, escapeUtil);
      this.tokenList = ts.tokenize();
    } catch (IllegalArgumentException npe) {
      throw new ScanException("Failed to initialize Parser", npe);
    }
  }

  /**
   * When the parsing step is done, the Node list can be transformed into a
   * converter chain.
   *
   * @param top
   * @param converterMap
   * @return
   * @throws ScanException
   */
  public Converter<E> compile(final Node top, Map converterMap, Map compositeConverterMap) {
    Compiler<E> compiler = new Compiler<E>(top, converterMap, compositeConverterMap);
    compiler.setContext(context);
    //compiler.setStatusManager(statusManager);
    return compiler.compile();
  }

  public Node parse() throws ScanException {
    return E();
  }

  // E = TEopt
  Node E() throws ScanException {
    Node t = T();
    if (t == null) {
      return null;
    }
    Node eOpt = Eopt();
    if (eOpt != null) {
      t.setNext(eOpt);
    }
    return t;
  }

  // Eopt = E|~
  Node Eopt() throws ScanException {
    // System.out.println("in Eopt()");
    Token next = getCurentToken();
    // System.out.println("Current token is " + next);
    if (next == null) {
      return null;
    } else {
      return E();
    }
  }

  Node T() throws ScanException {
    // System.out.println("in T()");
    Token t = getCurentToken();
    if (t == null) {
      throw new IllegalStateException("a LITERAL or '%'");
    }

    // System.out.println("Current token is " + t);

    switch (t.getType()) {
    case Token.LITERAL:
      advanceTokenPointer();
      return new Node(Node.LITERAL, t.getValue());
    case Token.PERCENT:
      advanceTokenPointer();
      // System.out.println("% token found");
      FormatInfo fi;
      Token u = getCurentToken();
      FormattingNode c;
      expectNotNull(u, "a FORMAT_MODIFIER, KEYWORD or LEFT_PARENTHESIS");
      if (u.getType() == Token.FORMAT_MODIFIER) {
        fi = FormatInfo.valueOf((String) u.getValue());
        advanceTokenPointer();
        c = C();
        c.setFormatInfo(fi);
      } else {
        c = C();
      }
      return c;

    default:
      return null;

    }

  }

  FormattingNode C() throws ScanException {
    Token t = getCurentToken();
    // System.out.println("in C()");
    // System.out.println("Current token is " + t);
    expectNotNull(t, "a LEFT_PARENTHESIS or KEYWORD");
    int type = t.getType();
    switch (type) {
    case Token.SIMPLE_KEYWORD:
      return SINGLE();
    case Token.COMPOSITE_KEYWORD:
      advanceTokenPointer();
      return COMPOSITE(t.getValue().toString());
    default:
      throw new IllegalStateException("Unexpected token " + t);
    }
  }

  FormattingNode SINGLE() throws ScanException {
    // System.out.println("in SINGLE()");
    Token t = getNextToken();
    // System.out.println("==" + t);
    SimpleKeywordNode keywordNode = new SimpleKeywordNode(t.getValue());

    Token ot = getCurentToken();
    if (ot != null && ot.getType() == Token.OPTION) {
      List<String> optionList = new OptionTokenizer((String) ot.getValue()).tokenize();
      keywordNode.setOptions(optionList);
      advanceTokenPointer();
    }
    return keywordNode;
  }

  FormattingNode COMPOSITE(String keyword) throws ScanException {
    CompositeNode compositeNode = new CompositeNode(keyword);

    Node childNode = E();
    // System.out.println("Child node: " + childNode);

    compositeNode.setChildNode(childNode);

    Token t = getNextToken();
    // System.out.println("Next token is" + t);

    if (t.getType() != Token.RIGHT_PARENTHESIS) {
      throw new IllegalStateException(
          "Expecting RIGHT_PARENTHESIS token but got " + t);
    } else {
      // System.out.println("got expected ')'");
    }
    Token ot = getCurentToken();
    if (ot != null && ot.getType() == Token.OPTION) {
      List<String> optionList = new OptionTokenizer((String) ot.getValue()).tokenize();
      compositeNode.setOptions(optionList);
      advanceTokenPointer();
    }
    return compositeNode;
  }

  Token getNextToken() {
    if (pointer < tokenList.size()) {
      return (Token) tokenList.get(pointer++);
    }
    return null;
  }

  Token getCurentToken() {
    if (pointer < tokenList.size()) {
      return (Token) tokenList.get(pointer);
    }
    return null;
  }

  void advanceTokenPointer() {
    pointer++;
  }

  void expectNotNull(Token t, String expected) {
    if (t == null) {
      throw new IllegalStateException("All tokens consumed but was expecting "
          + expected);
    }
  }

//  public void setStatusManager(StatusManager statusManager) {
//    this.statusManager = statusManager;
//  }
}