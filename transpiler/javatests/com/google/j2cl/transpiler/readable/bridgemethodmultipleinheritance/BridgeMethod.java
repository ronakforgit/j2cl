package com.google.j2cl.transpiler.readable.bridgemethodmultipleinheritance;

interface SomeInterface<T, S> {
  void foo(T t, S s);

  void foo(T t, Number n);
}

class SuperParent<T, S> {
  public void foo(T t, S s) {}
}

class Parent<T extends Error> extends SuperParent<T, Number> {
  // foo__Error__Number
  // there should be a bridge method foo__Object__Object for SuperParent.foo(T,S),
  // which delegates to foo(Error, Number)
  @Override
  public void foo(T t, Number s) {}

  public <T extends Number> void bar(T t) {} // bar__Number

  public <T> void fun(T t) {}
}

public class BridgeMethod extends Parent<AssertionError>
    implements SomeInterface<AssertionError, Number> {
  // foo__AssertionError__Number, there should be a bridge method
  // foo__Error__Number for Parent.foo(T, Number), and a bridge method
  // foo__Object__Object for SuperParent.foo(T, S) and for SomeInterface.foo(T, S),
  // and a bridge method foo__Object__Number for SomeInterface.foo(T, Number).
  @Override
  public void foo(AssertionError a, Number n) {}

  @Override
  public void bar(Number t) {} // no bridge method for Parent.bar(T), same signature.

  public void fun(Number t) {} // not override, no bridge method.
}
