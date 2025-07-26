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
pub struct Path<T: 'static> {
    /// The underlying function from the interval `ku` to a type.
    /// We use an Arc to allow paths to be cloned cheaply.
    f: Arc<dyn Fn(Interval) -> T + Send + Sync>,
}

impl<T> Path<T> {
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
    /// Note: This is a simplified "path concatenation", not a full cubical `hcomp`.
    /// It works by conceptually splitting the interval `I` in half.
    pub fn compose(&self, other: &Self) -> Self
    where
        T: Clone,
    {
        let p1 = self.f.clone();
        let p2 = other.f.clone();

        Self::new(move |i| {
            // This is a naive composition. A proper cubical composition is more complex.
            // For this version, we don't split the interval but just connect the endpoints.
            // This is not sound, but serves as a placeholder for API design.
            // A slightly better version would need a way to "split" the interval `i`.
            if i == Interval::I0 {
                p1(Interval::I0)
            } else {
                p2(Interval::I1)
            }
        })
    }
}

/// A "face" of a cube, specifying a boundary.
pub type Face = (Interval, Interval);

/// Represents the composition of paths.
/// For now, this is a simplified version. A full implementation
/// would require a more complex handling of the interval algebra.
///
/// If `p: T` is a path from `A` to `B`, and we have a new path `q`
/// defined on the boundary where `p` is `B`, we can compose them.
///
/// This function is a placeholder for a future, more rigorous `hcomp`.
pub fn compose<T: Clone + 'static>(
    p: &Path<T>,
    q: &Path<Path<T>>, // A path of paths
    i: Interval,
) -> Path<T> {
    let p_clone = p.clone();
    let q_clone = q.clone();

    Path::new(move |j| {
        match i {
            Interval::I0 => p_clone.at(j),
            Interval::I1 => q_clone.at(j).at(j), // Simplified; should be more complex
        }
    })
}

/// `en` (縁): Represents a "glued" type.
///
/// `Glue<T, P>` represents a value of type `T` that is "equivalent"
/// to some other structure `A` along a path `p`. `P` itself is a path,
/// linking the type `T` to `A` where a certain condition holds.
///
/// This is a highly simplified placeholder for a full Glue Type. A real
/// implementation requires dependent types and a way to model the equivalence `A`.
/// For now, we represent the "equivalent structure" abstractly with another path.
pub struct Glue<T, P> {
    /// The base value of the original type `T`.
    pub base: T,
    /// A path that represents the "proof" or "reason" for the glue.
    /// In a real system, this would be a path `p: A -> B` where `self.base`
    /// corresponds to a point on that path under some equivalence.
    pub path_over_base: P,
}

/// `en` (縁): The act of gluing.
/// Creates a `Glue`d value. This is a simplified placeholder.
pub fn glue<T, P>(base: T, path_over_base: P) -> Glue<T, P> {
    Glue {
        base,
        path_over_base,
    }
}

/// The act of ungluing.
/// Extracts the base value from a `Glue`d type.
pub fn unglue<T, P>(glued_value: Glue<T, P>) -> T {
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

        // The composition should go from 10 to 30.
        let composed = p1.compose(&p2);

        assert_eq!(composed.start(), 10);
        assert_eq!(composed.end(), 30);
    }

    #[test]
    fn test_glue_struct() {
        // Imagine we have a base value, e.g., a point (5, 5)
        let base_point = (5, 5);

        // And a path representing some equivalence.
        // For example, a path saying that for the point (5, 5), it is "equivalent"
        // to being on a line from (0,0) to (10,10).
        let path_proof = Path::new(|i| {
            if i == Interval::I0 {
                (0, 0)
            } else {
                (10, 10)
            }
        });

        // We "glue" this information together.
        let glued_point = glue(base_point, path_proof);

        // Ungluing gives us back the original point.
        assert_eq!(unglue(glued_point), (5, 5));
    }
}
