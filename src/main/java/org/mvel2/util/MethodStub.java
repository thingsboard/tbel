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

package org.mvel2.util;

import org.mvel2.integration.VariableResolverFactory;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class MethodStub implements StaticStub {
  private Class classReference;
  private String name;

  private transient Method method;

  private boolean sealed;

  public MethodStub(Method method) {
    this.classReference = method.getDeclaringClass();
    this.name = method.getName();
  }

  public MethodStub(Class classReference, String methodName) {
    this.classReference = classReference;
    this.name = methodName;
  }

  public Class getClassReference() {
    return classReference;
  }

  /**
   * Resolves the target method and makes this stub immutable. A stub registered in a configuration that is
   * shared between scripts is sealed, so that it keeps calling the method it was registered for.
   */
  public void seal() {
    if (!sealed) {
      getMethod();
      sealed = true;
    }
  }

  public boolean isSealed() {
    return sealed;
  }

  private void checkMutable() {
    if (sealed) {
      throw new IllegalStateException("Method stub is read-only!");
    }
  }

  public void setClassReference(Class classReference) {
    checkMutable();
    this.classReference = classReference;
  }

  public String getMethodName() {
    return name;
  }

  public void setMethodName(String methodName) {
    checkMutable();
    this.name = methodName;
  }

  public Method getMethod() {
    if (method == null) {
      for (Method method : classReference.getMethods()) {
        if (name.equals(method.getName())) return this.method = method;
      }
    }
    return method;
  }

  public Object call(Object ctx, Object thisCtx, VariableResolverFactory factory, Object[] parameters)
      throws IllegalAccessException, InvocationTargetException {
    return method.invoke(ctx, parameters);
  }
}
