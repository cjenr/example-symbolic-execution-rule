/*
 * SonarQube Java
 * Copyright (C) 2012-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.notsonar.samples.java.se.checks;

import org.sonar.check.Rule;
import org.sonar.java.se.CheckerContext;
import org.sonar.java.se.ExplodedGraph;
import org.sonar.java.se.Flow;
import org.sonar.java.se.FlowComputation;
import org.sonar.java.se.ProgramState;
import org.sonar.java.se.SymbolicValueFactory;
import org.sonar.java.se.checks.CheckerTreeNodeVisitor;
import org.sonar.java.se.checks.SECheck;
import org.sonar.java.se.constraint.BooleanConstraint;
import org.sonar.java.se.constraint.Constraint;
import org.sonar.java.se.constraint.ConstraintManager;
import org.sonar.java.se.symbolicvalues.SymbolicValue;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;

import java.util.Collections;
import java.util.List;

@Rule(key = "S2222")
public class LocksNotUnlockedCheck extends SECheck {

  private static final List<Class<? extends Constraint>> LOCK_CONSTRAINT_DOMAIN = Collections.singletonList(LockConstraint.class);

  public enum LockConstraint implements Constraint {
    LOCKED, UNLOCKED;

    @Override
    public String valueAsString() {
      if(this == LOCKED) {
        return "locked";
      }
      return "unlocked";
    }
  }

  private static final String LOCK = "java.util.concurrent.locks.Lock";
  private static final String LOCK_METHOD_NAME = "lock";
  private static final String TRY_LOCK_METHOD_NAME = "tryLock";
  private static final String UNLOCK_METHOD_NAME = "unlock";

  private static class PostStatementVisitor extends CheckerTreeNodeVisitor {

    public ProgramState getProgramState() {
      return super.programState;
    }

    public PostStatementVisitor(CheckerContext context) {
      super(context.getState());
    }

    @Override
    public void visitMethodInvocation(MethodInvocationTree syntaxNode) {
      if (syntaxNode.methodSelect().is(Tree.Kind.MEMBER_SELECT)) {
        MemberSelectExpressionTree memberSelect = (MemberSelectExpressionTree) syntaxNode.methodSelect();
        final ExpressionTree expression = memberSelect.expression();
        if (expression.is(Tree.Kind.IDENTIFIER) && expression.symbolType().isSubtypeOf(LOCK)) {
          final String methodName = memberSelect.identifier().name();
          visitMethodInvocationWithIdentifierTarget(methodName, (IdentifierTree) expression);
        }
      }
    }

    private void visitMethodInvocationWithIdentifierTarget(final String methodName, final IdentifierTree target) {
      if (!isMemberSelectActingOnField(target)) {
        final SymbolicValue symbolicValue = programState.getValue(target.symbol());
        if (LOCK_METHOD_NAME.equals(methodName) || TRY_LOCK_METHOD_NAME.equals(methodName)) {
          programState = programState.addConstraintTransitively(symbolicValue, LockConstraint.LOCKED);
        } else if (UNLOCK_METHOD_NAME.equals(methodName)) {
          programState = programState.addConstraintTransitively(symbolicValue, LockConstraint.UNLOCKED);
        }
      }
    }
  }

  private static boolean isMemberSelectActingOnField(IdentifierTree expression) {
    return ProgramState.isField(expression.symbol());
  }

  @Override
  public ProgramState checkPostStatement(CheckerContext context, Tree syntaxNode) {
    final PostStatementVisitor visitor = new PostStatementVisitor(context);
    syntaxNode.accept(visitor);
    return visitor.getProgramState();
  }

  @Override
  public void checkEndOfExecutionPath(CheckerContext context, ConstraintManager constraintManager) {
    if (context.getState().exitingOnRuntimeException()) {
      return;
    }
    ExplodedGraph.Node node = context.getNode();
    context.getState().getValuesWithConstraints(LockConstraint.LOCKED).stream()
      .flatMap(lockedSv -> FlowComputation.flowWithoutExceptions(node, lockedSv, LockConstraint.LOCKED::equals,
        LockConstraint.UNLOCKED::equals, LOCK_CONSTRAINT_DOMAIN, FlowComputation.MAX_LOOKUP_FLOWS).stream())
      .flatMap(Flow::firstFlowLocation)
      .forEach(this::reportIssue);
  }

  private void reportIssue(JavaFileScannerContext.Location location) {
    if (location.syntaxNode.is(Tree.Kind.METHOD_INVOCATION)) {
      MethodInvocationTree syntaxNode = (MethodInvocationTree) location.syntaxNode;
      Tree tree = issueTree(syntaxNode);
      reportIssue(tree, "Unlock this lock along all executions paths of this method.");
    }
  }

  private static Tree issueTree(MethodInvocationTree syntaxNode) {
    ExpressionTree methodSelect = syntaxNode.methodSelect();
    if (methodSelect.is(Tree.Kind.MEMBER_SELECT)) {
      return ((MemberSelectExpressionTree) methodSelect).expression();
    }
    return syntaxNode;
  }
}
