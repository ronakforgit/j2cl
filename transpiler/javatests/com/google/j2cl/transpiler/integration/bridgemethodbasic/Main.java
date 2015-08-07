package com.google.j2cl.transpiler.integration.bridgemethodbasic;

class Parent<T> {
  @SuppressWarnings("unused")
  public T foo(T t) {
    return null;
  }
}

/**
 * Class that subclasses a generic class, and overrides a generic method.
 */
class Child extends Parent<AssertionError> {
  // a bridge method should be generated for Parent.foo(T).
  @Override
  public AssertionError foo(AssertionError t) {
    return t;
  }
}

/**
 * Class that subclasses a generic class, and does not override the generic method.
 */
class AnotherChild extends Parent<AssertionError> {
  // no bridge method should be generated, so no ClassCastException will be caught even an argument
  // of wrong type is passed.
}

public class Main {
  @SuppressWarnings({"rawtypes", "unchecked"})
  public Object test(Parent p, Object t) {
    return p.foo(t);
  }

  public static void main(String[] args) {
    Main m = new Main();

    AssertionError ae = new AssertionError();
    Object o = new Object();

    Child c = new Child();
    AnotherChild ac = new AnotherChild();

    assert m.test(c, ae) == ae;
    assert m.test(c, ae) == c.foo(ae);
    assert m.test(ac, ae) == null;
    assert m.test(ac, o) == null; // no ClassCastException.
    try {
      assert m.test(c, o) == o; // casting o to AssertionError, should cause ClassCastException.
      assert false : "ClassCastException should be thrown";
    } catch (ClassCastException e) {
      // expected.
    }
  }
}
