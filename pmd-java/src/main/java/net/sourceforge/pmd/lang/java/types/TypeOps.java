/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.types;

import static net.sourceforge.pmd.lang.java.types.Substitution.EMPTY;
import static net.sourceforge.pmd.lang.java.types.Substitution.mapping;
import static net.sourceforge.pmd.lang.java.types.TypeConversion.capture;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import net.sourceforge.pmd.internal.util.IteratorUtil;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JExecutableSymbol;
import net.sourceforge.pmd.lang.java.symbols.JMethodSymbol;
import net.sourceforge.pmd.lang.java.types.TypeConversion.UncheckedConversion;
import net.sourceforge.pmd.lang.java.types.internal.infer.JInferenceVar;
import net.sourceforge.pmd.lang.java.types.internal.infer.JInferenceVar.BoundKind;
import net.sourceforge.pmd.lang.java.types.internal.infer.OverloadComparator;
import net.sourceforge.pmd.util.CollectionUtil;

/**
 * Common operations on types.
 */
@SuppressWarnings("PMD.CompareObjectsWithEquals")
public final class TypeOps {

    private TypeOps() {
        // utility class
    }


    // <editor-fold  defaultstate="collapsed" desc="Type equality">

    public static boolean isSameType(JMethodSig t, JMethodSig s) {
        return t.getDeclaringType().equals(s.getDeclaringType()) && haveSameSignature(t, s);
    }

    /*
     * Note that type mirror implementations use this method as their
     * Object#equals, which means it can't be used here unless it's on
     * the smaller parts of a type.
     */

    public static boolean isSameType(JTypeMirror t, JTypeMirror s) {
        return isSameType(t, s, false);
    }

    /**
     * Returns true if t and s are the same type. If 'inInference' is
     * true, then encountering inference variables produces side effects
     * on them, adding bounds.
     */
    public static boolean isSameType(JTypeMirror t, JTypeMirror s, boolean inInference) {
        if (t == s) {
            // also returns true if both t and s are null
            return true;
        }

        if (t == null || s == null) {
            return false;
        }

        if (!inInference) {
            return t.acceptVisitor(SameTypeVisitor.PURE, s);
        }

        // reorder
        if (t instanceof JInferenceVar) {
            return t.acceptVisitor(SameTypeVisitor.INFERENCE, s);
        } else {
            return s.acceptVisitor(SameTypeVisitor.INFERENCE, t);
        }
    }

    public static boolean areSameTypes(List<JTypeMirror> ts, List<JTypeMirror> ss, boolean inInference) {
        return areSameTypes(ts, ss, EMPTY, inInference);
    }

    public static boolean areSameTypes(List<JTypeMirror> ts, List<JTypeMirror> ss, Substitution subst) {
        return areSameTypes(ts, ss, subst, false);
    }

    public static boolean areSameTypes(List<JTypeMirror> ts, List<JTypeMirror> ss, Substitution subst, boolean inInference) {
        if (ts.size() != ss.size()) {
            return false;
        }
        for (int i = 0; i < ts.size(); i++) {
            if (!isSameType(ts.get(i), ss.get(i).subst(subst), inInference)) {
                return false;
            }
        }
        return true;
    }

    public static boolean allArgsAreUnboundedWildcards(List<JTypeMirror> sargs) {
        for (JTypeMirror sarg : sargs) {
            if (!(sarg instanceof JWildcardType) || !((JWildcardType) sarg).isUnbounded()) {
                return false;
            }
        }
        return true;
    }

    private static class SameTypeVisitor implements JTypeVisitor<Boolean, JTypeMirror> {

        static final SameTypeVisitor INFERENCE = new SameTypeVisitor(true);
        static final SameTypeVisitor PURE = new SameTypeVisitor(false);

        private final boolean inInference;

        private SameTypeVisitor(boolean inInference) {
            this.inInference = inInference;
        }

        @Override
        public Boolean visit(JTypeMirror t, JTypeMirror s) {
            // for primitive & sentinel types
            return t == s;
        }

        @Override
        public Boolean visitClass(JClassType t, JTypeMirror s) {
            if (s instanceof JClassType) {
                JClassType s2 = (JClassType) s;
                return t.getSymbol().getBinaryName().equals(s2.getSymbol().getBinaryName())
                    && t.hasErasedSuperTypes() == s2.hasErasedSuperTypes()
                    && isSameType(t.getEnclosingType(), s2.getEnclosingType(), inInference)
                    && areSameTypes(t.getTypeArgs(), s2.getTypeArgs(), inInference);
            }
            return false;
        }

        @Override
        public Boolean visitTypeVar(JTypeVar t, JTypeMirror s) {
            return t.equals(s);
        }

        @Override
        public Boolean visitWildcard(JWildcardType t, JTypeMirror s) {
            if (!(s instanceof JWildcardType)) {
                return false;
            }
            JWildcardType s2 = (JWildcardType) s;
            return s2.isUpperBound() == t.isUpperBound() && isSameType(t.getBound(), s2.getBound(), inInference);
        }

        @Override
        public Boolean visitInferenceVar(JInferenceVar t, JTypeMirror s) {
            if (!inInference) {
                return t == s;
            }

            if (s instanceof JPrimitiveType) {
                return false;
            }

            if (s instanceof JWildcardType) {
                JWildcardType s2 = (JWildcardType) s;
                if (s2.isUpperBound()) {
                    t.addBound(BoundKind.UPPER, s2.asUpperBound());
                } else {
                    t.addBound(BoundKind.LOWER, s2.asLowerBound());
                }
                return true;
            }

            // add an equality bound
            t.addBound(BoundKind.EQ, s);
            return true;
        }

        @Override
        public Boolean visitIntersection(JIntersectionType t, JTypeMirror s) {
            if (!(s instanceof JIntersectionType)) {
                return false;
            }

            JIntersectionType s2 = (JIntersectionType) s;

            // order is irrelevant

            if (!isSameType(t.getSuperClass(), s2.getSuperClass(), inInference)) {
                return false;
            }

            Map<JTypeMirror, JTypeMirror> tMap = new HashMap<>();
            for (JTypeMirror ti : t.getInterfaces()) {
                tMap.put(ti.getErasure(), ti);
            }
            for (JTypeMirror si : s2.getInterfaces()) {
                JTypeMirror siErased = si.getErasure();
                if (!tMap.containsKey(siErased)) {
                    return false;
                }
                JTypeMirror ti = tMap.remove(siErased);
                if (!isSameType(ti, si, inInference)) {
                    return false;
                }
            }
            return tMap.isEmpty();
        }

        @Override
        public Boolean visitArray(JArrayType t, JTypeMirror s) {
            if (s instanceof JArrayType) {
                return isSameType(t.getComponentType(), ((JArrayType) s).getComponentType(), inInference);
            }
            return false;
        }
    }

    // </editor-fold>

    // <editor-fold  defaultstate="collapsed" desc="Supertype enumeration">


    /**
     * Returns the set of all supertypes of the given type.
     *
     * @see JTypeMirror#getSuperTypeSet()
     */
    public static Set<JTypeMirror> getSuperTypeSet(@NonNull JTypeMirror t) {
        Set<JTypeMirror> result = new LinkedHashSet<>();
        t.acceptVisitor(SuperTypesVisitor.INSTANCE, result);
        assert !result.isEmpty() : "Empty supertype set for " + t;
        return result;
    }

    private static final class SuperTypesVisitor implements JTypeVisitor<Void, Set<JTypeMirror>> {

        static final SuperTypesVisitor INSTANCE = new SuperTypesVisitor();

        @Override
        public Void visit(JTypeMirror t, Set<JTypeMirror> result) {
            throw new IllegalStateException("Should not be called");
        }

        @Override
        public Void visitTypeVar(JTypeVar t, Set<JTypeMirror> result) {
            if (result.add(t)) {
                // prevent infinite loop
                t.getUpperBound().acceptVisitor(this, result);
            }
            return null;
        }

        @Override
        public Void visitNullType(JTypeMirror t, Set<JTypeMirror> result) {
            // too many types
            throw new IllegalArgumentException("The null type has all reference types as supertype");
        }

        @Override
        public Void visitSentinel(JTypeMirror t, Set<JTypeMirror> result) {
            result.add(t);
            return null;
        }

        @Override
        public Void visitInferenceVar(JInferenceVar t, Set<JTypeMirror> result) {
            result.add(t);
            return null;
        }

        @Override
        public Void visitWildcard(JWildcardType t, Set<JTypeMirror> result) {
            t.asUpperBound().acceptVisitor(this, result);
            // wildcards should be captured and so we should not end up here
            return null;
        }

        @Override
        public Void visitClass(JClassType t, Set<JTypeMirror> result) {
            result.add(t);


            // prefer digging up the superclass first
            JClassType sup = t.getSuperClass();
            if (sup != null) {
                sup.acceptVisitor(this, result);
            }
            for (JClassType i : t.getSuperInterfaces()) {
                visitClass(i, result);
            }
            if (t.isInterface() && t.getSuperInterfaces().isEmpty()) {
                result.add(t.getTypeSystem().OBJECT);
            }
            return null;
        }

        @Override
        public Void visitIntersection(JIntersectionType t, Set<JTypeMirror> result) {
            for (JTypeMirror it : t.getComponents()) {
                it.acceptVisitor(this, result);
            }
            return null;
        }

        @Override
        public Void visitArray(JArrayType t, Set<JTypeMirror> result) {
            result.add(t);

            TypeSystem ts = t.getTypeSystem();

            for (JTypeMirror componentSuper : t.getComponentType().getSuperTypeSet()) {
                result.add(ts.arrayType(componentSuper, 1));
            }
            result.add(ts.CLONEABLE);
            result.add(ts.SERIALIZABLE);
            result.add(ts.OBJECT);

            return null;
        }

        @Override
        public Void visitPrimitive(JPrimitiveType t, Set<JTypeMirror> result) {
            result.addAll(t.getSuperTypeSet()); // special implementation in JPrimitiveType
            return null;
        }
    }

    // </editor-fold>

    // <editor-fold  defaultstate="collapsed" desc="Subtyping">

    /**
     * Returns true if {@code T <: S}, ie T is a subtype of S.
     *
     * @param t         T
     * @param s         S
     * @param unchecked Whether to allow unchecked conversion.
     *                  If true, then this tests if T is *convertible*
     *                  to S, there is no "unchecked subtyping".
     */
    public static boolean isSubtype(@NonNull JTypeMirror t, @NonNull JTypeMirror s, boolean unchecked) {
        if (t == s) {
            return true;
        } else if (s == t.getTypeSystem().OBJECT) {
            return !t.isPrimitive();
        }

        if (s instanceof JInferenceVar) {
            // it's possible to add a bound to UNRESOLVED
            ((JInferenceVar) s).addBound(BoundKind.LOWER, t);
            return true;
        } else if (isUnresolved(t)) {
            // don't check both, because subtyping must be asymmetric
            return true;
        }

        return capture(t).acceptVisitor(unchecked ? SubtypeVisitor.UNCHECKED : SubtypeVisitor.CHECKED, s);
    }

    private static boolean isUnresolved(@NonNull JTypeMirror s) {
        TypeSystem ts = s.getTypeSystem();
        return s == ts.UNRESOLVED_TYPE || s == ts.ERROR_TYPE || s.getSymbol() != null && s.getSymbol().isUnresolved();
    }

    private static JTypeMirror upperBound(JTypeMirror type) {
        if (type instanceof JWildcardType) {
            return upperBound(((JWildcardType) type).asUpperBound());
        }
        return type;
    }

    private static JTypeMirror lowerBound(JTypeMirror type) {
        if (type instanceof JWildcardType) {
            return lowerBound(((JWildcardType) type).asLowerBound());
        }
        return type;
    }

    private static JTypeMirror lowerBoundRec(JTypeMirror type) {
        if (type instanceof JWildcardType) {
            return lowerBoundRec(((JWildcardType) type).asLowerBound());
        } else if (type instanceof JTypeVar && ((JTypeVar) type).isCaptured()) {
            return lowerBoundRec(((JTypeVar) type).getLowerBound());
        }
        return type;
    }

    private static boolean isTypeRange(JTypeMirror s) {
        return s instanceof JWildcardType
            || s instanceof JTypeVar && ((JTypeVar) s).isCaptured();
    }


    /**
     * Returns true if {@code S <= T}, ie "T contains S".
     *
     * <p>T contains S if:
     *
     * <p>{@code L(T) <: L(S) && U(S) <: U(T)}
     *
     * <p>This only makes sense for type arguments, it's a component of
     * subtype checks for parameterized types:
     *
     * <p>{@code C<T> <: C<S> if T <= S}
     *
     * <p>Defined in JLS§4.5.1 (Type Arguments of Parameterized Types)
     */
    public static boolean typeArgContains(JTypeMirror t, JTypeMirror s) {
        // the contains relation can be understood intuitively if we
        // represent types as ranges on a line:

        // ⊥ ---------L(T)---L(S)------U(S)-----U(T)---> Object
        // range of T   [-------------------------]
        // range of S          [---------]

        // here T contains S because its range is greater

        // since a wildcard is either "super" or "extends", in reality
        // either L(T) = ⊥, or U(T) = Object.

        // meaning when T != S, we only have two scenarios where S <= T:

        //      ⊥ -------U(S)-----U(T)------> Object   (L(S) = L(T) = ⊥)
        //      ⊥ -------L(T)-----L(S)------> Object   (U(S) = U(T) = Object)

        if (isSameType(t, s, true)) {
            // T <= T
            return true;
        }

        //        if (t instanceof JWildcardType && s instanceof JTypeVar) {
        //            if (((JTypeVar) s).isCaptureOf((JWildcardType) t)) {
        //                return true;
        //            }
        //        }

        if (t instanceof JWildcardType) {
            JWildcardType tw = (JWildcardType) t;

            return
                // L(T) <: L(S) if T is "extends" bound (L(T) is bottom)
                (tw.isUpperBound() || tw.asLowerBound().isSubtypeOf(lowerBound(s)))
                    //  U(S) <: U(T) if T is "super" bound (U(T) is top)
                    && (tw.isLowerBound() || upperBound(s).isSubtypeOf(tw.asUpperBound()));

        }

        return false;
    }


    private static final class SubtypeVisitor implements JTypeVisitor<Boolean, JTypeMirror> {

        static final SubtypeVisitor UNCHECKED = new SubtypeVisitor(true);
        static final SubtypeVisitor CHECKED = new SubtypeVisitor(false);

        private final boolean unchecked;

        private SubtypeVisitor(boolean unchecked) {
            this.unchecked = unchecked;
        }

        @Override
        public Boolean visit(JTypeMirror t, JTypeMirror s) {
            throw new IllegalStateException("Should not be called");
        }

        @Override
        public Boolean visitTypeVar(JTypeVar t, JTypeMirror s) {
            if (isTypeRange(s)) {
                return t.isSubtypeOf(lowerBoundRec(s), unchecked);
            }
            return t.getUpperBound().isSubtypeOf(s, unchecked);
        }

        @Override
        public Boolean visitNullType(JTypeMirror t, JTypeMirror s) {
            return !s.isPrimitive();
        }

        @Override
        public Boolean visitSentinel(JTypeMirror t, JTypeMirror s) {
            return true;
        }

        @Override
        public Boolean visitInferenceVar(JInferenceVar t, JTypeMirror s) {
            if (s == t.getTypeSystem().NULL_TYPE || s instanceof JPrimitiveType) {
                return false;
            }
            // here we add a constraint on the variable
            t.addBound(BoundKind.UPPER, s);
            return true;
        }

        @Override
        public Boolean visitWildcard(JWildcardType t, JTypeMirror s) {
            // wildcards should be captured and so we should not end up here
            return false;
        }

        @Override
        public Boolean visitClass(JClassType t, JTypeMirror s) {
            if (s == t.getTypeSystem().OBJECT) {
                return true;
            }

            if (s instanceof JIntersectionType) {
                // If S is an intersection, then T must conform to *all* bounds of S
                // Symmetrically, if T is an intersection, T <: S requires only that
                // at least one bound of T is a subtype of S.
                for (JTypeMirror ui : asList(s)) {
                    if (!t.isSubtypeOf(ui, unchecked)) {
                        return false;
                    }
                }
                return true;
            }

            if (isTypeRange(s)) {
                return t.isSubtypeOf(lowerBoundRec(s), unchecked);
            }

            if (!(s instanceof JClassType)) {
                // note, that this ignores wildcard types,
                // because they're only compared through
                // type argument containment.
                return false;
            }

            JClassType cs = (JClassType) s;

            // most specific
            // if null then not a subtype
            JClassType superDecl = t.getAsSuper(cs.getSymbol());

            return superDecl != null && (
                    // unchecked conversion maps a raw type C to any parameterization C<T1, .., Tn>
                    unchecked && superDecl.isRaw()
                        // a raw type C is a supertype for all the family of parameterized type generated by C<F1, .., Fn>
                        || cs.isRaw()
                        // otherwise, each type arguments must contain the other pairwise
                        || typeArgsAreContained(superDecl.getTypeArgs(), cs.getTypeArgs())
                );
        }

        /**
         * Generalises equality to check if for each i, {@code Ti <= Si}.
         */
        private boolean typeArgsAreContained(List<JTypeMirror> targs, List<JTypeMirror> sargs) {
            if (targs.isEmpty() && !sargs.isEmpty()) {
                // T is raw, it's convertible to S if T = C and S = D<?, .., ?>, C <: D

                // This is technically an unchecked conversion, but which
                // is safe and generates no warning, so could be allowed by default?
                if (!unchecked) {
                    return false;
                }
                return allArgsAreUnboundedWildcards(sargs);
            }

            for (int i = 0; i < targs.size(); i++) {
                if (!typeArgContains(sargs.get(i), targs.get(i))) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public Boolean visitIntersection(JIntersectionType t, JTypeMirror s) {
            return t.getComponents().stream().anyMatch(it -> it.isSubtypeOf(s));
        }

        @Override
        public Boolean visitArray(JArrayType t, JTypeMirror s) {
            TypeSystem ts = t.getTypeSystem();
            if (s == ts.OBJECT || s.equals(ts.CLONEABLE) || s.equals(ts.SERIALIZABLE)) {
                return true;
            }

            if (!(s instanceof JArrayType)) {
                // not comparable to any other type
                return false;
            }

            JArrayType cs = (JArrayType) s;

            if (t.getComponentType().isPrimitive() || cs.getComponentType().isPrimitive()) {
                // arrays of primitive types have no sub-/ supertype
                return cs.getComponentType() == t.getComponentType();
            } else {
                return t.getComponentType().isSubtypeOf(cs.getComponentType(), unchecked);
            }
        }

        @Override
        public Boolean visitPrimitive(JPrimitiveType t, JTypeMirror s) {
            return t.isSubtypeOf(s, false); // JPrimitiveType already overrides this
        }
    }

    // </editor-fold>

    // <editor-fold  defaultstate="collapsed" desc="Substitution">

    /**
     * Replace the type variables occurring in the given type to their
     * image by the given function. Substitutions are not applied
     * recursively.
     *
     * @param type  Type to substitute
     * @param subst Substitution function, eg a {@link Substitution}
     */
    public static JTypeMirror subst(@Nullable JTypeMirror type, Function<? super SubstVar, ? extends @NonNull JTypeMirror> subst) {
        if (type == null || Substitution.isEmptySubst(subst)) {
            return type;
        }
        return type.subst(subst);
    }


    /** Substitute on a list of types. */
    public static List<JTypeMirror> subst(List<? extends JTypeMirror> ts, Function<? super SubstVar, ? extends @NonNull JTypeMirror> subst) {
        return mapPreservingSelf(ts, t -> t.subst(subst));
    }

    public static List<JTypeVar> substInBoundsOnly(List<JTypeVar> ts, Function<? super SubstVar, ? extends @NonNull JTypeMirror> subst) {
        return mapPreservingSelf(ts, t -> t.substInBounds(subst));
    }

    // relies on the fact the original list is unmodifiable or won't be
    // modified
    @NonNull
    @SuppressWarnings("unchecked")
    private static <T> List<T> mapPreservingSelf(List<? extends T> ts, Function<? super T, ? extends @NonNull T> subst) {
        // Profiling shows, only 10% of calls to this method need to
        // create a new list. Substitution in general is a hot spot
        // of the framework, so optimizing this out is nice
        List<T> list = null;
        for (int i = 0, size = ts.size(); i < size; i++) {
            T it = ts.get(i);
            T substed = subst.apply(it);
            if (substed != it) {
                if (list == null) {
                    list = Arrays.asList((T[]) ts.toArray()); // NOPMD ClassCastExceptionWithToArray
                }
                list.set(i, substed);
            }
        }

        // subst relies on the fact that the original list is returned
        // to avoid new type creation. Thus one cannot use
        // Collections::unmodifiableList here
        return list != null ? list : (List<T>) ts;
    }

    // </editor-fold>

    // <editor-fold  defaultstate="collapsed" desc="Projection">


    /**
     * Returns the upwards projection of the given type, with respect
     * to the set of capture variables that are found in it. This is
     * some supertype of T which does not mention those capture variables.
     * This is used for local variable type inference.
     *
     * https://docs.oracle.com/javase/specs/jls/se11/html/jls-4.html#jls-4.10.5
     */
    public static JTypeMirror projectUpwards(JTypeMirror t) {
        return t.acceptVisitor(UPWARDS_PROJECTOR, null);
    }

    private static final JTypeMirror NO_DOWN_PROJECTION = null;
    private static final ProjectionVisitor UPWARDS_PROJECTOR = new ProjectionVisitor(true) {

        @Override
        public JTypeMirror visitTypeVar(JTypeVar t, Void v) {
            if (t.isCaptured()) {
                return t.getUpperBound().acceptVisitor(UPWARDS_PROJECTOR, null);
            }
            return t;
        }


        @Override
        public JTypeMirror visitWildcard(JWildcardType t, Void v) {
            JTypeMirror u = t.getBound().acceptVisitor(UPWARDS_PROJECTOR, null);
            if (u == t.getBound()) {
                return t;
            }
            TypeSystem ts = t.getTypeSystem();

            if (t.isUpperBound()) {
                return ts.wildcard(true, u);
            } else {
                JTypeMirror down = t.getBound().acceptVisitor(DOWNWARDS_PROJECTOR, null);
                return down == NO_DOWN_PROJECTION ? ts.UNBOUNDED_WILD : ts.wildcard(false, down);
            }
        }


        @Override
        public JTypeMirror visitNullType(JTypeMirror t, Void v) {
            return t;
        }

    };


    private static final ProjectionVisitor DOWNWARDS_PROJECTOR = new ProjectionVisitor(false) {

        @Override
        public JTypeMirror visitWildcard(JWildcardType t, Void v) {
            JTypeMirror u = t.getBound().acceptVisitor(UPWARDS_PROJECTOR, null);
            if (u == t.getBound()) {
                return t;
            }
            TypeSystem ts = t.getTypeSystem();

            if (t.isUpperBound()) {
                JTypeMirror down = t.getBound().acceptVisitor(DOWNWARDS_PROJECTOR, null);
                return down == NO_DOWN_PROJECTION ? NO_DOWN_PROJECTION
                                                  : ts.wildcard(true, down);
            } else {
                return ts.wildcard(false, u);
            }
        }


        @Override
        public JTypeMirror visitTypeVar(JTypeVar t, Void v) {
            if (t.isCaptured()) {
                return t.getLowerBound().acceptVisitor(DOWNWARDS_PROJECTOR, null);
            }
            return t;
        }

        @Override
        public JTypeMirror visitNullType(JTypeMirror t, Void v) {
            return NO_DOWN_PROJECTION;
        }
    };

    /**
     * Restricted type variables are:
     * - Inference vars
     * - Capture vars
     *
     * See
     *
     * https://docs.oracle.com/javase/specs/jls/se11/html/jls-4.html#jls-4.10.5
     *
     *
     * <p>Here we use {@link #NO_DOWN_PROJECTION} as a sentinel
     * (downwards projection is a partial function). If a type does not mention
     * restricted type variables, then the visitor should return the original
     * type (same reference). This allows testing predicates like
     * <blockquote>
     * "If Ai does not mention any restricted type variable, then Ai' = Ai."
     * </blockquote>
     */
    private abstract static class ProjectionVisitor implements JTypeVisitor<JTypeMirror, Void> {

        private final boolean upwards;

        private ProjectionVisitor(boolean upwards) {
            this.upwards = upwards;
        }


        @Override
        public abstract JTypeMirror visitNullType(JTypeMirror t, Void v);


        @Override
        public abstract JTypeMirror visitWildcard(JWildcardType t, Void v);


        @Override
        public abstract JTypeMirror visitTypeVar(JTypeVar t, Void v);


        @Override
        public JTypeMirror visit(JTypeMirror t, Void v) {
            return t;
        }

        @Override
        public JTypeMirror visitClass(JClassType t, Void v) {
            if (t.isParameterizedType()) {
                List<JTypeMirror> targs = t.getTypeArgs();
                List<JTypeMirror> newTargs = new ArrayList<>(targs.size());
                List<JTypeVar> formals = t.getFormalTypeParams();
                boolean change = false;

                for (int i = 0; i < targs.size(); i++) {
                    JTypeMirror ai = targs.get(i);
                    JTypeMirror u = ai.acceptVisitor(this, null);
                    if (u == ai || ai instanceof JWildcardType) {
                        // no change, or handled by the visitWildcard
                        newTargs.add(u);
                        continue;
                    } else if (!upwards) {
                        // If Ai is a type that mentions a restricted type variable, then Ai' is undefined.
                        return NO_DOWN_PROJECTION;
                    }

                    change = true;

                    /*
                        If Ai is a type that mentions a restricted type variable...
                     */
                    JTypeMirror bi = formals.get(i).getUpperBound();

                    TypeSystem ts = t.getTypeSystem();
                    if (u != ts.OBJECT
                        && (mentionsAnyTvar(bi, formals) || !bi.isSubtypeOf(u))) {
                        newTargs.add(ts.wildcard(true, u));
                    } else {
                        JTypeMirror down = ai.acceptVisitor(DOWNWARDS_PROJECTOR, null);
                        if (down == NO_DOWN_PROJECTION) {
                            newTargs.add(ts.UNBOUNDED_WILD);
                        } else {
                            newTargs.add(ts.wildcard(false, down));
                        }
                    }
                }

                return change ? t.withTypeArguments(newTargs) : t;
            } else {
                return t;
            }
        }

        @Override
        public JTypeMirror visitIntersection(JIntersectionType t, Void v) {
            List<JTypeMirror> comps = new ArrayList<>(t.getComponents());
            boolean change = false;
            for (int i = 0; i < comps.size(); i++) {
                JTypeMirror ci = comps.get(i);
                JTypeMirror proj = ci.acceptVisitor(this, null);
                if (proj == NO_DOWN_PROJECTION) {
                    return proj;
                } else {
                    comps.set(i, proj);
                    if (ci != proj) {
                        change = true;
                    }
                }
            }
            return change ? t.getTypeSystem().intersect(comps) : t;
        }

        @Override
        public JTypeMirror visitArray(JArrayType t, Void v) {
            JTypeMirror comp2 = t.getComponentType().acceptVisitor(this, null);
            return comp2 == NO_DOWN_PROJECTION
                   ? NO_DOWN_PROJECTION
                   : comp2 == t.getComponentType()
                     ? t : t.getTypeSystem().arrayType(comp2, 1);
        }

        @Override
        public JTypeMirror visitSentinel(JTypeMirror t, Void v) {
            return t;
        }
    }

    // </editor-fold>

    // <editor-fold  defaultstate="collapsed" desc="Overriding">

    /**
     * Returns true if m1 is return-type substitutable with m2. . The notion of return-type-substitutability
     * supports covariant returns, that is, the specialization of the return type to a subtype.
     *
     * https://docs.oracle.com/javase/specs/jls/se9/html/jls-8.html#jls-8.4.5
     */
    public static boolean isReturnTypeSubstitutable(JMethodSig m1, JMethodSig m2) {

        JTypeMirror r1 = m1.getReturnType();
        JTypeMirror r2 = m2.getReturnType();

        if (r1 == r1.getTypeSystem().NO_TYPE) {
            return r1 == r2;
        }

        if (r1.isPrimitive()) {
            return r1 == r2;
        }

        if (r1.isRaw() && TypeConversion.uncheckedConversionExists(r1, r2) != UncheckedConversion.NONE) {
            return true;
        }

        JMethodSig m1Prime = adaptForTypeParameters(m1, m2);
        if (m1Prime != null && m1Prime.getReturnType().isSubtypeOf(r2)) {
            return true;
        }

        if (!haveSameSignature(m1, m2)) {
            return isSameType(r1, r2.getErasure());
        }

        return false;
    }

    /**
     * Adapt m1 to the type parameters of m2. Returns null if that's not possible.
     *
     * https://docs.oracle.com/javase/specs/jls/se9/html/jls-8.html#jls-8.4.4
     */
    @Nullable
    private static JMethodSig adaptForTypeParameters(JMethodSig m1, JMethodSig m2) {
        if (haveSameTypeParams(m1, m2)) {
            return m1.subst(mapping(m1.getTypeParameters(), m2.getTypeParameters()));
        }

        return null;
    }

    private static boolean haveSameTypeParams(JMethodSig m1, JMethodSig m2) {
        List<JTypeVar> tp1 = m1.getTypeParameters();
        List<JTypeVar> tp2 = m2.getTypeParameters();
        if (tp1.size() != tp2.size()) {
            return false;
        }

        if (tp1.isEmpty()) {
            return true;
        }

        for (int i = 0; i < tp1.size(); i++) {
            JTypeVar p1 = tp1.get(i);
            JTypeVar p2 = tp2.get(i);

            if (!isSameType(p1, subst(p2, mapping(tp2, tp1)))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Two method signatures m1 and m2 are override-equivalent iff either
     * m1 is a subsignature of m2 or m2 is a subsignature of m1. This does
     * not look at the origin of the methods (their declaring class).
     *
     * https://docs.oracle.com/javase/specs/jls/se9/html/jls-8.html#jls-8.4.2
     */
    public static boolean areOverrideEquivalent(JMethodSig m1, JMethodSig m2) {
        // This method is a very hot spot as it is used to prune shadowed/overridden/hidden
        // methods from overload candidates before overload resolution.
        // Any optimization makes a big impact.
        if (m1.getArity() != m2.getArity()) {
            return false; // easy case
        } else if (m1 == m2) {
            return true;
        }
        // Two methods can only have the same signature if they have the same type parameters
        // But a generic method is allowed to override a non-generic one, and vice versa
        // So we first project both methods into a form that has the same number of type parameters
        boolean m1Gen = m1.isGeneric();
        boolean m2Gen = m2.isGeneric();
        if (m1Gen ^ m2Gen) {
            if (m1Gen) {
                m1 = m1.getErasure();
            } else {
                m2 = m2.getErasure();
            }
        }
        return haveSameSignature(m1, m2);
    }

    /**
     * The signature of a method m1 is a subsignature of the signature of a method m2 if either:
     * - m2 has the same signature as m1, or
     * - the signature of m1 is the same as the erasure (§4.6) of the signature of m2.
     */
    private static boolean isSubSignature(JMethodSig m1, JMethodSig m2) {
        // prune easy cases
        if (m1.getArity() != m2.getArity() || !m1.getName().equals(m2.getName())) {
            return false;
        }
        boolean m1Gen = m1.isGeneric();
        boolean m2Gen = m2.isGeneric();
        if (m1Gen ^ m2Gen) {
            if (m1Gen) {
                return false; // this test is assymetric
            } else {
                m2 = m2.getErasure();
            }
        }
        return haveSameSignature(m1, m2);
    }

    /**
     * Two methods or constructors, M and N, have the same signature if
     * they have the same name, the same type parameters (if any) (§8.4.4),
     * and, after adapting the formal parameter types of N to the the type
     * parameters of M, the same formal parameter types.
     *
     * Thrown exceptions are not part of the signature of a method.
     */
    private static boolean haveSameSignature(JMethodSig m1, JMethodSig m2) {
        if (!m1.getName().equals(m2.getName()) || m1.getArity() != m2.getArity()) {
            return false;
        }

        if (!haveSameTypeParams(m1, m2)) {
            return false;
        }

        return areSameTypes(m1.getFormalParameters(),
                            m2.getFormalParameters(),
                            Substitution.mapping(m2.getTypeParameters(), m1.getTypeParameters()));
    }

    /**
     * Returns true if m1 overrides m2, when both are view as members of
     * class origin. m1 and m2 may be declared in supertypes of origin,
     * possibly unrelated (default methods), which is why we need that
     * third parameter. By convention a method overrides itself.
     *
     * <p>This method ignores the static modifier. If both methods are
     * static, then this method tests for <i>hiding</i>. Otherwise, this
     * method properly tests for overriding. Note that it is an error for
     * a static method to override an instance method, or the reverse.
     */
    public static boolean overrides(JMethodSig m1, JMethodSig m2, JTypeMirror origin) {

        if (m1.isConstructor() || m2.isConstructor()) {
            return false;
        }

        JTypeMirror m1Owner = m1.getDeclaringType();
        JClassType m2Owner = (JClassType) m2.getDeclaringType();

        if (isOverridableIn(m2, m2Owner.getSymbol(), (JClassSymbol) m1Owner.getSymbol())
            && m1Owner.getAsSuper(m2Owner.getSymbol()) != null) {

            if (isSubSigInOrigin(m1, m2, m1Owner)) {
                return true;
            }
        }

        // check for an inherited implementation
        if (m1.isAbstract()
            || !m2.isAbstract() && !m2.getSymbol().isDefaultMethod()
            || !isOverridableIn(m2, m2Owner.getSymbol(), (JClassSymbol) origin.getSymbol())
            || !origin.isSubtypeOf(m2Owner)) {
            return false;
        }

        return isSubSigInOrigin(m1, m2, origin);
    }

    private static boolean isSubSigInOrigin(JMethodSig m1, JMethodSig m2, JTypeMirror origin) {
        JMethodSig s1;
        JMethodSig s2;

        if (origin.isRaw()) {
            s1 = m1.getErasure();
            s2 = m2.getErasure();
        } else {
            Substitution subst = origin instanceof JClassType ? ((JClassType) origin).getTypeParamSubst() : EMPTY;
            s1 = m1.subst(subst);
            s2 = m2.subst(subst);
        }
        return isSubSignature(s1, s2);
    }

    /**
     * Returns true if the given method can be overridden in the origin
     * class. This only checks access modifiers and not eg whether the
     * method is final or static. Regardless of whether the method is
     * final it is overridden - whether this is a compile error or not
     * is another matter.
     *
     * <p>Like {@link #overrides(JMethodSig, JMethodSig, JTypeMirror)},
     * this does not check the static modifier, and tests for hiding
     * if the method is static.
     *
     * @param m         Method to test
     * @param declaring Symbol of the declaring type of m
     * @param origin    Site of the potential override
     */
    private static boolean isOverridableIn(JMethodSig m, JClassSymbol declaring, JClassSymbol origin) {
        final int accessFlags = Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE;

        // JLS 8.4.6.1
        switch (m.getModifiers() & accessFlags) {
        case Modifier.PUBLIC:
            return true;
        case Modifier.PROTECTED:
            return !origin.isInterface();
        case 0:
            // package private
            return
                declaring.getPackageName().equals(origin.getPackageName())
                    && !origin.isInterface();
        default:
            // private
            return false;
        }
    }

    // </editor-fold>

    // <editor-fold  defaultstate="collapsed" desc="SAM types">

    /*
     * Function types of SAM (single-abstract-method) types.
     *
     * See https://docs.oracle.com/javase/specs/jls/se11/html/jls-9.html#jls-9.9
     */


    /**
     * Returns the non-wildcard parameterization of the given functional
     * interface type. Returns null if such a parameterization does not
     * exist.
     *
     * <p>This is used to remove wildcards from the type of a functional
     * interface.
     *
     * https://docs.oracle.com/javase/specs/jls/se9/html/jls-9.html#jls-9.9
     *
     * @param type A parameterized functional interface type
     */
    @Nullable
    public static JClassType nonWildcardParameterization(@NonNull JClassType type) {
        TypeSystem ts = type.getTypeSystem();

        List<JTypeMirror> targs = type.getTypeArgs();
        if (targs.stream().noneMatch(it -> it instanceof JWildcardType)) {
            return type;
        }

        List<JTypeVar> tparams = type.getFormalTypeParams();
        List<JTypeMirror> newArgs = new ArrayList<>();

        for (int i = 0; i < tparams.size(); i++) {
            JTypeMirror ai = targs.get(i);
            if (ai instanceof JWildcardType) {
                JTypeVar pi = tparams.get(i);
                JTypeMirror bi = pi.getUpperBound();
                if (mentionsAnyTvar(bi, new HashSet<>(tparams))) {
                    return null;
                }

                JWildcardType ai2 = (JWildcardType) ai;

                if (ai2.isUnbounded()) {
                    newArgs.add(bi);
                } else if (ai2.isUpperBound()) {
                    newArgs.add(ts.glb(Arrays.asList(ai2.asUpperBound(), bi)));
                } else { // lower bound
                    newArgs.add(ai2.asLowerBound());
                }

            } else {
                newArgs.add(ai);
            }

        }

        return type.withTypeArguments(newArgs);
    }

    /**
     * Finds the method of the given type that can be overridden as a lambda
     * expression. That is more complicated than "the unique abstract method",
     * it's actually a function type which can override all abstract methods
     * of the SAM at once.
     *
     * https://docs.oracle.com/javase/specs/jls/se9/html/jls-9.html#jls-9.9
     */
    @Nullable
    public static JMethodSig findFunctionalInterfaceMethod(JClassType candidateSam) {
        if (candidateSam == null) {
            return null;
        }

        if (candidateSam.isParameterizedType()) {
            return findFunctionTypeImpl(nonWildcardParameterization(candidateSam));
        } else if (candidateSam.isRaw()) {
            //  The function type of the raw type of a generic functional
            //  interface I<...> is the erasure of the function type of the generic functional interface I<...>.
            JMethodSig fun = findFunctionTypeImpl(candidateSam.getGenericTypeDeclaration());
            return fun == null ? null : fun.getErasure();
        } else {
            return findFunctionTypeImpl(candidateSam);
        }

        // TODO
        //  The function type of an intersection type that induces a notional functional
        //  interface is the function type of the notional functional interface.
    }

    @Nullable
    private static JMethodSig findFunctionTypeImpl(@Nullable JClassType candidateSam) {

        if (candidateSam == null || !candidateSam.isInterface() || candidateSam.getSymbol().isAnnotation()) {
            return null;
        }

        List<JMethodSig> candidates =
            candidateSam.streamMethods(it -> Modifier.isAbstract(it.getModifiers()))
                        .filter(TypeOps::isNotDeclaredInClassObject)
                        .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return null;
        } else if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // we can technically order them approximately by depth in the
        // hierarchy to select the one that has the best chance to override
        // all other abstracts.

        JMethodSig currentBest = null;

        nextCandidate:
        for (int i = 0; i < candidates.size(); i++) {
            JMethodSig cand = candidates.get(i);

            for (JMethodSig other : candidates) {
                if (!isSubSignature(cand, other)
                    || !isReturnTypeSubstitutable(cand, other)) {
                    continue nextCandidate;
                }
            }

            if (currentBest == null) {
                currentBest = cand;
            } else if (cand.getReturnType().isSubtypeOf(currentBest.getReturnType())) {
                // select the most specific return type
                currentBest = cand;
            }
        }

        return currentBest;
    }

    private static boolean isNotDeclaredInClassObject(JMethodSig it) {
        TypeSystem ts = it.getDeclaringType().getTypeSystem();
        return ts.OBJECT.streamMethods(om -> Modifier.isPublic(om.getModifiers()) && om.getSimpleName().equals(it.getName()))
                        .noneMatch(om -> haveSameSignature(it, om));
    }

    // </editor-fold>

    // <editor-fold  defaultstate="collapsed" desc="As super">

    /**
     * @see JTypeMirror#getAsSuper(JClassSymbol)
     */
    @Nullable
    public static JTypeMirror asSuper(JTypeMirror t, JClassSymbol s) {

        if (!t.isPrimitive() && s.equals(t.getTypeSystem().OBJECT.getSymbol())) {
            // interface types need to have OBJECT somewhere up their hierarchy
            return t.getTypeSystem().OBJECT;
        }

        return t.acceptVisitor(AsSuperVisitor.INSTANCE, s);
    }

    /**
     * Return the base type of t or any of its outer types that starts
     * with the given type.  If none exists, return null.
     */
    public static JClassType asOuterSuper(JTypeMirror t, JClassSymbol sym) {
        if (t instanceof JClassType) {
            JClassType ct = (JClassType) t;
            do {
                JClassType sup = ct.getAsSuper(sym);
                if (sup != null) {
                    return sup;
                }
                ct = ct.getEnclosingType();
            } while (ct != null);
        } else if (t instanceof JTypeVar || t instanceof JArrayType) {
            return (JClassType) t.getAsSuper(sym);
        }
        return null;
    }

    private static final class AsSuperVisitor implements JTypeVisitor<@Nullable JTypeMirror, JClassSymbol> {

        static final AsSuperVisitor INSTANCE = new AsSuperVisitor();

        /** Parameter is the erasure of the target. */

        @Override
        public JTypeMirror visit(JTypeMirror t, JClassSymbol target) {
            return null;
        }

        @Override
        public JTypeMirror visitClass(JClassType t, JClassSymbol target) {
            if (target.equals(t.getSymbol())) {
                return t;
            }

            // prefer digging up the superclass first
            JClassType sup = t.getSuperClass();
            JClassType res = sup == null ? null : (JClassType) sup.acceptVisitor(this, target);
            if (res != null) {
                return res;
            } else {
                // then look in interfaces if possible
                if (target.isInterface()) {
                    return firstResult(target, t.getSuperInterfaces());
                }
            }

            return null;
        }

        @Override
        public JTypeMirror visitIntersection(JIntersectionType t, JClassSymbol target) {
            return firstResult(target, t.getComponents());
        }

        @Nullable
        public JTypeMirror firstResult(JClassSymbol target, List<? extends JTypeMirror> components) {
            for (JTypeMirror ci : components) {
                @Nullable JTypeMirror sup = ci.acceptVisitor(this, target);
                if (sup != null) {
                    return sup;
                }
            }
            return null;
        }

        @Override
        public JTypeMirror visitTypeVar(JTypeVar t, JClassSymbol target) {
            // caution, infinite recursion
            return t.getUpperBound().acceptVisitor(this, target);
        }

        @Override
        public JTypeMirror visitArray(JArrayType t, JClassSymbol target) {
            // Cloneable, Serializable, Object
            JTypeMirror decl = t.getTypeSystem().declaration(target);
            return t.isSubtypeOf(decl) ? decl : null;
        }
    }

    // </editor-fold>

    // <editor-fold  defaultstate="collapsed" desc="LUB/GLB">

    /**
     * Returns a subset S of the parameter, whose components have no
     * strict supertype in S.
     *
     * <pre>{@code
     * S = { V | V in set, and for all W ≠ V in set, it is not the case that W <: V }
     * }</pre>
     */
    public static Set<JTypeMirror> mostSpecific(Set<? extends JTypeMirror> set) {
        LinkedHashSet<JTypeMirror> result = new LinkedHashSet<>(set.size());
        vLoop:
        for (JTypeMirror v : set) {
            for (JTypeMirror w : set) {
                if (!w.equals(v) && w.isSubtypeOf(v, true)) {
                    continue vLoop;
                }
            }
            result.add(v);
        }
        return result;
    }

    // </editor-fold>

    /**
     * Returns the components of t if it is an intersection type,
     * otherwise returns t.
     */
    public static List<JTypeMirror> asList(JTypeMirror t) {
        if (t instanceof JIntersectionType) {
            return ((JIntersectionType) t).getComponents();
        } else {
            return Collections.singletonList(t);
        }
    }

    /** Returns a new list with the erasures of the given types. */
    public static List<JTypeMirror> erase(List<? extends JTypeMirror> ts) {
        List<JTypeMirror> res = new ArrayList<>(ts.size());
        for (JTypeMirror t : ts) {
            res.add(t.getErasure());
        }

        return res;
    }

    // <editor-fold  defaultstate="collapsed" desc="Mentions">


    public static boolean mentions(@NonNull JTypeVisitable type, @NonNull JInferenceVar parent) {
        try {
            return type.acceptVisitor(MentionsVisitor.IVAR_VISITOR, Collections.singleton(parent));
        } catch (StackOverflowError e) {
            return false;
        }
    }

    // for inference vars
    public static boolean mentionsAny(JTypeVisitable t, Collection<? extends JInferenceVar> vars) {
        return !vars.isEmpty() && t.acceptVisitor(MentionsVisitor.IVAR_VISITOR, vars);
    }

    public static boolean mentionsAnyTvar(JTypeVisitable t, Collection<? extends JTypeVar> vars) {
        return !vars.isEmpty() && t.acceptVisitor(MentionsVisitor.TVAR_VISITOR, vars);
    }


    private static final class MentionsVisitor implements JTypeVisitor<Boolean, Collection<? extends JTypeMirror>> {

        static final MentionsVisitor TVAR_VISITOR = new MentionsVisitor(true);
        static final MentionsVisitor IVAR_VISITOR = new MentionsVisitor(false);

        private final boolean isTvar;

        private MentionsVisitor(boolean isTvar) {
            this.isTvar = isTvar;
        }

        @Override
        public Boolean visit(JTypeMirror t, Collection<? extends JTypeMirror> targets) {
            return false;
        }

        @Override
        public Boolean visitTypeVar(JTypeVar t, Collection<? extends JTypeMirror> targets) {
            if (isTvar) {
                return targets.contains(t);
            }
            //t.getUpperBound().acceptVisitor(this, targets);
            // this causes infinite loops eg with types like the type var
            // <E extends Enum<E>>
            return false;
        }

        @Override
        public Boolean visitWildcard(JWildcardType t, Collection<? extends JTypeMirror> targets) {
            return t.getBound().acceptVisitor(this, targets);
        }

        @Override
        public Boolean visitInferenceVar(JInferenceVar t, Collection<? extends JTypeMirror> targets) {
            return !isTvar && targets.contains(t);
        }

        @Override
        public Boolean visitMethodType(JMethodSig t, Collection<? extends JTypeMirror> targets) {
            if (t.getReturnType().acceptVisitor(this, targets)) {
                return true;
            }
            for (JTypeMirror fi : t.getFormalParameters()) {
                if (fi.acceptVisitor(this, targets)) {
                    return true;
                }
            }
            for (JTypeMirror ti : t.getThrownExceptions()) {
                if (ti.acceptVisitor(this, targets)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Boolean visitClass(JClassType t, Collection<? extends JTypeMirror> targets) {
            JClassType encl = t.getEnclosingType();
            if (encl != null && encl.acceptVisitor(this, targets)) {
                return true;
            }

            for (JTypeMirror typeArg : t.getTypeArgs()) {
                if (typeArg.acceptVisitor(this, targets)) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public Boolean visitIntersection(JIntersectionType t, Collection<? extends JTypeMirror> targets) {
            for (JTypeMirror comp : t.getComponents()) {
                if (comp.acceptVisitor(this, targets)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Boolean visitArray(JArrayType t, Collection<? extends JTypeMirror> targets) {
            return t.getComponentType().acceptVisitor(this, targets);
        }
    }

    // </editor-fold>



    public static Predicate<JMethodSymbol> accessibleMethodFilter(String name, @NonNull JClassSymbol symbol) {
        return it -> it.getSimpleName().equals(name) && isAccessible(it, symbol);
    }

    public static Iterable<JMethodSig> lazyFilterAccessible(List<JMethodSig> visible, @NonNull JClassSymbol accessSite) {
        return () -> IteratorUtil.filter(visible.iterator(), it -> isAccessible(it.getSymbol(), accessSite));
    }

    public static List<JMethodSig> filterAccessible(List<JMethodSig> visible, @NonNull JClassSymbol accessSite) {
        return CollectionUtil.mapNotNull(visible, m -> isAccessible(m.getSymbol(), accessSite) ? m : null);
    }


    public static List<JMethodSig> getMethodsOf(JTypeMirror type, String name, boolean staticOnly, @NonNull JClassSymbol enclosing) {
        return type.streamMethods(
            it -> (!staticOnly || Modifier.isStatic(it.getModifiers()))
                && it.getSimpleName().equals(name)
                && isAccessible(it, enclosing)
        ).collect(OverloadComparator.collectMostSpecific(type));
    }

    private static boolean isAccessible(JExecutableSymbol method, JClassSymbol ctx) {
        Objects.requireNonNull(ctx, "Cannot check a null symbol");

        int mods = method.getModifiers();
        if (Modifier.isPublic(mods)) {
            return true;
        }

        JClassSymbol owner = method.getEnclosingClass();

        if (Modifier.isPrivate(mods)) {
            return ctx.getNestRoot().equals(owner.getNestRoot());
        } else if (owner instanceof JArrayType) {
            return true;
        }

        return ctx.getPackageName().equals(owner.getPackageName())
            // we can exclude interfaces because their members are all public
            || Modifier.isProtected(mods) && isSubClassOfNoInterface(ctx, owner);
    }

    private static boolean isSubClassOfNoInterface(JClassSymbol sub, JClassSymbol symbol) {
        if (symbol.equals(sub)) {
            return true;
        }

        JClassSymbol superclass = sub.getSuperclass();
        return superclass != null && isSubClassOfNoInterface(superclass, symbol);
    }

}
