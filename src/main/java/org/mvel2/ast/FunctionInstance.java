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
