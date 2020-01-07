/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.symbols.table.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.checkerframework.checker.nullness.qual.Nullable;

import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JMethodSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeDeclSymbol;
import net.sourceforge.pmd.lang.java.symbols.JValueSymbol;
import net.sourceforge.pmd.lang.java.symbols.internal.impl.reflect.ReflectSymInternals;
import net.sourceforge.pmd.lang.java.symbols.table.JSymbolTable;


/**
 * Implicit type imports from {@literal java.lang}.
 *
 * @since 7.0.0
 */
final class JavaLangSymbolTable extends AbstractSymbolTable {

    private static final Logger LOG = Logger.getLogger(SamePackageSymbolTable.class.getName());

    private static final Map<String, JTypeDeclSymbol> JAVA_8_LANG;
    private final Map<String, JTypeDeclSymbol> javaLangTypes;


    static {
        List<Class<?>> classes = Arrays.asList(
            // from a jdk8
            // these may differ from a jdk version to another.
            // Ideally we'd should have each LanguageVersionHandler store these I think
            java.lang.AbstractMethodError.class,
            java.lang.Appendable.class,
            java.lang.ArithmeticException.class,
            java.lang.ArrayIndexOutOfBoundsException.class,
            java.lang.ArrayStoreException.class,
            java.lang.AssertionError.class,
            java.lang.AutoCloseable.class,
            java.lang.Boolean.class,
            java.lang.BootstrapMethodError.class,
            java.lang.Byte.class,
            java.lang.Character.class,
            java.lang.CharSequence.class,
            java.lang.Class.class,
            java.lang.ClassCastException.class,
            java.lang.ClassCircularityError.class,
            java.lang.ClassFormatError.class,
            java.lang.ClassLoader.class,
            java.lang.ClassNotFoundException.class,
            java.lang.ClassValue.class,
            java.lang.Cloneable.class,
            java.lang.CloneNotSupportedException.class,
            java.lang.Comparable.class,
            java.lang.Compiler.class,
            java.lang.Deprecated.class,
            java.lang.Double.class,
            java.lang.Enum.class,
            java.lang.EnumConstantNotPresentException.class,
            java.lang.Error.class,
            java.lang.Exception.class,
            java.lang.ExceptionInInitializerError.class,
            java.lang.Float.class,
            java.lang.FunctionalInterface.class,
            java.lang.IllegalAccessError.class,
            java.lang.IllegalAccessException.class,
            java.lang.IllegalArgumentException.class,
            java.lang.IllegalMonitorStateException.class,
            java.lang.IllegalStateException.class,
            java.lang.IllegalThreadStateException.class,
            java.lang.IncompatibleClassChangeError.class,
            java.lang.IndexOutOfBoundsException.class,
            java.lang.InheritableThreadLocal.class,
            java.lang.InstantiationError.class,
            java.lang.InstantiationException.class,
            java.lang.Integer.class,
            java.lang.InternalError.class,
            java.lang.InterruptedException.class,
            java.lang.Iterable.class,
            java.lang.LinkageError.class,
            java.lang.Long.class,
            java.lang.Math.class,
            java.lang.NegativeArraySizeException.class,
            java.lang.NoClassDefFoundError.class,
            java.lang.NoSuchFieldError.class,
            java.lang.NoSuchFieldException.class,
            java.lang.NoSuchMethodError.class,
            java.lang.NoSuchMethodException.class,
            java.lang.NullPointerException.class,
            java.lang.Number.class,
            java.lang.NumberFormatException.class,
            java.lang.Object.class,
            java.lang.OutOfMemoryError.class,
            java.lang.Override.class,
            java.lang.Package.class,
            java.lang.Process.class,
            java.lang.ProcessBuilder.class,
            java.lang.Readable.class,
            java.lang.ReflectiveOperationException.class,
            java.lang.Runnable.class,
            java.lang.Runtime.class,
            java.lang.RuntimeException.class,
            java.lang.RuntimePermission.class,
            java.lang.SafeVarargs.class,
            java.lang.SecurityException.class,
            java.lang.SecurityManager.class,
            java.lang.Short.class,
            java.lang.StackOverflowError.class,
            java.lang.StackTraceElement.class,
            java.lang.StrictMath.class,
            java.lang.String.class,
            java.lang.StringBuffer.class,
            java.lang.StringBuilder.class,
            java.lang.StringIndexOutOfBoundsException.class,
            java.lang.SuppressWarnings.class,
            java.lang.System.class,
            java.lang.Thread.class,
            java.lang.ThreadDeath.class,
            java.lang.ThreadGroup.class,
            java.lang.ThreadLocal.class,
            java.lang.Throwable.class,
            java.lang.TypeNotPresentException.class,
            java.lang.UnknownError.class,
            java.lang.UnsatisfiedLinkError.class,
            java.lang.UnsupportedClassVersionError.class,
            java.lang.UnsupportedOperationException.class,
            java.lang.VerifyError.class,
            java.lang.VirtualMachineError.class,
            java.lang.Void.class
        );

        Map<String, JTypeDeclSymbol> theJavaLang = new HashMap<>();


        for (Class<?> aClass : classes) {

            JClassSymbol reference = ReflectSymInternals.createSharedSym(aClass);

            theJavaLang.put(aClass.getSimpleName(), reference);
            theJavaLang.put(aClass.getCanonicalName(), reference);
        }
        JAVA_8_LANG = Collections.unmodifiableMap(theJavaLang);
    }


    /**
     * Constructor with just the parent table.
     *
     * @param parent Parent table
     * @param helper Resolve helper
     */
    public JavaLangSymbolTable(JSymbolTable parent, SymbolTableResolveHelper helper) {
        super(parent, helper);
        this.javaLangTypes = getJavaLangForJdkVersion(helper.getJdkVersion());
    }


    @Override
    protected @Nullable JTypeDeclSymbol resolveTypeNameImpl(String simpleName) {
        return javaLangTypes.get(simpleName);
    }


    @Override
    protected Stream<JMethodSymbol> resolveMethodNameImpl(String simpleName) {
        return Stream.empty();
    }


    @Override
    protected @Nullable JValueSymbol resolveValueNameImpl(String simpleName) {
        return null;
    }


    @Override
    protected Logger getLogger() {
        return LOG;
    }


    @SuppressWarnings("PMD.UnusedFormalParameter")
    private static Map<String, JTypeDeclSymbol> getJavaLangForJdkVersion(int jdkVersion) {
        // TODO implement for other jdk versions
        return JAVA_8_LANG;
    }

}
