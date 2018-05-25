// !API_VERSION: 1.3
// !JVM_DEFAULT_MODE: compatibility
// FILE: Simple.java

public interface Simple extends KInterface2 {
    default String test() {
        return KInterface2.DefaultImpls.test2(this, "OK");
    }
}

// FILE: Foo.java
public class Foo implements Simple {
    public String test2(p: String) {
        return "fail"
    }
}

// FILE: main.kt
// JVM_TARGET: 1.8
// WITH_RUNTIME

interface KInterface<T>  {
    @JvmDefault
    fun test2(p: T): T {
        return p
    }
}

interface KInterface2 : KInterface<String> {

}


fun box(): String {
    return Foo().test()
}