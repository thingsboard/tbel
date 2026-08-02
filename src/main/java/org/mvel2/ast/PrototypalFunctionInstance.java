/*
 * SPDX-FileCopyrightText: Modifications Copyright (C) 2022-present ThingsBoard, Inc.
 * This file has been modified from the original MVEL source.
 * See the project's Git history for details of the changes.
 */
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

