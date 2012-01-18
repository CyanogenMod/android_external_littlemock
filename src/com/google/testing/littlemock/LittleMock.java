/*
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.testing.littlemock;

import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Very lightweight and simple mocking framework, inspired by Mockito, http://mockito.org.
 *
 * <p>It supports only a <b>small subset</b> of the APIs provided by Mockito and other mocking
 * frameworks.
 *
 * <p>This project was originally designed to be lightweight and suitable for platforms that don't
 * support dynamic class generation, for example Android.  Since the release of the open
 * source dexmaker project http://code.google.com/p/dexmaker/ we can now mock concrete classes
 * too.
 *
 * <p>Here is an example of how to use the framework.
 *
 * <p>Suppose that we have this interface:
 * <pre>
 *     public interface Foo {
 *       public String aString(int input);
 *       public void doSomething();
 *     }
 * </pre>
 *
 * <p>Then stubbing out mocks works as follows:
 * <pre>
 *     Foo mockFoo = mock(Foo.class);  // Create the mock.
 *     doReturn("hello").when(mockFoo).aString(anyInt());  // Stub the mock to return "hello".
 *     doThrow(new IOException("oh noes")).when(mockFoo).doSomething();
 *     assertEquals("hello", mockFoo.aString(5));  // Use the mock - performs as expected.
 *     mockFoo.doSomething();  // This will throw an IOException.
 * </pre>
 *
 * <p>You can verify in the 'natural place', after the method call has happened, like this:
 * <pre>
 *     Foo mockFoo = mock(Foo.class);
 *     assertEquals(null, mockFoo.aString(6));  // Unstubbed method calls return a sensible default.
 *     verify(mockFoo).aString(6);  // This will pass, aString() was called once.
 *     verify(mockFoo, never()).doSomething();  // This will pass, doSomething was never called.
 *     verify(mockFoo, times(3)).aString(anyInt());  // This will fail, was called once only.
 * </pre>
 *
 * <p>The documentation is still incomplete.  You can look at the {@link LittleMockTest} class and
 * its unit tests - since they tell you exactly what operations are supported and how to use them.
 *
 * <p>The reasons I much prefer Mockito's approach to the one of EasyMock are as follows:
 * <ul>
 *   <li>No need to remember to put your mocks in replay mode.</li>
 *   <li>No need to remember to call verify, a dangerous source of false-positive tests or
 *   alternatively over-specified tests.</li>
 *   <li>Much less over-specification: only verify what you actually care about.</li>
 *   <li>Which in turn leads to better separated tests, each test verifying only a part of the
 *   desired behaviour.</li>
 *   <li>Which also leads to less fragile tests, where adding another method call on your
 *   dependencies doesn't break unrelated tests.</li>
 *   <li>Simpler sharing of common setup method with specific tests overridding individual
 *   behavious however they want to, only the most recent stubbed call is the one that counts.</li>
 *   <li>More natural order for tests: set up stubs, execute tests, verify that it worked.</li>
 *   <li>More unified syntax that doesn't require special case for differences between void method
 *   calls and method calls that return a value.</li>
 * </ul>
 *
 * <p>There were enough reasons that I wanted to give Mockito a try.  It didn't work on Android
 * because of issues with class generation.  So I looked at the documentation and examples page and
 * added tests for all the examples, and then implemented the this framework.  I should stress that
 * this is a clean-room implementation, and as such it's possible that there are a couple of methods
 * that don't work in the same way as Mockito's implementation.  Where that is the case I think we
 * should fix once we discover them.  There is also some functionality missing, but it will be added
 * in due course.
 *
 * <p>Over time, the API has diverged slightly from the one of Mockito, as I have added APIs that I
 * found convenient but that did not have an equivalent in Mockite itself.  For anything that has an
 * equivalent in Mockito I tried to keep the same name and syntax, to make it easier to transition
 * between using one framework to using the other, e.g., when developing both an Android application
 * using this framework and a desktop application using Mockito.
 *
 * @author hugohudson@gmail.com (Hugo Hudson)
 */
/*@NotThreadSafe*/
public class LittleMock {
  /** Generates a {@link Behaviour} suitable for void methods. */
  public static Behaviour doNothing() { return doReturn(null); }

  /** Generates a {@link Behaviour} that returns the given result. */
  public static <T> Behaviour doReturn(final T result) {
    return new BehaviourImpl(new Action() {
      @Override public T doAction(Method method, Object[] args) { return result; }
      @Override public Class<?> getReturnType() {
        return (result == null) ? null : result.getClass();
      }
    });
  }

  /**
   * Gets a {@link Behaviour} that will execute the given {@link Callable} and return its result.
   */
  public static <T> Behaviour doAnswer(final Callable<T> callable) {
    return new BehaviourImpl(new Action() {
      @Override
      public T doAction(Method method, Object[] args) throws Throwable { return callable.call(); }
      @Override
      public Class<?> getReturnType() { return null; }
    });
  }

  /** Returns a {@link Behaviour} that throws the given {@link Throwable}. */
  public static <T extends Throwable> Behaviour doThrow(final T exception) {
    return new BehaviourImpl(new Action() {
      @Override
      public Object doAction(Method method, Object[] args) throws Throwable { throw exception; }
      @Override
      public Class<?> getReturnType() { return null; }
    });
  }

  /** Begins a verification step on a mock: the next method invocation on that mock will verify. */
  public static <T> T verify(T mock, CallCount howManyTimes) {
    if (howManyTimes == null) {
      throw new IllegalArgumentException("Can't pass null for howManyTimes parameter");
    }
    DefaultInvocationHandler handler = getHandlerFrom(mock);
    checkState(handler.mHowManyTimes == null, "Unfinished verify() statements");
    checkState(handler.mStubbingAction == null, "Unfinished stubbing statements");
    checkNoMatchers();
    handler.mHowManyTimes = howManyTimes;
    sUnfinishedCallCounts.add(howManyTimes);
    return handler.<T>getVerifyingMock();
  }

  /** The list of outstanding calls to verify() that haven't finished, used to check for errors. */
  private static List<CallCount> sUnfinishedCallCounts = new ArrayList<CallCount>();

  /** The list of outstanding calls to when() that haven't finished, used to check for errors. */
  private static List<Action> sUnfinishedStubbingActions = new ArrayList<Action>();

  /** Begins a verification step for exactly one method call. */
  public static <T> T verify(T mock) { return verify(mock, times(1)); }

  /** Assert that no method calls at all happened on these mocks. */
  public static void verifyZeroInteractions(Object... mocks) {
    checkNoMatchers();
    for (Object mock : mocks) {
      List<MethodCall> mMethodCalls = getHandlerFrom(mock).mRecordedCalls;
      expect(mMethodCalls.isEmpty(), "Mock expected zero interactions, had " + mMethodCalls);
    }
  }

  /** Assert that there are no unverified method calls on these mocks. */
  public static void verifyNoMoreInteractions(Object... mocks) {
    StackTraceElement callSite = new Exception().getStackTrace()[1];
    for (Object mock : mocks) {
      verifyNoMoreInteractions(mock, callSite);
    }
  }

  /** Check that there are no unverified actions on the given mock. */
  private static void verifyNoMoreInteractions(Object mock, StackTraceElement callSite) {
    checkNoMatchers();
    DefaultInvocationHandler handlerFrom = getHandlerFrom(mock);
    List<MethodCall> unverifiedCalls = new ArrayList<MethodCall>();
    for (MethodCall method : handlerFrom.mRecordedCalls) {
      if (!method.mWasVerified) {
        unverifiedCalls.add(method);
      }
    }
    if (unverifiedCalls.size() > 0) {
      StringBuffer sb = new StringBuffer();
      sb.append("\nWe found these unverified calls:");
      for (MethodCall method : unverifiedCalls) {
        appendDebugStringForMethodCall(sb, method.mMethod,
            method.mElement, handlerFrom.mFieldName, false);
      }
      sb.append("\n\nAfter final interaction was verified:\n");
      sb.append("  at ").append(callSite).append("\n");
      throw new AssertionError(sb.toString());
    }
  }

  /** Creates a {@link CallCount} that matches exactly the given number of calls. */
  public static CallCount times(long n) { return new CallCount(n, n); }

  /** Claims that the verified call must happen before the given timeout. */
  public static Timeout timeout(long timeoutMillis) {
    return new Timeout(1, 1, timeoutMillis);
  }

/** Creates a {@link CallCount} that only matches if the method was never called. */
  public static CallCount never() { return new CallCount(0, 0); }

  /** Creates a {@link CallCount} that matches at least one method call. */
  public static CallCount atLeastOnce() { return new CallCount(1, Long.MAX_VALUE); }

  /** Creates a {@link CallCount} that matches any number of method calls, including none at all. */
  public static CallCount anyTimes() { return new CallCount(0, Long.MAX_VALUE); }

  /** Creates a {@link CallCount} that matches at least the given number of calls. */
  public static CallCount atLeast(long n) { return new CallCount(n, Long.MAX_VALUE); }

  /** Creates a {@link CallCount} that matches up to the given number of calls but no more. */
  public static CallCount atMost(long n) { return new CallCount(0, n); }

  /** Creates a {@link CallCount} that matches any number of calls between the two given bounds. */
  public static CallCount between(long lower, long upper) { return new CallCount(lower, upper); }

  /**
   * Creates an argument matcher that matches any object, don't use for primitives.
   * <p>
   * <b>Note</b>: This does not enforce that the object is of type {@code T}; use
   * {@link #isA(Class)} to do that.
   */
  public static <T> T anyObject() { return LittleMock.<T>addMatcher(new MatchAnything(), null); }

  /** Generates an argument matcher that matches any string. */
  public static String anyString() { return isA(String.class); }

  /** Generates an argument matcher that matches any int. */
  public static int anyInt() { return addMatcher(new MatchAnything(), 0); }

  /** Generates an argument matcher that matches any float. */
  public static float anyFloat() { return addMatcher(new MatchAnything(), 0f); }

  /** Generates an argument matcher that matches any double. */
  public static double anyDouble() { return addMatcher(new MatchAnything(), 0.0); }

  /** Generates an argument matcher that matches any boolean. */
  public static boolean anyBoolean() { return addMatcher(new MatchAnything(), false); }

  /** Generates an argument matcher that matches any short. */
  public static short anyShort() { return addMatcher(new MatchAnything(), (short) 0); }

  /** Generates an argument matcher that matches any char. */
  public static char anyChar() { return addMatcher(new MatchAnything(), '\u0000'); }

  /** Generates an argument matcher that matches any long. */
  public static long anyLong() { return addMatcher(new MatchAnything(), 0L); }

  /** Generates an argument matcher that matches any byte. */
  public static byte anyByte() { return addMatcher(new MatchAnything(), (byte) 0); }

  /** Generates an argument matcher that matches exactly this value. */
  public static <T> T eq(final T expected) {
    return addMatcher(new ArgumentMatcher() {
      @Override
      public boolean matches(Object value) {
        return (expected == null) ? (value == null) : expected.equals(value);
      }
    }, expected);
  }

  /** An argument matcher that matches any value of the given type or a subtype thereof. */
  public static <T> T isA(final Class<T> clazz) {
    return LittleMock.<T>addMatcher(new ArgumentMatcher() {
      @Override
      public boolean matches(Object value) {
        return value == null || clazz.isAssignableFrom(value.getClass());
      }
    }, null);
  }

  /**
   * Injects fields annotated with {@link Mock} with a newly created mock, and those
   * annotated with {@link Captor} with a suitable capture object.
   *
   * <p>This operation is recursive, and travels up the class hierarchy, in order to set all
   * suitably annotated fields.
   */
  public static void initMocks(Object instance) throws Exception {
    injectMocksForClass(instance, instance.getClass());
  }

  /** Recurse up the class hierarchy injecting mocks as we go, stopping when we reach Object. */
  private static void injectMocksForClass(Object instance, Class<?> clazz)
      throws Exception {
    if (clazz.equals(Object.class)) {
      return;
    }
    for (Field field : clazz.getDeclaredFields()) {
      if (field.getAnnotation(Mock.class) != null) {
        setField(field, instance, mock(field.getType(), field.getName()));
      } else if (field.getAnnotation(Captor.class) != null) {
        setField(field, instance, createCaptor());
      }
    }
    injectMocksForClass(instance, clazz.getSuperclass());
  }

  /**
   * Creates a correctly typed {@link ArgumentCaptor} , it's easier to use
   * {@link #initMocks(Object)}.
   */
  public static <T> ArgumentCaptor<T> createCaptor() {
    return new ArgumentCaptorImpl<T>();
  }

  /** Implementation of the {@link ArgumentCaptor} interface. */
  private static class ArgumentCaptorImpl<T extends Object> implements ArgumentCaptor<T> {
    private final ArrayList<T> mAllValues = new ArrayList<T>();
    private T mValue;

    private ArgumentCaptorImpl() {
    }

    public void setValue(T value) {
      mValue = value;
      mAllValues.add(mValue);
    }

    @Override
    public T getValue() {
      return mValue;
    }

    @Override
    public List<T> getAllValues() {
      return mAllValues;
    }

    @Override
    public T capture() {
      return LittleMock.<T>addMatcher(this, null);
    }

    @Override
    public boolean matches(Object value) {
      // A capture always matches any argument.
      // This is so that verify(mMock).someMethod(capture(mCapture)) will match any and all calls
      // to someMethod() and we will capture the values into mCapture.
      return true;
    }
  }

  /**
   * Creates a mock, more easily done via the {@link #initMocks(Object)} method.
   *
   * <p>Also if you use this method to create your mock, the field in the error messages will
   * be named the same as your class passed in, you only get the actual field name if you
   * use the annotation.
   *
   * @throws IllegalArgumentException if the class you pass in is null
   */
  public static <T> T mock(Class<T> clazz) {
    if (clazz == null) {
      throw new IllegalArgumentException("Class to mock was null");
    }
    return mock(clazz, getDefaultFieldNameFor(clazz));
  }

  /** Creates a mock, more easily done via the {@link #initMocks(Object)} method. */
  @SuppressWarnings("unchecked")
  private static <T> T mock(Class<T> clazz, String fieldName) {
    return (T) createProxy(clazz, new DefaultInvocationHandler(clazz, fieldName));
  }

  /** Pick a suitable name for a field of the given clazz. */
  private static String getDefaultFieldNameFor(Class<?> clazz) {
    return clazz.getSimpleName().substring(0, 1).toLowerCase()
        + clazz.getSimpleName().substring(1);
  }

  /** Clears out the expectations on these mocks. */
  public static void reset(Object... mocks) {
    for (Object mock : mocks) {
      getHandlerFrom(mock).reset();
    }
  }

  /** Use this in tear down to check for programming errors. */
  public static void checkForProgrammingErrorsDuringTearDown() {
    checkNoMatchers();
    checkNoUnfinishedCalls(sUnfinishedCallCounts, "verify()");
    checkNoUnfinishedCalls(sUnfinishedStubbingActions, "stubbing");
  }

  /** Helper function to check that there are no verify or stubbing commands outstanding. */
  private static void checkNoUnfinishedCalls(List<?> list, String type) {
    if (!list.isEmpty()) {
      list.clear();
      throw new IllegalStateException("Unfinished " + type + " statements");
    }
  }

  /** Implementation of {@link Behaviour}. */
  private static class BehaviourImpl implements Behaviour {
    private final Action mAction;

    private BehaviourImpl(Action action) {
      mAction = action;
    }

    @Override
    public <T> T when(T mock) {
      DefaultInvocationHandler handler = getHandlerFrom(mock);
      checkState(handler.mHowManyTimes == null, "Unfinished verify() statements");
      checkState(handler.mStubbingAction == null, "Unfinished stubbing statements");
      handler.mStubbingAction = mAction;
      sUnfinishedStubbingActions.add(mAction);
      return handler.<T>getStubbingMock();
    }
  }

  /**
   * The static list of argument matchers, used in the next method call to the mock.
   *
   * <p>In order to support the syntax like this: verify(mFoo).someMethod(anyInt()), it is
   * required that the anyInt() method store the value somewhere for use when the someMethod
   * is invoked.  That somewhere has to be static.  I don't like it any more than you do.
   *
   * <p>The same goes for anything that is passed into the someMethod as an argument - be it
   * a capture(mCaptureString) or eq(5) or whatever.
   *
   * <p>Avoiding the use of statics requires that we change the syntax of the verify statement,
   * and I can't think of an elegant way of doing it, and in any case I really like the current
   * syntax, so for now a static variable it is.
   *
   * <p>This match arguments list should contain either zero elements (the next method call will
   * not use any argument matchers) or it should contain exactly one argument matcher for
   * every argument being passed to the next method call.  You can't mix argument matchers and
   * raw values.
   */
  private static final List<ArgumentMatcher> sMatchArguments = new ArrayList<ArgumentMatcher>();

  /** Encapsulates a single call of a method with associated arguments. */
  private static class MethodCall {
    /** The method call. */
    private final Method mMethod;
    /** The arguments provided at the time the call happened. */
    private final Object[] mArgs;
    /** The line from the test that invoked the handler to create this method call. */
    private final StackTraceElement mElement;
    /** Keeps track of method calls that have been verified, for verifyNoMoreInteractions(). */
    public boolean mWasVerified = false;

    public MethodCall(Method method, StackTraceElement element, Object[] args) {
      mMethod = method;
      mElement = element;
      mArgs = args;
    }

    public boolean argsMatch(Object[] args) {
      return Arrays.equals(mArgs, args);
    }

    @Override
    public String toString() {
      return "MethodCall [method=" + mMethod + ", args=" + Arrays.toString(mArgs) + "]";
    }
  }

  /**
   * Magically handles the invoking of method calls.
   *
   * <p>This object is in one of three states, default (where invoking methods returns default
   * values and records the call), verifying (where invoking method calls makes sure that those
   * method calls happen with the supplied arguments or matchers) or stubbing (where the next method
   * call tells us which arguments to match in order to perform the desired behaviour).
   */
  private static class DefaultInvocationHandler implements InvocationHandler {
    private static Method sEqualsMethod;
    private static Method sHashCodeMethod;
    private static Method sToStringMethod;

    static {
      try {
        sEqualsMethod = Object.class.getMethod("equals", Object.class);
        sHashCodeMethod = Object.class.getMethod("hashCode");
        sToStringMethod = Object.class.getMethod("toString");
      } catch (SecurityException e) {
        // Should never happen.
        throw new RuntimeException("Your JVM/classloader is broken", e);
      } catch (NoSuchMethodException e) {
        // Should never happen.
        throw new RuntimeException("Your JVM/classloader is broken", e);
      }
    }

    /** The class of the mocked objects. */
    private final Class<?> mClazz;
    /** The field name in which the mock is assigned. */
    private final String mFieldName;

    /** The list of method calls executed on the mock. */
    private List<MethodCall> mRecordedCalls = new CopyOnWriteArrayList<MethodCall>();
    /** The list of method calls that were stubbed out and their corresponding actions. */
    private List<StubbedCall> mStubbedCalls = new CopyOnWriteArrayList<StubbedCall>();

    /**
     * The number of times a given call should be verified.
     *
     * <p>It is not null when in the verification state, and it is actually used to determine if we
     * are in the verification state.
     *
     * <p>It is reset to null once the verification has occurred.
     */
    private CallCount mHowManyTimes = null;

    /**
     * The action to be associated with the stubbed method.
     *
     * <p>It is not null when in the stubbing state, and it is actually used to determine if we are
     * in the stubbing state.
     */
    private Action mStubbingAction = null;

    /** Dynamic proxy used to verify calls against this mock. */
    private final Object mVerifyingMock;

    /** Dynamic proxy used to stub calls against this mock. */
    private final Object mStubbingMock;

    /**
     * Creates a new invocation handler for an object.
     *
     * @param clazz the class the object belongs to
     * @param fieldName The name to be used to refer to the object. This may either be the name of
     *        the field this mock will be stored into (in case it uses @Mock) or a suitable name to
     *        use to refer to the object in error messages, based on the name of the class itself.
     */
    public DefaultInvocationHandler(Class<?> clazz, String fieldName) {
      mClazz = clazz;
      mFieldName = fieldName;
      mVerifyingMock = createVerifyingMock(clazz);
      mStubbingMock = createStubbingMock(clazz);
    }

    // Safe if you call getHandlerFrom(mock).getVerifyingMock(), since this is guaranteed to be
    // of the same type as mock itself.
    @SuppressWarnings("unchecked")
    public <T> T getVerifyingMock() {
      return (T) mVerifyingMock;
    }

    // Safe if you call getHandlerFrom(mock).getStubbingMock(), since this is guaranteed to be
    // of the same type as mock itself.
    @SuppressWarnings("unchecked")
    public <T> T getStubbingMock() {
      return (T) mStubbingMock;
    }

    /** Used to check that we always stub and verify from the same thread. */
    private AtomicReference<Thread> mCurrentThread = new AtomicReference<Thread>();

    /** Check that we are stubbing and verifying always from the same thread. */
    private void checkThread() {
      Thread currentThread = Thread.currentThread();
      mCurrentThread.compareAndSet(null, currentThread);
      if (mCurrentThread.get() != currentThread) {
        throw new IllegalStateException("Must always mock and stub from one thread only.  "
            + "This thread: " + currentThread + ", the other thread: " + mCurrentThread.get());
      }
    }

    /** Generate the dynamic proxy that will handle verify invocations. */
    private Object createVerifyingMock(Class<?> clazz) {
      return createProxy(clazz, new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
          checkThread();
          expect(mHowManyTimes != null, "verifying mock doesn't know how many times");
          try {
            ArgumentMatcher[] matchers = checkClearAndGetMatchers(method);
            StackTraceElement callSite = new Exception().getStackTrace()[2];
            MethodCall methodCall = new MethodCall(method, callSite, args);
            innerVerify(method, matchers, methodCall, proxy, callSite, mHowManyTimes);
            return defaultReturnValue(method.getReturnType());
          } finally {
            sUnfinishedCallCounts.remove(mHowManyTimes);
            mHowManyTimes = null;
          }
        }
      });
    }

    /** Generate the dynamic proxy that will handle stubbing invocations. */
    private Object createStubbingMock(Class<?> clazz) {
      return createProxy(clazz, new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
          checkThread();
          expect(mStubbingAction != null, "stubbing mock doesn't know what action to perform");
          try {
            ArgumentMatcher[] matchers = checkClearAndGetMatchers(method);
            StackTraceElement callSite = new Exception().getStackTrace()[2];
            MethodCall methodCall = new MethodCall(method, callSite, args);
            innerStub(method, matchers, methodCall, callSite, mStubbingAction);
            return defaultReturnValue(method.getReturnType());
          } finally {
            sUnfinishedStubbingActions.remove(mStubbingAction);
            mStubbingAction = null;
          }
        }
      });
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      StackTraceElement callSite = new Exception().getStackTrace()[2];
      MethodCall methodCall = new MethodCall(method, callSite, args);
      return innerRecord(method, args, methodCall, proxy, callSite);
    }

    /**
     * Checks whether the given method is one of the special object methods that should not
     * verified or stubbed.
     * <p>
     * If this is one of such methods, it throws an AssertionException.
     *
     * @param method the method to be checked
     * @param operation the name of the operation, used for generating a helpful message
     */
    private void checkSpecialObjectMethods(Method method, String operation) {
      if (method.equals(sEqualsMethod)
          || method.equals(sHashCodeMethod)
          || method.equals(sToStringMethod)) {
        fail("cannot " + operation + " call to " + method);
      }
    }

    private void reset() {
      mRecordedCalls.clear();
      mStubbedCalls.clear();
      mHowManyTimes = null;
      mStubbingAction = null;
    }

    private Object innerRecord(Method method, final Object[] args,
            MethodCall methodCall, Object proxy, StackTraceElement callSite) throws Throwable {
      if (method.equals(sEqualsMethod)) {
        // Use identify for equality, the default behavior on object.
        return proxy == args[0];
      } else if (method.equals(sHashCodeMethod)) {
        // This depends on the fact that each mock has its own DefaultInvocationHandler.
        return hashCode();
      } else if (method.equals(sToStringMethod)) {
        // This is used to identify this is a mock, e.g., in error messages.
        return "Mock<" + mClazz.getName() + ">";
      }
      mRecordedCalls.add(methodCall);
      for (StubbedCall stubbedCall : mStubbedCalls) {
        if (stubbedCall.mMethodCall.mMethod.equals(methodCall.mMethod)) {
          if (stubbedCall.mMethodCall.argsMatch(methodCall.mArgs)) {
            methodCall.mWasVerified = true;
            return stubbedCall.mAction.doAction(method, args);
          }
        }
      }
      // If no stub is defined, return the default value.
      return defaultReturnValue(method.getReturnType());
    }

    private void innerStub(Method method, final ArgumentMatcher[] matchers, MethodCall methodCall,
        StackTraceElement callSite, final Action stubbingAction) {
      checkSpecialObjectMethods(method, "stub");
      checkThisActionCanBeUsedForThisMethod(method, stubbingAction, callSite);
      if (matchers.length == 0) {
        // If there are no matchers, then this is a simple stubbed method call with an action.
        mStubbedCalls.add(0, new StubbedCall(methodCall, stubbingAction));
        return;
      }
      // If there are matchers, then we need to make a new method call which matches only
      // when all the matchers match.  Further, the action that method call needs to take
      // is to first record the values into any captures that may be present, and only then
      // proceed to execute the original action.
      MethodCall matchMatchersMethodCall = new MethodCall(method, callSite, matchers) {
        @Override
        public boolean argsMatch(Object[] args) { return doMatchersMatch(matchers, args); }
      };
      Action setCapturesThenAction = new Action() {
        @Override
        public Object doAction(Method innerMethod, Object[] innerArgs) throws Throwable {
          setCaptures(matchers, innerArgs);
          return stubbingAction.doAction(innerMethod, innerArgs);
        }
        @Override
        public Class<?> getReturnType() {
          return stubbingAction.getReturnType();
        }
      };
      mStubbedCalls.add(0, new StubbedCall(matchMatchersMethodCall, setCapturesThenAction));
    }

    private void checkThisActionCanBeUsedForThisMethod(Method method, final Action stubbingAction,
        StackTraceElement callSite) {
      Class<?> methodType = method.getReturnType();
      Class<?> actionType = stubbingAction.getReturnType();
      if (actionType == null) {
        // We could not determine the type returned by this action at the time we
        // created it. At this time we cannot check that the returned value is
        // appropriate to the return type of the method.
        // However, if the type is not correct, any actual invocation of the method
        // will fail later on.
        return;
      }
      if (!methodType.isAssignableFrom(actionType)) {
        if (methodType.isPrimitive() &&
            actionType.equals(PRIMITIVE_TO_BOXED_LOOKUP.get(methodType))) {
          return;
        }
        StringBuffer sb = new StringBuffer();
        sb.append("\nCan't return ").append(actionType.getSimpleName()).append(" from stub for:");
        appendDebugStringForMethodCall(sb, method, callSite, mFieldName, true);
        sb.append("\n");
        throw new IllegalArgumentException(sb.toString());
      }
    }

    private boolean doMatchersMatch(ArgumentMatcher[] matchers, Object[] args) {
      for (int i = 0; i < matchers.length; ++i) {
        if (!matchers[i].matches(args[i])) {
          return false;
        }
      }
      return true;
    }

    private void innerVerify(Method method, ArgumentMatcher[] matchers, MethodCall methodCall,
        Object proxy, StackTraceElement callSite, CallCount callCount) {
      checkSpecialObjectMethods(method, "verify");
      int total = countMatchingInvocations(method, matchers, methodCall);
      long callTimeout = callCount.getTimeout();
      if (callTimeout > 0) {
        long endTime = System.currentTimeMillis() + callTimeout;
        while (!callCount.matches(total)) {
          try {
            Thread.sleep(1);
          } catch (InterruptedException e) {
            fail("interrupted whilst waiting to verify");
          }
          if (System.currentTimeMillis() > endTime) {
            fail(formatFailedVerifyMessage(methodCall, total, callTimeout, callCount));
          }
          total = countMatchingInvocations(method, matchers, methodCall);
        }
      } else {
        if (!callCount.matches(total)) {
          fail(formatFailedVerifyMessage(methodCall, total, 0, callCount));
        }
      }
    }

    private int countMatchingInvocations(Method method, ArgumentMatcher[] matchers,
        MethodCall methodCall) {
      int total = 0;
      for (MethodCall call : mRecordedCalls) {
        if (call.mMethod.equals(method)) {
          if ((matchers.length > 0 && doMatchersMatch(matchers, call.mArgs)) ||
              call.argsMatch(methodCall.mArgs)) {
            setCaptures(matchers, call.mArgs);
            ++total;
            call.mWasVerified  = true;
          }
        }
      }
      return total;
    }

    private String formatFailedVerifyMessage(MethodCall methodCall, int total, long timeoutMillis,
        CallCount callCount) {
      StringBuffer sb = new StringBuffer();
      sb.append("\nExpected ").append(callCount);
      if (timeoutMillis > 0) {
        sb.append(" within " + timeoutMillis + "ms");
      }
      sb.append(" to:");
      appendDebugStringForMethodCall(sb, methodCall.mMethod,
          methodCall.mElement, mFieldName, false);
      sb.append("\n\n");
      if (mRecordedCalls.size() == 0) {
        sb.append("No method calls happened on this mock");
      } else {
        sb.append("Method calls that did happen:");
        for (MethodCall recordedCall : mRecordedCalls) {
          appendDebugStringForMethodCall(sb, recordedCall.mMethod,
              recordedCall.mElement, mFieldName, false);
        }
      }
      sb.append("\n");
      return sb.toString();
    }

    /** All matchers that are captures will store the corresponding arg value. */
    // This suppress warning means that I'm calling setValue with something that I can't promise
    // is of the right type.  But I think it is unavoidable.  Certainly I could give a better
    // error message than the class cast exception you'll get when you try to retrieve the value.
    @SuppressWarnings("unchecked")
    private void setCaptures(ArgumentMatcher[] matchers, Object[] args) {
      for (int i = 0; i < matchers.length; ++i) {
        if (matchers[i] instanceof ArgumentCaptorImpl) {
          ArgumentCaptorImpl.class.cast(matchers[i]).setValue(args[i]);
        }
      }
    }

    /** An empty array of matchers, to optimise the toArray() call below. */
    private static final ArgumentMatcher[] EMPTY_MATCHERS_ARRAY = new ArgumentMatcher[0];

    /** Makes sure that we have the right number of MATCH_ARGUMENTS for the given method. */
    private ArgumentMatcher[] checkClearAndGetMatchers(Method method) {
      ArgumentMatcher[] matchers = sMatchArguments.toArray(EMPTY_MATCHERS_ARRAY);
      sMatchArguments.clear();
      if (matchers.length > 0 && method.getParameterTypes().length != matchers.length) {
        throw new IllegalArgumentException("You can't mix matchers and regular objects.");
      }
      return matchers;
    }
  }

  private static void appendDebugStringForMethodCall(StringBuffer sb, Method method,
      StackTraceElement callSite, String fieldName, boolean showReturnType) {
    sb.append("\n  ");
    if (showReturnType) {
      sb.append("(").append(method.getReturnType().getSimpleName()).append(") ");
    }
    sb.append(fieldName).append(".").append(method.getName()).append("(");
    int i = 0;
    for (Class<?> type : method.getParameterTypes()) {
      if (++i > 1) {
        sb.append(", ");
      }
      sb.append(type.getSimpleName());
    }
    sb.append(")\n  at ").append(callSite);
  }

  /** Call this function when you don't expect there to be any outstanding matcher objects. */
  private static void checkNoMatchers() {
    if (sMatchArguments.size() > 0) {
      sMatchArguments.clear();
      throw new IllegalStateException("You have outstanding matchers, must be programming error");
    }
  }

  /** A pairing of a method call and an action to be performed when that call happens. */
  private static class StubbedCall {
    private final MethodCall mMethodCall;
    private final Action mAction;

    public StubbedCall(MethodCall methodCall, Action action) {
      mMethodCall = methodCall;
      mAction = action;
    }

    @Override
    public String toString() {
      return "StubbedCall [methodCall=" + mMethodCall + ", action=" + mAction + "]";
    }
  }

  /** Represents an action to be performed as a result of a method call. */
  private interface Action {
    public Object doAction(Method method, Object[] arguments) throws Throwable;
    /** The type of the action, or null if we can't determine the type at stub time. */
    public Class<?> getReturnType();
  }

  /** Represents something capable of testing if it matches an argument or not. */
  /*package*/ interface ArgumentMatcher {
    public boolean matches(Object value);
  }

  /** A matcher that matches any argument. */
  private static class MatchAnything implements ArgumentMatcher {
    @Override
    public boolean matches(Object value) { return true; }
  }

  /** Encapsulates the number of times a method is called, between upper and lower bounds. */
  private static class CallCount {
    private long mLowerBound;
    private long mUpperBound;

    public CallCount(long lowerBound, long upperBound) {
      mLowerBound = lowerBound;
      mUpperBound = upperBound;
    }

    /** Tells us if this call count matches a desired count. */
    public boolean matches(long total) {
      return total >= mLowerBound && total <= mUpperBound;
    }

    /** Tells us how long we should block waiting for the verify to happen. */
    public long getTimeout() {
      return 0;
    }

    public CallCount setLowerBound(long lowerBound) {
      mLowerBound = lowerBound;
      return this;
    }

    public CallCount setUpperBound(long upperBound) {
      mUpperBound = upperBound;
      return this;
    }

    @Override
    public String toString() {
      if (mLowerBound == mUpperBound) {
        return "exactly " + mLowerBound + plural(" call", mLowerBound);
      } else {
        return "between " + mLowerBound + plural(" call", mLowerBound) + " and " +
            mUpperBound + plural(" call", mUpperBound);
      }
    }
  }

  /** Encapsulates adding number of times behaviour to a call count with timeout. */
  public static final class Timeout extends CallCount {
    private long mTimeoutMillis;

    public Timeout(long lowerBound, long upperBound, long timeoutMillis) {
      super(lowerBound, upperBound);
      mTimeoutMillis = timeoutMillis;
    }

    @Override
    public long getTimeout() {
      return mTimeoutMillis;
    }

    public CallCount times(int times) { return setLowerBound(times).setUpperBound(times); }
    public CallCount atLeast(long n) { return setLowerBound(n).setUpperBound(Long.MAX_VALUE); }
    public CallCount atLeastOnce() { return setLowerBound(1).setUpperBound(Long.MAX_VALUE); }
    public CallCount between(long n, long m) { return setLowerBound(n).setUpperBound(m); }
  }

  /** Helper method to add an 's' to a string iff the count is not 1. */
  private static String plural(String prefix, long count) {
    return (count == 1) ? prefix : (prefix + "s");
  }

  /** Helps us implement the eq(), any() and capture() and other methods on one line. */
  private static <T> T addMatcher(ArgumentMatcher argument, T value) {
    sMatchArguments.add(argument);
    return value;
  }

  /** Utility method to throw an AssertionError if an assertion fails. */
  private static void expect(boolean result, String message) {
    if (!result) {
      fail(message);
    }
  }

  /** Throws an AssertionError exception with a message. */
  private static void fail(String message) {
    throw new AssertionError(message);
  }

  /** Static mapping from class type to default value for that type. */
  private static final Map<Class<?>, Object> DEFAULT_RETURN_VALUE_LOOKUP;
  static {
    DEFAULT_RETURN_VALUE_LOOKUP = new HashMap<Class<?>, Object>();
    DEFAULT_RETURN_VALUE_LOOKUP.put(int.class, 0);
    DEFAULT_RETURN_VALUE_LOOKUP.put(boolean.class, false);
    DEFAULT_RETURN_VALUE_LOOKUP.put(byte.class, (byte) 0);
    DEFAULT_RETURN_VALUE_LOOKUP.put(long.class, (long) 0);
    DEFAULT_RETURN_VALUE_LOOKUP.put(short.class, (short) 0);
    DEFAULT_RETURN_VALUE_LOOKUP.put(float.class, (float) 0);
    DEFAULT_RETURN_VALUE_LOOKUP.put(double.class, (double) 0);
    DEFAULT_RETURN_VALUE_LOOKUP.put(char.class, '\u0000');
    DEFAULT_RETURN_VALUE_LOOKUP.put(String.class, null);
  }

  /** Static lookup from primitive types to their boxed versions. */
  private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_BOXED_LOOKUP;
  static {
    PRIMITIVE_TO_BOXED_LOOKUP = new HashMap<Class<?>, Class<?>>();
    PRIMITIVE_TO_BOXED_LOOKUP.put(int.class, Integer.class);
    PRIMITIVE_TO_BOXED_LOOKUP.put(boolean.class, Boolean.class);
    PRIMITIVE_TO_BOXED_LOOKUP.put(byte.class, Byte.class);
    PRIMITIVE_TO_BOXED_LOOKUP.put(long.class, Long.class);
    PRIMITIVE_TO_BOXED_LOOKUP.put(short.class, Short.class);
    PRIMITIVE_TO_BOXED_LOOKUP.put(float.class, Float.class);
    PRIMITIVE_TO_BOXED_LOOKUP.put(double.class, Double.class);
    PRIMITIVE_TO_BOXED_LOOKUP.put(char.class, Character.class);
  }

  /** For a given class type, returns the default value for that type. */
  private static Object defaultReturnValue(Class<?> returnType) {
    return DEFAULT_RETURN_VALUE_LOOKUP.get(returnType);
  }

  /** Gets a suitable class loader for use with the proxy. */
  private static ClassLoader getClassLoader() {
    return LittleMock.class.getClassLoader();
  }

  /** Sets a member field on an object via reflection (can set private members too). */
  private static void setField(Field field, Object object, Object value) throws Exception {
    field.setAccessible(true);
    field.set(object, value);
    field.setAccessible(false);
  }

  /** Helper method to throw an IllegalStateException if given condition is not met. */
  private static void checkState(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  /**
   * If the input object is one of our mocks, returns the {@link DefaultInvocationHandler}
   * we constructed it with.  Otherwise fails with {@link IllegalArgumentException}.
   */
  private static DefaultInvocationHandler getHandlerFrom(Object mock) {
    try {
      InvocationHandler invocationHandler = Proxy.getInvocationHandler(mock);
      if (invocationHandler instanceof DefaultInvocationHandler) {
        return (DefaultInvocationHandler) invocationHandler;
      }
    } catch (IllegalArgumentException expectedIfNotAProxy) {}
    try {
      Class<?> proxyBuilder = Class.forName("com.google.dexmaker.stock.ProxyBuilder");
      Method getHandlerMethod = proxyBuilder.getMethod("getInvocationHandler", Object.class);
      Object invocationHandler = getHandlerMethod.invoke(proxyBuilder, mock);
      if (invocationHandler instanceof DefaultInvocationHandler) {
        return (DefaultInvocationHandler) invocationHandler;
      }
    } catch (Exception expectedIfNotAProxyBuilderMock) {}
    throw new IllegalArgumentException("not a valid mock: " + mock);
  }

  /** Create a dynamic proxy for the given class, delegating to the given invocation handler. */
  private static Object createProxy(Class<?> clazz, InvocationHandler handler) {
    if (clazz.isInterface()) {
      return Proxy.newProxyInstance(getClassLoader(), new Class<?>[] { clazz }, handler);
    }
    try {
      Class<?> proxyBuilder = Class.forName("com.google.dexmaker.stock.ProxyBuilder");
      Method forClassMethod = proxyBuilder.getMethod("forClass", Class.class);
      Object builder = forClassMethod.invoke(null, clazz);
      Method dexCacheMethod = builder.getClass().getMethod("dexCache", File.class);
      File directory = AppDataDirGuesser.getsInstance().guessSuitableDirectoryForGeneratedClasses();
      builder = dexCacheMethod.invoke(builder, directory);
      Method buildClassMethod = builder.getClass().getMethod("buildProxyClass");
      Class<?> resultClass = (Class<?>) buildClassMethod.invoke(builder);
      Object proxy = unsafeCreateInstance(resultClass);
      Field handlerField = resultClass.getDeclaredField("$__handler");
      handlerField.setAccessible(true);
      handlerField.set(proxy, handler);
      return proxy;
    } catch (Exception e) {
      throw new IllegalStateException("Could not mock this concrete class", e);
    }
  }

  /** Attempt to construct an instance of the class using hacky methods to avoid calling super. */
  @SuppressWarnings("unchecked")
  private static <T> T unsafeCreateInstance(Class<T> clazz) {
    // try dalvikvm, pre-gingerbread
    try {
      final Method newInstance = ObjectInputStream.class.getDeclaredMethod(
          "newInstance", Class.class, Class.class);
      newInstance.setAccessible(true);
      return (T) newInstance.invoke(null, clazz, Object.class);
    } catch (Exception ignored) {}
    // try dalvikvm, post-gingerbread
    try {
      Method getConstructorId = ObjectStreamClass.class.getDeclaredMethod(
          "getConstructorId", Class.class);
      getConstructorId.setAccessible(true);
      final int constructorId = (Integer) getConstructorId.invoke(null, Object.class);
      final Method newInstance = ObjectStreamClass.class.getDeclaredMethod(
          "newInstance", Class.class, int.class);
      newInstance.setAccessible(true);
      return (T) newInstance.invoke(null, clazz, constructorId);
    } catch (Exception ignored) {}
    throw new IllegalStateException("unsafe create instance failed");
  }
}
