/**
 * SPDX-FileCopyrightText: Copyright 2022 ThingsBoard, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.mvel2.ast;

import org.mvel2.Operator;
import org.mvel2.ParserContext;
import org.mvel2.compiler.Accessor;
import org.mvel2.integration.VariableResolverFactory;
import org.mvel2.integration.impl.StackDemarcResolverFactory;

import static org.mvel2.MVEL.eval;
import static org.mvel2.util.ParseTools.subCompileExpression;

/**
 * @author Nick
 */
public class BreakNode extends ASTNode {

  public BreakNode(char[] expr, int start, int offset, int fields, ParserContext pCtx) {
    super(pCtx);
    this.expr = expr;
    this.start = start;
    this.offset = offset;
    if ((fields & COMPILE_IMMEDIATE) != 0) {
      setAccessor((Accessor) subCompileExpression(expr, start, offset, pCtx));
    }
  }

  public Object getReducedValueAccelerated(Object ctx, Object thisValue, VariableResolverFactory factory) {
    if (accessor == null) {
      setAccessor((Accessor) subCompileExpression(expr, start, offset, pCtx));
    }

    factory.setBreakFlag(true);

    return accessor.getValue(ctx, thisValue, new StackDemarcResolverFactory(factory));
  }

  public Object getReducedValue(Object ctx, Object thisValue, VariableResolverFactory factory) {
    factory.setBreakFlag(true);
    return eval(expr, start, offset, ctx, new StackDemarcResolverFactory(factory));
  }

  @Override
  public boolean isOperator() {
    return true;
  }

  @Override
  public Integer getOperator() {
    return Operator.BREAK;
  }

  @Override
  public boolean isOperator(Integer operator) {
    return Operator.BREAK == operator;
  }
}
