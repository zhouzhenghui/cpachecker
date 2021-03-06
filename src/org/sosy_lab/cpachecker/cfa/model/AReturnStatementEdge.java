/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.cfa.model;

import org.sosy_lab.cpachecker.cfa.ast.FileLocation;
import com.google.common.base.Optional;
import org.sosy_lab.cpachecker.cfa.ast.AExpression;
import org.sosy_lab.cpachecker.cfa.ast.AReturnStatement;
import org.sosy_lab.cpachecker.cfa.ast.AAssignment;



public class AReturnStatementEdge extends AbstractCFAEdge {

  private static final long serialVersionUID = -6181479727890105919L;
  protected final AReturnStatement rawAST;

  protected AReturnStatementEdge(String pRawStatement, AReturnStatement pRawAST,
      FileLocation pFileLocation, CFANode pPredecessor, FunctionExitNode pSuccessor) {

    super(pRawStatement, pFileLocation, pPredecessor, pSuccessor);
    rawAST = pRawAST;
  }

  @Override
  public CFAEdgeType getEdgeType() {
    return CFAEdgeType.ReturnStatementEdge;
  }

  public Optional<? extends AExpression> getExpression() {
    return rawAST.getReturnValue();
  }

  /**
   * @see AReturnStatement#asAssignment()
   */
  public Optional<? extends AAssignment> asAssignment() {
    return rawAST.asAssignment();
  }

  @Override
  public Optional<? extends AReturnStatement> getRawAST() {
    return Optional.of(rawAST);
  }

  @Override
  public String getCode() {
    return rawAST.toASTString();
  }

  @Override
  public FunctionExitNode getSuccessor() {
    // the constructor enforces that the successor is always a FunctionExitNode
    return (FunctionExitNode)super.getSuccessor();
  }

}
