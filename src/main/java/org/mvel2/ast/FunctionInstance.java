// SPDX-FileCopyrightText: Modifications Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2.ast;

import org.mvel2.ExecutionContext;
import org.mvel2.integration.VariableResolverFactory;

/**
 * @author Mike Brock
 */
public class FunctionInstance {
  protected final Function function;

  public FunctionInstance(Function function) {
    this.function = function;
  }

  public Function getFunction() {
    return function;
  }

  public Object call(Object ctx, Object thisValue, VariableResolverFactory factory, Object[] parms) {
    return this.call(ctx, null, thisValue, factory, parms);
  }

  public Object call(Object ctx, ExecutionContext execCtx, Object thisValue, VariableResolverFactory factory, Object[] parms) {
    return function.call(ctx, execCtx, thisValue, factory, parms);
  }
}
