// SPDX-FileCopyrightText: Modifications Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: Apache-2.0
package org.mvel2.ast;

import org.mvel2.ExecutionContext;
import org.mvel2.integration.VariableResolverFactory;

/**
 * @author Mike Brock
 */
public class PrototypalFunctionInstance extends FunctionInstance {
  private final VariableResolverFactory resolverFactory;

  public PrototypalFunctionInstance(Function function, VariableResolverFactory resolverFactory) {
    super(function);
    this.resolverFactory = resolverFactory;
  }

  @Override
  public Object call(Object ctx, ExecutionContext execCtx, Object thisValue, VariableResolverFactory factory, Object[] parms) {
    return function.call(ctx, execCtx, thisValue, new InvokationContextFactory(factory, resolverFactory), parms);
  }

  public VariableResolverFactory getResolverFactory() {
    return resolverFactory;
  }

  public String toString() {
    return "function_prototype:" + function.getName();
  }

}

