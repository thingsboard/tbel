/**
 * MVEL 2.0
 * Copyright (C) 2007 The Codehaus
 * Mike Brock, Dhanji Prasanna, John Graham, Mark Proctor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mvel2.ast;

import org.mvel2.DataTypes;
import org.mvel2.Operator;
import org.mvel2.ParserContext;
import org.mvel2.integration.VariableResolver;
import org.mvel2.integration.VariableResolverFactory;
import org.mvel2.math.MathProcessor;

/**
 * @author Christopher Brock
 */
public class PostFixDecNode extends ASTNode {
  private String name;

  public PostFixDecNode(String name, ParserContext pCtx) {
    super(pCtx);
    this.name = name;
    if (pCtx != null) {
      this.egressType = pCtx.getVarOrInputType(name);
    }
  }

  public Object getReducedValueAccelerated(Object ctx, Object thisValue, VariableResolverFactory factory) {
    VariableResolver vResolver = factory.getVariableResolver(name);
    vResolver.setValue(MathProcessor.doOperations(ctx = vResolver.getValue(), Operator.SUB, DataTypes.INTEGER, 1));
    return ctx;
  }

  public Object getReducedValue(Object ctx, Object thisValue, VariableResolverFactory factory) {
    return getReducedValueAccelerated(ctx, thisValue, factory);
  }

  public String toString() {
    return "<<" + name + "-- " + this.egressType.getName() + ">>";
  }
}
