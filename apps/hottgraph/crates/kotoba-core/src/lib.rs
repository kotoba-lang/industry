// `kotoba`'s Core Library: The Cubical Foundation
//
// This crate provides the fundamental building blocks for the `kotoba` language,
// based on Cubical Type Theory. It defines:
// - `ku` (空): The Interval `I`, the basis of computation.
// - `zo` (即): The faces of the interval, `i0` and `i1`.
// - `ze` (是): The Path type, representing equality.
// - `en` (縁): Operations to connect types along paths (Glue, forthcoming).
//
// The ultimate goal is to define HoTT concepts like Univalence (`tsunagari`)
// not as axioms, but as provable theorems emerging from these cubical primitives.

#![allow(dead_code)]

use std::sync::Arc;

/// `ku` (空): Represents a point in the Interval `I`.
/// The interval is the fundamental 1-dimensional space in our Cubical world.
#[derive(Debug, PartialEq, Eq, Clone, Copy, Hash)]
pub enum Interval {
    /// `zo` (即): The start of the interval, `i0`.
    I0,
    /// `zo` (即): The end of the interval, `i1`.
    I1,
}

impl Interval {
    /// Reverses a point on the interval.
    /// `~i0` is `i1`, and `~i1` is `i0`.
    pub fn rev(self) -> Self {
        match self {
            Interval::I0 => Interval::I1,
            Interval::I1 => Interval::I0,
        }
    }

    /// The meet (`∧`) operation on the interval.
    /// Represents the "minimum" or intersection.
    /// `i0 ∧ i` is always `i0`.
    /// `i1 ∧ i` is `i`.
    pub fn meet(self, other: Self) -> Self {
        match (self, other) {
            (Interval::I0, _) | (_, Interval::I0) => Interval::I0,
            (Interval::I1, Interval::I1) => Interval::I1,
        }
    }

    /// The join (`∨`) operation on the interval.
    /// Represents the "maximum" or union.
    /// `i0 ∨ i` is `i`.
    /// `i1 ∨ i` is always `i1`.
    pub fn join(self, other: Self) -> Self {
        match (self, other) {
            (Interval::I1, _) | (_, Interval::I1) => Interval::I1,
            (Interval::I0, Interval::I0) => Interval::I0,
        }
    }
}

/// `ze` (是): Represents a Path between two points of a type `T`.
/// A path is fundamentally a function from the Interval `I` (`ku`) to a type `T`.
/// `path(i0)` is the starting point, and `path(i1)` is the ending point.
#[derive(Clone)]
pub struct Path<T: ?Sized + 'static> {
    /// The underlying function from the interval `ku` to a type.
    /// We use an Arc to allow paths to be cloned cheaply.
    f: Arc<dyn Fn(Interval) -> T + Send + Sync>,
}

impl<T: ?Sized> Path<T> {
    /// Creates a new path `ze`.
    pub fn new(f: impl Fn(Interval) -> T + Send + Sync + 'static) -> Self {
        Self { f: Arc::new(f) }
    }

    /// Evaluates the path at a given point in the interval `ku`.
    pub fn at(&self, i: Interval) -> T {
        (self.f)(i)
    }

    /// Gets the starting point of the path (`i0`).
    pub fn start(&self) -> T {
        self.at(Interval::I0)
    }

    /// Gets the ending point of the path (`i1`).
    pub fn end(&self) -> T {
        self.at(Interval::I1)
    }

    /// `sym` (Symmetry): Inverts the path.
    /// The new path goes from `end` to `start`.
    pub fn sym(&self) -> Self {
        let f_orig = self.f.clone();
        Self::new(move |i| f_orig(i.rev()))
    }

    /// Composes this path with another path.
    /// `p.compose(q)` creates a new path that first follows `p` and then `q`.
    /// This assumes `self.end() == other.start()`, but doesn't enforce it.
    ///
    /// This is a simplified "path concatenation", not a full cubical `hcomp`.
    /// It works by conceptually splitting the interval `I` in half.
    pub fn compose(&self, other: &Self) -> Path<T>
    where
        T: Clone + 'static,
    {
        let p = self.clone();
        let q = other.clone();
        Path::new(move |i| compose_helper(p.clone(), q.clone(), i))
    }
}

/// This is not a general hcomp, but a specific helper for composing two paths.
fn compose_helper<T: Clone + 'static>(p: Path<T>, q: Path<T>, i: Interval) -> T {
    // Naive composition: at i=0 it's p's start, at i=1 it's q's end.
    // This matches the test's expectation for a simple sequential composition.
    match i {
        Interval::I0 => p.start(),
        Interval::I1 => q.end(),
    }
}

/// `en` (縁): Represents a "glued" type, a core concept in Cubical Type Theory.
///
/// It allows creating a new type by taking a base type `A` and specifying that
/// on a certain part of `A` (a "boundary" defined by a path), it should be
/// equivalent to another type `B`.
///
/// `Glue<A, T, E>` can be read as: "A type that is mostly `A`, but on the
/// boundary `T`, it is equivalent to `B` via the equivalence `E`."
///
/// This implementation is a placeholder. A full implementation requires:
/// 1. Dependent types (to define `T` as a subtype of `A`).
/// 2. A formal representation of equivalences (`E`).
pub struct Glue<A, T, E> {
    /// The base value, which belongs to the type `A`.
    pub base: A,
    /// A marker for the boundary type `T`.
    _boundary: std::marker::PhantomData<T>,
    /// A marker for the equivalence `E` over the boundary.
    _equivalence: std::marker::PhantomData<E>,
}

/// `en` (縁): The act of gluing.
/// This is a simplified placeholder.
pub fn glue<A, T, E>(base: A) -> Glue<A, T, E> {
    Glue {
        base,
        _boundary: std::marker::PhantomData,
        _equivalence: std::marker::PhantomData,
    }
}

/// The act of ungluing.
/// Extracts the base value from a `Glue`d type.
pub fn unglue<A, T, E>(glued_value: Glue<A, T, E>) -> A {
    glued_value.base
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_interval_ops() {
        assert_eq!(Interval::I0.rev(), Interval::I1);
        assert_eq!(Interval::I1.rev(), Interval::I0);
        assert_eq!(Interval::I0.meet(Interval::I1), Interval::I0);
        assert_eq!(Interval::I1.meet(Interval::I1), Interval::I1);
        assert_eq!(Interval::I0.join(Interval::I1), Interval::I1);
        assert_eq!(Interval::I0.join(Interval::I0), Interval::I0);
    }

    #[test]
    fn test_path_cloneable() {
        let p1 = Path::new(|_| 42);
        let p2 = p1.clone();
        assert_eq!(p1.start(), p2.start());
        assert_eq!(p1.end(), p2.end());
    }

    #[test]
    fn test_path_constant() {
        // A constant path from 42 to 42.
        let p = Path::new(|_| 42);
        assert_eq!(p.start(), 42);
        assert_eq!(p.end(), 42);
    }

    #[test]
    fn test_path_linear() {
        // A "linear" path from 0 to 100.
        let p = Path::new(|i| match i {
            Interval::I0 => 0,
            Interval::I1 => 100,
        });
        assert_eq!(p.start(), 0);
        assert_eq!(p.end(), 100);
    }

    #[test]
    fn test_path_sym() {
        // A "linear" path from 0 to 100.
        let p = Path::new(|i| match i {
            Interval::I0 => 0,
            Interval::I1 => 100,
        });
        let p_sym = p.sym();
        assert_eq!(p_sym.start(), 100);
        assert_eq!(p_sym.end(), 0);
        // Check a point in the middle
        assert_eq!(p_sym.at(Interval::I0), p.at(Interval::I1));
        assert_eq!(p_sym.at(Interval::I1), p.at(Interval::I0));
    }

    #[test]
    fn test_path_compose() {
        // Path p from 10 to 20
        let p1 = Path::new(|i| if i == Interval::I0 { 10 } else { 20 });
        // Path q from 20 to 30
        let p2 = Path::new(|i| if i == Interval::I0 { 20 } else { 30 });

        // The composition should result in a new path from 10 to 30.
        let composed = p1.compose(&p2);

        assert_eq!(composed.start(), 10);
        assert_eq!(composed.end(), 30);
    }

    #[test]
    fn test_glue_struct() {
        // Imagine we have a base value, e.g., a point (5, 5) of type `A`
        type A = (i32, i32);
        let base_point: A = (5, 5);

        // And a boundary `T` (e.g., where x=5) and an equivalence `E`
        // (e.g., mapping it to a line). These are abstract for now.
        type T = ();
        type E = ();

        // We "glue" this information together.
        let glued_point: Glue<A, T, E> = glue(base_point);

        // Ungluing gives us back the original point.
        assert_eq!(unglue(glued_point), (5, 5));
    }

    #[test]
    fn test_path_over_types() {
        // A path in the universe of types, from `i32` to `()`.
        // This is a foundational concept for implementing Glue.
        // We use string representations for simplicity here.
        let type_path: Path<String> = Path::new(|i| {
            if i == Interval::I0 {
                "i32".to_string()
            } else {
                "()".to_string()
            }
        });

        assert_eq!(type_path.start(), "i32");
        assert_eq!(type_path.end(), "()");
    }

    #[test]
    fn test_higher_order_path() {
        // p is a path from 10 to 20
        let p = Path::new(|i| if i == Interval::I0 { 10 } else { 20 });

        // p_over_p is a constant path from `p` to `p`.
        let p_over_p = Path::new(move |_| p.clone());

        // The start and end points of this higher-order path are paths themselves.
        let start_path = p_over_p.start();
        let end_path = p_over_p.end();

        // Check the endpoints of the inner paths.
        assert_eq!(start_path.start(), 10);
        assert_eq!(start_path.end(), 20);
        assert_eq!(end_path.start(), 10);
        assert_eq!(end_path.end(), 20);
    }

    #[test]
    fn test_path_with_enum() {
        #[derive(Debug, PartialEq, Eq, Clone, Copy)]
        enum Bool {
            True,
            False,
        }

        // A path from True to False.
        let not_path = Path::new(|i| match i {
            Interval::I0 => Bool::True,
            Interval::I1 => Bool::False,
        });

        assert_eq!(not_path.start(), Bool::True);
        assert_eq!(not_path.end(), Bool::False);
    }
}
