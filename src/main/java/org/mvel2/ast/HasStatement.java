/**
 * SPDX-FileCopyrightText: Copyright 2022 ThingsBoard, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.mvel2.ast;

import org.mvel2.compiler.ExecutableStatement;

public interface HasStatement {

    ExecutableStatement getStatement();

}
