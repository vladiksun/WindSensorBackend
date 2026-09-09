---
name: vavr-functional-programming
description: This skill provides guidance on using the Vavr library (formerly Javaslang) for functional programming in Java 8+. Use this skill whenever the user writes Java code involving Vavr types, asks about functional patterns in Java, works with immutable collections, Option/Try/Either/Lazy/Validation, tuples, function composition, pattern matching, or expresses interest in referential transparency, side-effect management, or persistent data structures. Use this skill when the user mentions Vavr, asks how to replace Java stdlib collections with functional alternatives, or needs help with functional error handling instead of exceptions.
---

# Vavr Functional Programming

This skill provides guidance on using the Vavr library (formerly Javaslang) for functional programming in Java 8+. Use this skill whenever the user writes Java code involving Vavr types, asks about functional patterns in Java, works with immutable collections, Option/Try/Either/Lazy/Validation, tuples, function composition, pattern matching, or expresses interest in referential transparency, side-effect management, or persistent data structures. Use this skill when the user mentions Vavr, asks how to replace Java stdlib collections with functional alternatives, or needs help with functional error handling instead of exceptions.

## Core Principles

**Think in values, not mutations.** Vavr is built around immutable, persistent data structures. Every operation returns a new instance rather than modifying the original. This gives you thread-safety for free, stable `equals`/`hashCode`, and referential transparency.

**Make side-effects explicit.** Instead of throwing exceptions, wrap computations that might fail in `Try`. Instead of returning `null`, use `Option`. Instead of silent state changes, return new instances. These types make the possibility of failure or absence part of the type signature, so callers can't ignore them.

**Prefer expressions over statements.** A `void` return type is a code smell in functional code — it signals a side-effect. Use methods that return values so they can be composed, chained, and reasoned about.

## Option — Never Return null

Use `io.vavr.control.Option` instead of `null` or `java.util.Optional`.

```java
// ✅ Good: Option makes absence explicit
Option<String> findName(Id id) {
    return Option.of(nameRepository.findById(id));
}

// ❌ Bad: null is invisible in the type
String findName(Id id) {
    return nameRepository.findById(id); // might be null!
}
```

**Key difference from Java's Optional:** Vavr's `Option.map()` **does NOT** swallow `null`. If a mapper returns `null`, you get `Some(null)` which will throw `NullPointerException` on the next operation. This is intentional — it forces you to handle `null` properly using `flatMap`:

```java
// ✅ Correct: use flatMap when the mapper might produce null
Option<String> result = maybeValue
    .map(this::mightReturnNull)
    .flatMap(Option::of);  // null becomes None

// ✅ Alternative: wrap at the source
Option<String> result = maybeValue
    .flatMap(s -> Option.of(mightReturnNull(s)));
```

**Common patterns:**
```java
Option.of(value)
    .map(this::transform)
    .filter(this::isValid)
    .getOrElse(defaultValue);

Option.ofNullable(rawValue)  // wraps null as None
    .orElseCompute(() -> expensiveDefault());
```

## Try — Exceptions as Values

Wrap code that might throw in `Try` to make exceptions part of the return type instead of breaking control flow.

```java
// ✅ Good: failure is explicit in the type
Try<Integer> divide(Integer a, Integer b) {
    return Try.of(() -> a / b);
}

// ❌ Bad: exception breaks control flow
int divide(int a, int b) {
    return a / b; // throws ArithmeticException!
}
```

**Recovering from specific exceptions with pattern matching:**
```java
import static io.vavr.API.*;
import static io.vavr.Predicates.*;

Result result = Try.of(this::riskyWork)
    .recover(x -> Match(x).of(
        Case($(instanceOf(IOException.class)), e -> handleIo(e)),
        Case($(instanceOf(TimeoutException.class)), e -> handleTimeout(e)),
        Case($(), e -> handleGeneric(e))
    ))
    .getOrElse(fallback);
```

**Chaining risky operations:**
```java
Try.of(() -> readFile(path))
    .flatMap(this::parse)
    .flatMap(this::validate)
    .onSuccess(result -> process(result))
    .onFailure(err -> log.error("Failed", err));
```

## Either — Two Possible Types

Use `Either<Error, Success>` when you have two distinct outcomes. By convention, **Left = error**, **Right = success**.

```java
Either<String, Integer> parse(String input) {
    return input.matches("-?\\d+")
        ? Either.right(Integer.parseInt(input))
        : Either.left("Invalid number: " + input);
}

// Operations on the Right side
Either<String, Integer> doubled = parse(input)
    .right()
    .map(i -> i * 2)
    .toEither();
```

## Validation — Accumulating Errors

Unlike `Try` or `Either` which short-circuit on the first error, `Validation` continues processing and **accumulates all errors**. This is ideal for form validation or multi-field checks.

```java
class PersonValidator {
    Validation<Seq<String>, Person> validatePerson(String name, int age) {
        return Validation.combine(
            validateName(name),
            validateAge(age)
        ).ap(Person::new);
    }

    Validation<String, String> validateName(String name) {
        return name.matches("[a-zA-Z ]+")
            ? Validation.valid(name)
            : Validation.invalid("Name contains invalid characters");
    }

    Validation<String, Integer> validateAge(int age) {
        return age > 0
            ? Validation.valid(age)
            : Validation.invalid("Age must be positive");
    }
}
```

## Tuples — Fixed-Size Heterogeneous Groups

Tuples combine a fixed number of elements of different types (up to 8). They're immutable and useful for multi-value returns.

```java
// Create
Tuple2<String, Integer> pair = Tuple.of("Java", 8);

// Access
String name = pair._1;
Integer version = pair._2;

// Component-wise map (bimap)
Tuple2<String, Integer> mapped = pair.map(
    s -> s.toLowerCase(),
    i -> i + 1
);

// Transform to a different type
String result = pair.apply((s, i) -> s + " " + i);
```

Use tuples for quick multi-value returns. For anything more structured, prefer a dedicated class with meaningful field names.

## Functions — Function0 through Function8

Vavr provides `Function0` through `Function8`, extending beyond Java's `Function` and `BiFunction`. These are composable, liftable, curriable, and memoizable.

```java
// Create
Function2<Integer, Integer, Integer> sum = (a, b) -> a + b;

// From method reference
Function3<String, String, String, String> concat =
    Function3.of(this::concatenateThree);
```

**Composition:**
```java
Function1<Integer, Integer> plusOne = a -> a + 1;
Function1<Integer, Integer> multiplyByTwo = a -> a * 2;

// plusOne first, then multiplyByTwo
Function1<Integer, Integer> combined = plusOne.andThen(multiplyByTwo);
// or equivalently:
Function1<Integer, Integer> combined = multiplyByTwo.compose(plusOne);
```

**Lifting — turning partial functions into total ones:**
```java
// A partial function that throws on invalid input
Function2<Integer, Integer, Integer> divide = (a, b) -> a / b;

// Lifted: returns Option instead of throwing
Function2<Integer, Integer, Option<Integer>> safeDivide = Function2.lift(divide);

safeDivide.apply(4, 2); // Some(2)
safeDivide.apply(1, 0); // None
```

**Partial application — fixing some arguments:**
```java
Function2<Integer, Integer, Integer> sum = (a, b) -> a + b;
Function1<Integer, Integer> add2 = sum.apply(2); // fixes first arg to 2
add2.apply(4); // 6
```

**Currying — converting to chained Function1s:**
```java
Function2<Integer, Integer, Integer> sum = (a, b) -> a + b;
Function1<Integer, Integer> add2 = sum.curried().apply(2);
add2.apply(4); // 6
```

Partial application reduces arity by the number of fixed arguments. Currying always produces a chain of `Function1`s. They look the same for `Function2` but diverge for higher arities.

**Memoization — caching results:**
```java
Function0<Double> cachedRandom = Function0.of(Math::random).memoized();
// Subsequent calls return the same value
```

## Lazy — Memoized Deferred Computation

`Lazy` is like `Supplier` but evaluates only once and caches the result. It's referentially transparent.

```java
Lazy<Double> lazy = Lazy.of(Math::random);
lazy.isEvaluated(); // false
lazy.get();         // evaluates, e.g. 0.123
lazy.isEvaluated(); // true
lazy.get();         // returns cached 0.123
```

Use `Lazy` for expensive computations that should happen at most once, or when you need to defer evaluation until a value is actually needed.

## Collections — Immutable and Persistent

Vavr collections are in `io.vavr.collection.*`. They're immutable, persistent (previous versions remain valid), and support fluent chaining without the boilerplate of Java Streams.

**List:**
```java
// Vavr: direct, no stream() needed
List.of(1, 2, 3).sum();

// vs Java 8:
Arrays.asList(1, 2, 3).stream().reduce((i, j) -> i + j);
```

**Stream (lazy linked list):**
```java
// Infinite sequences work naturally
Stream.from(1)
    .filter(i -> i % 2 == 0)
    .take(10)
    .toList(); // [2, 4, 6, 8, 10, 12, 14, 16, 18, 20]
```

**Choosing the right collection:**
- **List** — O(1) prepend/head/tail, O(n) random access. Best for sequential processing and LIFO patterns.
- **Vector** — O(1) effective for both random access and modification. Best when you need indexed access + immutability.
- **Array** — backed by Java array, O(1) random access, O(n) insert/remove.
- **Queue** — O(1) enqueue/dequeue using a two-list implementation.
- **HashMap/HashSet** — O(1) effective via HAMT (Hash Array Mapped Trie).
- **TreeMap/TreeSet** — O(log n) via Red-Black tree. Use when you need ordered data.

**Prefer Vavr collections over Java stdlib:**
```java
// ✅ Vavr: clean chaining
List.of(1, 2, 3, 4)
    .filter(i -> i % 2 == 0)
    .map(i -> i * 10)
    .mkString(", ");

// ❌ Java: stream boilerplate
Arrays.asList(1, 2, 3, 4)
    .stream()
    .filter(i -> i % 2 == 0)
    .map(i -> i * 10)
    .collect(Collectors.joining(", "));
```

## Pattern Matching

Vavr's `Match` API provides Scala-like pattern matching as an expression (it returns a value).

```java
import static io.vavr.API.*;
import static io.vavr.Predicates.*;

String result = Match(value).of(
    Case($(is(1)), "one"),
    Case($(is(2)), "two"),
    Case($(), "other")  // wildcard: always matches, prevents MatchError
);
```

**Matching types:**
```java
Number result = Match(obj).of(
    Case($(instanceOf(Integer.class)), i -> i + 1),
    Case($(instanceOf(Double.class)), d -> d + 1),
    Case($(), o -> throw new NumberFormatException())
);
```

**Matching Vavr types with predefined patterns:**
```java
import static io.vavr.Patterns.*;

Match(_try).of(
    Case($Success($()), value -> handleSuccess(value)),
    Case($Failure($()), ex -> handleFailure(ex))
);

Match(option).of(
    Case($Some($()), value -> handlePresent(value)),
    Case($None(), this::handleAbsent)
);
```

**Returning Optional when exhaustiveness isn't guaranteed:**
```java
Option<String> result = Match(i).option(
    Case($(0), "zero"),
    Case($(1), "one")
    // no wildcard: returns None for unmatched values
);
```

**Side-effects in Match:** Wrap `run()` inside a lambda — never use `run()` directly as the Case return value, or it evaluates eagerly before matching.

```java
// ✅ Correct: run inside lambda
Match(arg).of(
    Case($(isIn("-h", "--help")), o -> run(this::displayHelp)),
    Case($(), o -> run(() -> throw new IllegalArgumentException(arg)))
);

// ❌ Wrong: run evaluated eagerly before Match runs
Case($(isIn("-h")), run(this::displayHelp))
```

## Common Anti-Patterns to Avoid

| Anti-Pattern | Preferred Approach |
|---|---|
| Returning `null` | Return `Option.none()` |
| Throwing exceptions for flow control | Return `Try` or `Either` |
| `for` loops with mutable state | Use `map`, `flatMap`, `foldLeft` |
| `java.util.stream.Stream` boilerplate | Use Vavr collections directly |
| `java.util.Optional` | Use Vavr `Option` (better monad semantics) |
| `if-else` chains for type dispatch | Use `Match` with patterns |
| Validating one field at a time | Use `Validation.combine()` to accumulate errors |

## Quick Reference

| Type | Purpose | Key Methods |
|---|---|---|
| `Option<T>` | Optional value | `of()`, `ofNullable()`, `map()`, `flatMap()`, `getOrElse()` |
| `Try<T>` | Computation that may throw | `of()`, `recover()`, `getOrElse()`, `onSuccess()`, `onFailure()` |
| `Either<L,R>` | One of two types (Left=error, Right=success) | `left()`, `right()`, `toEither()` |
| `Validation<E,T>` | Accumulate errors | `valid()`, `invalid()`, `combine()`, `ap()` |
| `Lazy<T>` | Memoized deferred computation | `of()`, `get()`, `isEvaluated()` |
| `TupleN` | Fixed-size heterogeneous group | `Tuple.of()`, `map()`, `apply()` |
| `FunctionN` | N-arity function | `compose()`, `andThen()`, `lift()`, `curried()`, `apply()`, `memoized()` |
| `List`, `Stream`, `Vector` | Immutable sequences | `of()`, `map()`, `filter()`, `foldLeft()`, `mkString()` |
| `Match` | Pattern matching expression | `Match().of()`, `Match().option()` |

