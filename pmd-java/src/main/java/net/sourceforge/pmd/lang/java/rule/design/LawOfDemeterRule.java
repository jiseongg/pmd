/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.rule.design;

import static net.sourceforge.pmd.lang.java.rule.internal.JavaRuleUtil.isArrayLengthFieldAccess;
import static net.sourceforge.pmd.lang.java.rule.internal.JavaRuleUtil.isCallOnThisInstance;
import static net.sourceforge.pmd.lang.java.rule.internal.JavaRuleUtil.isGetterCall;
import static net.sourceforge.pmd.lang.java.rule.internal.JavaRuleUtil.isRefToFieldOfThisInstance;
import static net.sourceforge.pmd.properties.constraints.NumericConstraints.positive;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import net.sourceforge.pmd.RuleContext;
import net.sourceforge.pmd.lang.java.ast.ASTArrayAccess;
import net.sourceforge.pmd.lang.java.ast.ASTAssignableExpr.ASTNamedReferenceExpr;
import net.sourceforge.pmd.lang.java.ast.ASTExpression;
import net.sourceforge.pmd.lang.java.ast.ASTExpressionStatement;
import net.sourceforge.pmd.lang.java.ast.ASTFieldAccess;
import net.sourceforge.pmd.lang.java.ast.ASTForeachStatement;
import net.sourceforge.pmd.lang.java.ast.ASTMethodCall;
import net.sourceforge.pmd.lang.java.ast.ASTVariableAccess;
import net.sourceforge.pmd.lang.java.ast.internal.PrettyPrintingUtil;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRulechainRule;
import net.sourceforge.pmd.lang.java.rule.internal.DataflowPass;
import net.sourceforge.pmd.lang.java.rule.internal.DataflowPass.AssignmentEntry;
import net.sourceforge.pmd.lang.java.rule.internal.DataflowPass.DataflowResult;
import net.sourceforge.pmd.lang.java.rule.internal.DataflowPass.ReachingDefinitionSet;
import net.sourceforge.pmd.lang.java.rule.internal.JavaRuleUtil;
import net.sourceforge.pmd.lang.java.types.InvocationMatcher;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.TypeTestUtil;
import net.sourceforge.pmd.properties.PropertyDescriptor;
import net.sourceforge.pmd.properties.PropertyFactory;

/**
 * This rule can detect possible violations of the Law of Demeter. The Law of
 * Demeter is a simple rule, that says "only talk to friends". It helps to
 * reduce coupling between classes or objects.
 * <p>
 * See:
 * <ul>
 * <li>Andrew Hunt, David Thomas, and Ward Cunningham. The Pragmatic Programmer.
 * From Journeyman to Master. Addison-Wesley Longman, Amsterdam, October
 * 1999.</li>
 * <li>K.J. Lieberherr and I.M. Holland. Assuring good style for object-oriented
 * programs. Software, IEEE, 6(5):38–48, 1989.</li>
 * </ul>
 *
 * @since 5.0
 *
 */
public class LawOfDemeterRule extends AbstractJavaRulechainRule {

    private static final InvocationMatcher ITERATOR_NEXT = InvocationMatcher.parse("java.util.Iterator#next()");

    private static final PropertyDescriptor<Integer> TRUST_RADIUS =
        PropertyFactory.intProperty("trustRadius")
                       .desc("TODO")
                       .require(positive())
                       .defaultValue(1)
                       .build();

    public LawOfDemeterRule() {
        super(ASTMethodCall.class, ASTFieldAccess.class);
        definePropertyDescriptor(TRUST_RADIUS);
    }

    /**
     * This cache is there to prevent recursion in case of cycles. It
     * also avoids recomputing the degree of too many nodes, as the degree
     * of a call chain depends on the degree of the qualifier. {@link #visit(ASTMethodCall, Object)}
     * is called on every part of the chain, so without memoization we
     * would run in O(n2).
     */
    private final Map<ASTExpression, Integer> degreeCache = new LinkedHashMap<>();

    @Override
    public void end(RuleContext ctx) {
        degreeCache.clear(); // avoid memory leak
    }

    @Override
    public Object visit(ASTFieldAccess node, Object data) {
        int degree = foreignDegree(node);
        if (isReportedDegree(degree)) {
            addViolationWithMessage(
                data, node,
                "Access to field `{0}` on foreign value `{1}` (degree {2})",
                new Object[] {
                    node.getName(),
                    PrettyPrintingUtil.prettyPrint(node.getQualifier()),
                    degree,
                }
            );
        }
        return null;
    }

    /**
     * Only report the first occurrences of a breach of trust. Those are
     * the ones that need to be fixed.
     */
    private boolean isReportedDegree(int degree) {
        return degree == getProperty(TRUST_RADIUS) + 1;
    }

    @Override
    public Object visit(ASTMethodCall node, Object data) {
        ASTExpression qualifier = node.getQualifier();
        if (qualifier != null) {
            int degree = foreignDegree(node);
            if (isReportedDegree(degree)) {
                addViolationWithMessage(
                    data, node,
                    "Call to `{0}` on foreign value `{1}` (degree {2})",
                    new Object[] {
                        node.getMethodName(),
                        PrettyPrintingUtil.prettyPrint(node.getQualifier()),
                        degree,
                    });
            }
        }
        return null;
    }

    private boolean isBuilderPattern(ASTExpression qualifier) {
        return typeEndsWith(qualifier, "Builder");
    }

    private boolean isFactoryMethod(ASTMethodCall expr) {
        ASTExpression qualifier = expr.getQualifier();
        if (qualifier != null) { // NOPMD SimplifyBooleanReturns https://github.com/pmd/pmd/issues/3786
            return typeEndsWith(qualifier, "Factory")
                || nameEndsWith(qualifier, "Factory")
                || nameIs(qualifier, "factory");
        }
        return false;
    }

    private boolean nameEndsWith(ASTExpression expr, String suffix) {
        return expr instanceof ASTNamedReferenceExpr
            && ((ASTNamedReferenceExpr) expr).getName().endsWith(suffix);
    }

    private boolean nameIs(ASTExpression expr, String name) {
        return expr instanceof ASTNamedReferenceExpr
            && ((ASTNamedReferenceExpr) expr).getName().equals(name);
    }

    private boolean typeEndsWith(ASTExpression qualifier, String suffix) {
        return qualifier.getTypeMirror() instanceof JClassType
            && qualifier.getTypeMirror().getSymbol().getSimpleName().endsWith(suffix);
    }


    private int foreignDegree(AssignmentEntry def) {

        if (def.isForeachVar()) {
            ASTForeachStatement foreach = def.getVarId().ancestors(ASTForeachStatement.class).firstOrThrow();
            // same degree as the list
            return foreignDegree(foreach.getIterableExpr());
        }
        // formal parameters are not foreign otherwise we couldn't call any methods on them
        if (def.getVarId().isFormalParameter()) {
            return 1;
        }
        return foreignDegree(def.getRhsAsExpression());
    }

    private int foreignDegree(@Nullable ASTExpression expr) {
        if (expr == null) {
            return 0;
        }
        Integer cachedValue = degreeCache.get(expr);
        if (cachedValue == null) {
            degreeCache.put(expr, -1); // recursion guard
            int computed = foreignDegreeImpl(expr);
            degreeCache.put(expr, computed);
            return computed;
        } else if (cachedValue == -1) {
            return cachedValue; // recursion
        } else {
            return cachedValue;
        }
    }

    private int foreignDegreeImpl(ASTExpression expr) {
        if (expr instanceof ASTMethodCall) {
            return methodForeignDegreeImpl((ASTMethodCall) expr);
        } else if (expr instanceof ASTFieldAccess) {
            return fieldForeignDegreeImpl((ASTFieldAccess) expr);
        } else if (expr instanceof ASTVariableAccess) {
            return variableAccessDegree((ASTVariableAccess) expr);
        } else if (expr instanceof ASTArrayAccess) {
            return foreignDegree(((ASTArrayAccess) expr).getQualifier());
        }
        return 0;
    }

    private int variableAccessDegree(ASTVariableAccess expr) {
        if (JavaRuleUtil.isRefToFieldOfThisInstance(expr)) {
            return 1;
        }

        DataflowResult dataflow = DataflowPass.getDataflowResult(expr.getRoot());
        ReachingDefinitionSet reaching = dataflow.getReachingDefinitions(expr);
        if (reaching.isNotFullyKnown()) {
            return 0; // should never happen
        }

        // note this max could be changed to min to get a more conservative
        // strategy, trading recall for precision. maybe make that configurable
        return reaching.getReaching().stream().mapToInt(this::foreignDegree).max().orElse(0);
    }

    private int fieldForeignDegreeImpl(ASTFieldAccess expr) {
        if (isRefToFieldOfThisInstance(expr)) {
            return 1;
        } else if (isArrayLengthFieldAccess(expr)) {
            // as foreign as the array
            return foreignDegree(expr.getQualifier());
        }
        // more foreign
        return 1 + foreignDegree(expr.getQualifier());
    }

    private int methodForeignDegreeImpl(ASTMethodCall expr) {
        if (producesTrustedData(expr)) {
            return 1;
        } else if (increasesDegree(expr)) {
            return 1 + foreignDegree(expr.getQualifier());
        }
        return foreignDegree(expr.getQualifier());
    }

    /**
     * Method that produces trusted data.
     */
    private boolean producesTrustedData(ASTMethodCall expr) {
        if (expr.getOverloadSelectionInfo().isFailed()) {
            return true; // be conservative
        }
        // static methods are taken to be construction methods.
        return expr.getMethodType().isStatic()
            || isCallOnThisInstance(expr)
            || isFactoryMethod(expr)
            || isBuilderPattern(expr.getQualifier())
            || !isGetterLike(expr) // action methods are not dangerous
            || isNeverForeignMethod(expr)
            || isPureData(expr.getTypeMirror())
            || isPureDataContainer(expr.getMethodType().getDeclaringType());
    }

    private boolean isPureData(JTypeMirror type) {
        return TypeTestUtil.isA(String.class, type)
            || TypeTestUtil.isA(StringBuilder.class, type)
            || TypeTestUtil.isA(StringBuffer.class, type)
            || type.isPrimitive()
            || type.isBoxedPrimitive();
    }

    private boolean isPureDataContainer(JTypeMirror type) {
        return TypeTestUtil.isA(Collection.class, type)
            || type.isArray();
    }

    /**
     * Method that reaches across a boundary. This method assumes
     * {@link #producesTrustedData(ASTMethodCall)} returned false.
     */
    private boolean increasesDegree(ASTMethodCall expr) {
        return isGetterLike(expr);
    }

    private boolean isGetterLike(ASTMethodCall expr) {
        return (isGetterCall(expr)
            || expr.getArguments().isEmpty())
            && !(expr.getParent() instanceof ASTExpressionStatement);
    }

    /**
     * Method that is as trusted as its receiver.
     */
    private boolean isNeverForeignMethod(@NonNull ASTMethodCall expr) {
        return ITERATOR_NEXT.matchesCall(expr)
            || TypeTestUtil.isA(Collection.class, expr.getQualifier())
            || TypeTestUtil.isA(StringBuilder.class, expr.getQualifier())
            || TypeTestUtil.isA(StringBuffer.class, expr.getQualifier())
            || TypeTestUtil.isA(String.class, expr.getQualifier())
            || isBuilderPattern(expr)
            || isFactoryMethod(expr);
    }


}
