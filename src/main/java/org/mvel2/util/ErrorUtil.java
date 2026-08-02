/*
 * SPDX-FileCopyrightText: Modifications Copyright (C) 2022-present ThingsBoard, Inc.
 * This file has been modified from the original MVEL source.
 * See the project's Git history for details of the changes.
 */
package org.mvel2.util;

import org.mvel2.CompileException;
import org.mvel2.ErrorDetail;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Mike Brock .
 */
public class ErrorUtil {
  private static final Logger LOG = Logger.getLogger(ErrorUtil.class.getName());

  public static CompileException rewriteIfNeeded(CompileException caught, char[] outer, int outerCursor) {
    if (outer != null && outer != caught.getExpr()) {
      char[] inner = caught.getExpr() != null ? caught.getExpr() : new char[0];
      if (inner.length > 0 && inner.length <= caught.getCursor()) {
        caught.setCursor(inner.length - 1);
      }

      try {
      String innerExpr = new String(inner).substring(caught.getCursor());
      caught.setExpr(outer);

      String outerStr = new String(outer);

      int newCursor;
      if (innerExpr.length() > 0) {
        newCursor = outerStr.substring(outerStr.indexOf(new String(caught.getExpr())))
                .indexOf(innerExpr);
      } else {
        newCursor = outerCursor;
      }
      if (newCursor == -1) {
        newCursor = outerCursor;
      }

      caught.setCursor(newCursor);
      }
      catch (Throwable t) {
        LOG.log(Level.WARNING, "", t);
      }
    }
    return caught;
  }

  public static ErrorDetail rewriteIfNeeded(ErrorDetail detail, char[] outer, int outerCursor) {
    if (outer != detail.getExpr()) {
      String innerExpr = new String(detail.getExpr()).substring(detail.getCursor());
      detail.setExpr(outer);

      int newCursor = outerCursor;
      newCursor += new String(outer).substring(outerCursor).indexOf(innerExpr);

      detail.setCursor(newCursor);
    }
    return detail;
  }
}
