/**
 * MVEL (The MVFLEX Expression Language)
 *
 * Copyright (C) 2007 Christopher Brock, MVFLEX/Valhalla Project and the Codehaus
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
 *
 */
/*
 * SPDX-FileCopyrightText: Modifications Copyright (C) 2022-present ThingsBoard, Inc.
 * This file has been modified from the original MVEL source.
 * See the project's Git history for details of the changes.
 */
package org.mvel2.optimizers.impl.refl.collection;

import org.mvel2.ExecutionContext;
import org.mvel2.compiler.Accessor;
import org.mvel2.execution.ExecutionArrayList;
import org.mvel2.integration.VariableResolverFactory;

import java.lang.reflect.Array;
import java.util.Arrays;

import static java.lang.reflect.Array.newInstance;

/**
 * @author Christopher Brock
 */
public class ArrayCreator implements Accessor {
  public Accessor[] template;
  private Class arrayType;

  public Object getValue(Object ctx, Object elCtx, VariableResolverFactory variableFactory) {
    Object res;
    if (Object.class.equals(arrayType)) {
      Object[] newArray = new Object[template.length];

      for (int i = 0; i < newArray.length; i++) {
        newArray[i] = template[i].getValue(ctx, elCtx, variableFactory);
      }

      res = newArray;
    }
    else {
      Object newArray = newInstance(arrayType, template.length);
      for (int i = 0; i < template.length; i++) {
        Array.set(newArray, i, template[i].getValue(ctx, elCtx, variableFactory));
      }

      res = newArray;
    }
    if (ctx instanceof ExecutionContext && !res.getClass().getComponentType().isPrimitive()) {
      return new ExecutionArrayList<>(Arrays.asList((Object[])res), (ExecutionContext) ctx);
    } else {
      return res;
    }
  }

  public Accessor[] getTemplate() {
    return template;
  }

  public ArrayCreator(Accessor[] template, Class arrayType) {
    this.template = template;
    this.arrayType = arrayType;
  }

  public Object setValue(Object ctx, Object elCtx, VariableResolverFactory variableFactory, Object value) {
    return null;
  }

  public Class getKnownEgressType() {
    return arrayType;
  }
}
